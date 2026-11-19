#!/usr/bin/env python3
"""
Prueba de punta a punta de la fase 2 contra el entorno de Docker Compose:

  cliente -> gateway -> reportes -> persistencia -> PostgreSQL
                                        └─ outbox -> RabbitMQ -> atencion -> persistencia (caso)

Verifica:
  1. Crear un reporte (201, Location, X-Correlation-Id propagado, municipio DIVIPOLA).
  2. Idempotencia: repetir con el mismo X-Correlation-Id devuelve el mismo reporte; si lo
     envía OTRA cuenta no es un reintento: 409 CORRELACION_AJENA, sin devolver el reporte
     ajeno ni crear otro (ADR-23).
  3. Consultar el reporte con su historia.
  4. Validación RNF-DAT1 en problem+json.
  5. reporte.creado publicado y consumido: Atención abre el caso.
  6. Trazabilidad: el mismo correlationId aparece en los logs de los tres servicios.
  7. RNF-DC2: con Atención detenida el reporte se registra igual, y el caso se
     abre cuando Atención vuelve (el evento espera en su cola).
  8. Ningún mensaje terminó en la cola de mensajes fallidos.

Desde la fase 3 el ciudadano se registra e inicia sesión: cada petición lleva su JWT.
Requiere el compose arriba con el PostgreSQL local (valor por defecto de .env).
Uso (desde la raíz):  python3 pruebas/e2e/flujo-reporte.py
"""
import time
import uuid
from datetime import datetime, timedelta, timezone

from comun import (comprobar, compose, esperar, http, mensajes_en_cola, registrar_y_entrar, sql,
                   terminar)


def caso_de(id_reporte):
    return sql(f"SELECT estado FROM atencion.casos WHERE id_reporte = '{id_reporte}'")


def nuevo_reporte(horas_atras=1.0):
    inicio = datetime.now(timezone(timedelta(hours=-5))) - timedelta(hours=horas_atras)
    return {"tipoServicio": "ENERGIA", "ubicacion": {"latitud": 4.6287, "longitud": -74.0648},
            "inicioEstimado": inicio.isoformat(timespec="seconds"), "magnitud": "CUADRA",
            "descripcion": "Sin luz en toda la cuadra (prueba e2e)"}


print("0) Gateway disponible y ciudadano autenticado")
estado, _, cuerpo = http("GET", "/readyz")
if not comprobar(estado == 200 and cuerpo["status"] == "UP", "gateway UP"):
    raise SystemExit("Levante el entorno con: docker compose up -d --build --wait")
sesion, _ = registrar_y_entrar()
token = sesion["tokenAcceso"]
id_ciudadano = sesion["cuenta"]["idCuenta"]

print("1) Crear un reporte")
correlacion = str(uuid.uuid4())
estado, cabeceras, reporte = http("POST", "/api/v1/reportes", nuevo_reporte(), correlacion, token)
comprobar(estado == 201, f"201 Created (fue {estado})")
id_reporte = reporte.get("idReporte") if isinstance(reporte, dict) else None
comprobar(cabeceras.get("Location") == f"/api/v1/reportes/{id_reporte}", "Location apunta al reporte")
comprobar(cabeceras.get("X-Correlation-Id") == correlacion, "el gateway devuelve el mismo X-Correlation-Id")
comprobar(reporte.get("estado") == "RECIBIDO", "estado RECIBIDO")
comprobar(reporte.get("codigoMunicipio") == "11001", "municipio resuelto: 11001 (Bogotá)")
comprobar("idCiudadano" not in reporte, "la respuesta no expone el identificador del ciudadano")
comprobar(sql(f"SELECT id_ciudadano FROM reportes.reportes WHERE id = '{id_reporte}'") == id_ciudadano,
          "el reporte queda a nombre del ciudadano del token (claim sub)")

