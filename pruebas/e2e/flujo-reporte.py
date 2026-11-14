#!/usr/bin/env python3
"""
Prueba de punta a punta de la fase 2 contra el entorno de Docker Compose:

  cliente -> gateway -> reportes -> persistencia -> PostgreSQL
                                        └─ outbox -> RabbitMQ -> atencion -> persistencia (caso)

Verifica:
  1. Crear un reporte (201, Location, X-Correlation-Id propagado, municipio DIVIPOLA).
  2. Idempotencia: repetir con el mismo X-Correlation-Id devuelve el mismo reporte.
  3. Consultar el reporte con su historia.
  4. Validación RNF-DAT1 en problem+json.
  5. reporte.creado publicado y consumido: Atención abre el caso.
  6. Trazabilidad: el mismo correlationId aparece en los logs de los tres servicios.
  7. RNF-DC2: con Atención detenida el reporte se registra igual, y el caso se
     abre cuando Atención vuelve (el evento espera en su cola).
  8. Ningún mensaje terminó en la cola de mensajes fallidos.

Requiere el compose arriba con el PostgreSQL local (valor por defecto de .env).
Uso (desde la raíz):  python3 pruebas/e2e/flujo-reporte.py
"""
import base64
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

RAIZ = Path(__file__).resolve().parents[2]
GATEWAY = "http://localhost:8080"
RABBIT_API = "http://127.0.0.1:15672/api"


def leer_env():
    valores = {}
    for linea in (RAIZ / ".env").read_text(encoding="utf-8").splitlines():
        if "=" in linea and not linea.lstrip().startswith("#"):
            clave, valor = linea.split("=", 1)
            valores[clave.strip()] = valor.strip()
    return valores


ENV = leer_env()
fallos = []


def comprobar(condicion, descripcion):
    print(("  OK    " if condicion else "  FALLA ") + descripcion)
    if not condicion:
        fallos.append(descripcion)
    return condicion


def http(metodo, ruta, cuerpo=None, correlacion=None):
    cabeceras = {"content-type": "application/json"}
    if correlacion:
        cabeceras["X-Correlation-Id"] = correlacion
    peticion = urllib.request.Request(GATEWAY + ruta, method=metodo, headers=cabeceras,
                                      data=json.dumps(cuerpo).encode() if cuerpo is not None else None)
    try:
        with urllib.request.urlopen(peticion, timeout=15) as r:
            return r.status, dict(r.headers), json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), json.loads(e.read() or b"null")


def compose(*argumentos):
    return subprocess.run(["docker", "compose", *argumentos], cwd=RAIZ, capture_output=True, text=True,
                          stdin=subprocess.DEVNULL, check=False)


def sql(consulta):
    r = compose("exec", "-T", "postgres-local", "psql", "-U", ENV["POSTGRES_LOCAL_USUARIO"], "-d", "servired",
                "-tAc", consulta)
    return r.stdout.strip()


def esperar(condicion, segundos=30):
    limite = time.time() + segundos
    while time.time() < limite:
        if condicion():
            return True
        time.sleep(0.5)
    return False


def caso_de(id_reporte):
    return sql(f"SELECT estado FROM atencion.casos WHERE id_reporte = '{id_reporte}'")


def mensajes_en_dlq(cola):
    auth = base64.b64encode(f"{ENV['RABBITMQ_USUARIO']}:{ENV['RABBITMQ_CLAVE']}".encode()).decode()
    peticion = urllib.request.Request(f"{RABBIT_API}/queues/%2F/{cola}/get", method="POST",
                                      headers={"Authorization": f"Basic {auth}", "content-type": "application/json"},
                                      data=json.dumps({"count": 10, "ackmode": "ack_requeue_true",
                                                       "encoding": "auto"}).encode())
    with urllib.request.urlopen(peticion, timeout=10) as r:
        return len(json.loads(r.read()))


def nuevo_reporte(horas_atras=1.0):
    inicio = datetime.now(timezone(timedelta(hours=-5))) - timedelta(hours=horas_atras)
    return {"tipoServicio": "ENERGIA", "ubicacion": {"latitud": 4.6287, "longitud": -74.0648},
            "inicioEstimado": inicio.isoformat(timespec="seconds"), "magnitud": "CUADRA",
            "descripcion": "Sin luz en toda la cuadra (prueba e2e)"}