print("2) Idempotencia por X-Correlation-Id")
estado, _, repetido = http("POST", "/api/v1/reportes", nuevo_reporte(), correlacion, token)
comprobar(estado == 201 and repetido.get("idReporte") == id_reporte, "el reintento devuelve el mismo reporte")
comprobar(sql(f"SELECT count(*) FROM infraestructura.outbox WHERE correlation_id = '{correlacion}' AND tipo = 'reporte.creado'") == "1",
          "un solo evento en el outbox")
otra_cuenta = registrar_y_entrar()[0]["tokenAcceso"]
estado, _, ajena = http("POST", "/api/v1/reportes", nuevo_reporte(), correlacion, otra_cuenta)
comprobar(estado == 409 and (ajena or {}).get("motivo") == "CORRELACION_AJENA",
          f"otra cuenta con el mismo X-Correlation-Id recibe 409 CORRELACION_AJENA ({estado})")
comprobar(reporte["idReporte"] not in str(ajena) and "Sin luz" not in str(ajena),
          "sin ver el reporte ajeno")
comprobar(sql(f"SELECT count(*) FROM infraestructura.outbox WHERE correlation_id = '{correlacion}' AND tipo = 'reporte.creado'") == "1",
          "ni crear otro reporte")

print("3) Consultar el reporte")
estado, _, consultado = http("GET", f"/api/v1/reportes/{id_reporte}", token=token)
comprobar(estado == 200 and consultado["historia"][0]["estado"] == "RECIBIDO", "200 con la historia")
estado, _, _ = http("GET", f"/api/v1/reportes/{uuid.uuid4()}", token=token)
comprobar(estado == 404, "404 para un reporte inexistente")

print("4) Validación (RNF-DAT1)")
estado, cabeceras, problema = http("POST", "/api/v1/reportes", nuevo_reporte(horas_atras=-3), token=token)
comprobar(estado == 400 and cabeceras.get("Content-Type", "").startswith("application/problem+json"),
          "400 problem+json para un inicio futuro")
comprobar(any(e["campo"] == "inicioEstimado" for e in problema.get("errores", [])), "indica el campo inválido")

print("5) reporte.creado -> Atención abre el caso")
comprobar(esperar(lambda: caso_de(id_reporte) == "RECIBIDO"), "caso RECIBIDO en atencion.casos")
comprobar(sql(f"SELECT publicado_en IS NOT NULL FROM infraestructura.outbox "
              f"WHERE correlation_id = '{correlacion}' AND tipo = 'reporte.creado'") == "t",
          "evento marcado como publicado")
# La apertura del caso publica caso.actualizado con el mismo correlationId (ADR-24).
comprobar(esperar(lambda: sql(f"SELECT count(*) FROM infraestructura.outbox WHERE correlation_id = '{correlacion}' "
                              "AND tipo = 'caso.actualizado'") == "1", 10),
          "la apertura del caso publica caso.actualizado con el mismo correlationId")

print("6) Trazabilidad del correlationId en los logs")
for servicio in ["gateway", "reportes", "persistencia", "atencion"]:
    comprobar(esperar(lambda s=servicio: correlacion in compose("logs", "--no-color", s).stdout, 10),
              f"aparece en los logs de {servicio}")

print("7) RNF-DC2: Atención caída no impide registrar")
compose("stop", "atencion")
correlacion_2 = str(uuid.uuid4())
estado, _, reporte_2 = http("POST", "/api/v1/reportes", nuevo_reporte(), correlacion_2, token)
comprobar(estado == 201, "el reporte se registra con Atención detenida")
id_2 = reporte_2.get("idReporte")
time.sleep(2)
comprobar(caso_de(id_2) == "", "aún no hay caso (el evento espera en la cola)")
compose("start", "atencion")
comprobar(esperar(lambda: caso_de(id_2) == "RECIBIDO", 60), "al volver Atención el caso se abre")

print("8) Cola de mensajes fallidos vacía")
comprobar(mensajes_en_cola("atencion.eventos-reportes.dlq") == 0, "atencion.eventos-reportes.dlq vacía")

terminar()