print("0) Gateway disponible")
estado, _, cuerpo = http("GET", "/actuator/health")
if not comprobar(estado == 200 and cuerpo["status"] == "UP", "gateway UP"):
    sys.exit("Levante el entorno con: docker compose up -d --build --wait")

print("1) Crear un reporte")
correlacion = str(uuid.uuid4())
estado, cabeceras, reporte = http("POST", "/api/v1/reportes", nuevo_reporte(), correlacion)
comprobar(estado == 201, f"201 Created (fue {estado})")
id_reporte = reporte.get("idReporte") if isinstance(reporte, dict) else None
comprobar(cabeceras.get("Location") == f"/api/v1/reportes/{id_reporte}", "Location apunta al reporte")
comprobar(cabeceras.get("X-Correlation-Id") == correlacion, "el gateway devuelve el mismo X-Correlation-Id")
comprobar(reporte.get("estado") == "RECIBIDO", "estado RECIBIDO")
comprobar(reporte.get("codigoMunicipio") == "11001", "municipio resuelto: 11001 (Bogotá)")
comprobar("idCiudadano" not in reporte, "la respuesta no expone el identificador del ciudadano")

print("2) Idempotencia por X-Correlation-Id")
estado, _, repetido = http("POST", "/api/v1/reportes", nuevo_reporte(), correlacion)
comprobar(estado == 201 and repetido.get("idReporte") == id_reporte, "el reintento devuelve el mismo reporte")
comprobar(sql(f"SELECT count(*) FROM infraestructura.outbox WHERE correlation_id = '{correlacion}'") == "1",
          "un solo evento en el outbox")

print("3) Consultar el reporte")
estado, _, consultado = http("GET", f"/api/v1/reportes/{id_reporte}")
comprobar(estado == 200 and consultado["historia"][0]["estado"] == "RECIBIDO", "200 con la historia")
estado, _, _ = http("GET", f"/api/v1/reportes/{uuid.uuid4()}")
comprobar(estado == 404, "404 para un reporte inexistente")

print("4) Validación (RNF-DAT1)")
estado, cabeceras, problema = http("POST", "/api/v1/reportes", nuevo_reporte(horas_atras=-3))
comprobar(estado == 400 and cabeceras.get("Content-Type", "").startswith("application/problem+json"),
          "400 problem+json para un inicio futuro")
comprobar(any(e["campo"] == "inicioEstimado" for e in problema.get("errores", [])), "indica el campo inválido")

print("5) reporte.creado -> Atención abre el caso")
comprobar(esperar(lambda: caso_de(id_reporte) == "RECIBIDO"), "caso RECIBIDO en atencion.casos")
comprobar(sql(f"SELECT publicado_en IS NOT NULL FROM infraestructura.outbox "
              f"WHERE correlation_id = '{correlacion}'") == "t", "evento marcado como publicado")

print("6) Trazabilidad del correlationId en los logs")
for servicio in ["gateway", "reportes", "persistencia", "atencion"]:
    comprobar(esperar(lambda s=servicio: correlacion in compose("logs", "--no-color", s).stdout, 10),
              f"aparece en los logs de {servicio}")

print("7) RNF-DC2: Atención caída no impide registrar")
compose("stop", "atencion")
correlacion_2 = str(uuid.uuid4())
estado, _, reporte_2 = http("POST", "/api/v1/reportes", nuevo_reporte(), correlacion_2)
comprobar(estado == 201, "el reporte se registra con Atención detenida")
id_2 = reporte_2.get("idReporte")
time.sleep(2)
comprobar(caso_de(id_2) == "", "aún no hay caso (el evento espera en la cola)")
compose("start", "atencion")
comprobar(esperar(lambda: caso_de(id_2) == "RECIBIDO", 60), "al volver Atención el caso se abre")

print("8) Cola de mensajes fallidos vacía")
comprobar(mensajes_en_dlq("atencion.eventos-reportes.dlq") == 0, "atencion.eventos-reportes.dlq vacía")

print("Resultado:", "TODO OK" if not fallos else f"{len(fallos)} fallo(s)")
sys.exit(1 if fallos else 0)
