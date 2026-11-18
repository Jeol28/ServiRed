#!/usr/bin/env python3
"""
Scripts de operación contra el compose:

R2 (infra/operacion/dlq.py) — mensajes en colas de fallidos:
  1. Las DLQ tienen la política sin límite de entregas: leerlas 25 veces no borra nada.
  2. Un reporte.creado que terminó en la DLQ (Atención caída) aparece en «ver» con su
     tipo y correlationId; «reprocesar» lo devuelve a su cola y Atención abre el caso.
  3. Un mensaje ilegible vuelve a la DLQ; «descartar» exige --confirmar y deja copia.

R14 (infra/operacion/reconstruir-indicadores.py) — vista de Indicadores:
  4. Se reconstruye y queda alineada; se desalinea a propósito (fila borrada, valor
     alterado, fila huérfana); verificar lo detecta (código 2) sin tocar nada;
     reconstruir la deja alineada y con los valores del origen.

Detiene contenedores: úsela solo en un entorno de pruebas.
Uso (desde la raíz):  python3 pruebas/e2e/operacion-dlq-e-indicadores.py
"""
import base64
import json
import subprocess
import sys
import tempfile
import time
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

from comun import (ENV, RABBIT_API, RAIZ, buscar_caso, comprobar, compose, entrar_como_admin, esperar, http,
                   registrar_y_entrar, sql, terminar)

COLA = "atencion.eventos-reportes"
DLQ = COLA + ".dlq"


def rabbit(metodo, ruta, cuerpo=None):
    auth = base64.b64encode(f"{ENV['RABBITMQ_USUARIO']}:{ENV['RABBITMQ_CLAVE']}".encode()).decode()
    r = urllib.request.Request(RABBIT_API + ruta, method=metodo,
                               headers={"Authorization": f"Basic {auth}", "content-type": "application/json"},
                               data=json.dumps(cuerpo).encode() if cuerpo is not None else None)
    with urllib.request.urlopen(r, timeout=15) as x:
        c = x.read()
        return json.loads(c) if c else None


def script(nombre, *args):
    return subprocess.run([sys.executable, f"infra/operacion/{nombre}", *args], cwd=RAIZ, capture_output=True,
                          text=True)


def en_dlq():
    return len(rabbit("POST", f"/queues/%2F/{DLQ}/get", {"count": 100, "ackmode": "ack_requeue_true",
                                                         "encoding": "auto"}) or [])


def publicar_en_dlq(payload, propiedades):
    rabbit("POST", f"/exchanges/%2F/amq.default/publish",
           {"properties": propiedades, "routing_key": DLQ, "payload": payload, "payload_encoding": "string"})


print("1. Las DLQ no pierden mensajes al leerlas")
dlqs = [c for c in rabbit("GET", "/queues/%2F") if c["name"].endswith(".dlq")]
comprobar(dlqs and all((c.get("effective_policy_definition") or {}).get("delivery-limit") == -1 for c in dlqs),
          f"las {len(dlqs)} DLQ tienen la política sin límite de entregas")
rabbit("DELETE", f"/queues/%2F/{DLQ}/contents")
marca = str(uuid.uuid4())
publicar_en_dlq(json.dumps({"marca": marca}), {"delivery_mode": 2})
for _ in range(25):
    rabbit("POST", f"/queues/%2F/{DLQ}/get", {"count": 1, "ackmode": "ack_requeue_true", "encoding": "auto"})
comprobar(en_dlq() == 1, "tras 25 lecturas el mensaje sigue en la DLQ (sin la política se borraba a la 21)")
rabbit("DELETE", f"/queues/%2F/{DLQ}/contents")

print("2. Un reporte.creado perdido en la DLQ se reprocesa")
autor = registrar_y_entrar()[0]["tokenAcceso"]
compose("stop", "atencion")
inicio = (datetime.now(timezone(timedelta(hours=-5))) - timedelta(hours=1)).isoformat(timespec="seconds")
correlacion = str(uuid.uuid4())
estado, _, r = http("POST", "/api/v1/reportes", {"tipoServicio": "ENERGIA", "ubicacion": {"latitud": 4.6287,
                    "longitud": -74.0648}, "inicioEstimado": inicio, "magnitud": "CUADRA"},
                    correlacion=correlacion, token=autor)
comprobar(estado == 201, f"reporte creado con Atención detenida ({estado})")
# Simula que su evento falló: se saca de la cola de Atención y se deja en la DLQ.
comprobar(esperar(lambda: any(m["properties"].get("correlation_id") == correlacion for m in rabbit(
    "POST", f"/queues/%2F/{COLA}/get", {"count": 200, "ackmode": "ack_requeue_true", "encoding": "auto"}) or []), 30),
          "su reporte.creado llegó a la cola de Atención")
for m in rabbit("POST", f"/queues/%2F/{COLA}/get", {"count": 500, "ackmode": "ack_requeue_false", "encoding": "auto"}) or []:
    if m["properties"].get("correlation_id") == correlacion:
        publicar_en_dlq(m["payload"], m["properties"])
publicar_en_dlq("esto no es un evento", {"delivery_mode": 2})
compose("start", "atencion")
esperar(lambda: subprocess.run(["docker", "inspect", "-f", "{{.State.Health.Status}}", "servired-atencion-1"],
                               capture_output=True, text=True).stdout.strip() == "healthy", 90)
admin = entrar_como_admin()["tokenAcceso"]
time.sleep(5)
comprobar(buscar_caso(admin, r["idReporte"]) is None, "sin su evento, el reporte no tiene caso")

listado = script("dlq.py", "listar")
comprobar(DLQ in listado.stdout, "listar muestra la DLQ de Atención")
visto = script("dlq.py", "ver", COLA)
comprobar("reporte.creado" in visto.stdout and correlacion in visto.stdout and "ilegible" in visto.stdout,
          "ver muestra el reporte.creado con su correlationId y el mensaje ilegible")
comprobar(en_dlq() == 2, "ver no consumió nada")

rep = script("dlq.py", "reprocesar", COLA)
print("     " + rep.stdout.strip().replace("\n", "\n     "))
comprobar(rep.returncode == 0, "reprocesar termina bien")
comprobar(esperar(lambda: buscar_caso(admin, r["idReporte"]) is not None, 60),
          "Atención procesó el evento devuelto y abrió el caso")

print("3. El ilegible vuelve a la DLQ y se descarta con copia")
comprobar(esperar(lambda: en_dlq() == 1, 30), "el mensaje ilegible volvió a la DLQ")
sin_confirmar = script("dlq.py", "descartar", COLA)
comprobar(sin_confirmar.returncode == 1 and en_dlq() == 1, "descartar sin --confirmar no borra nada")
with tempfile.TemporaryDirectory() as carpeta:
    copia = Path(carpeta) / "copia.json"
    desc = script("dlq.py", "descartar", COLA, "--confirmar", "--archivo", str(copia))
    comprobar(desc.returncode == 0 and esperar(lambda: en_dlq() == 0, 10), "descartar --confirmar vacía la DLQ")
    guardado = json.loads(copia.read_text(encoding="utf-8")) if copia.exists() else []
    comprobar(len(guardado) == 1 and guardado[0]["payload"] == "esto no es un evento", "y deja copia en el archivo")

print("4. Reconstrucción de la vista de Indicadores (R14)")
base = script("reconstruir-indicadores.py")
print("     verificación inicial:\n     " + base.stdout.strip().replace("\n", "\n     "))
aplicada = script("reconstruir-indicadores.py", "--aplicar")
comprobar(aplicada.returncode == 0 and "alineada" in aplicada.stdout, "reconstruir deja la vista alineada")
comprobar(script("reconstruir-indicadores.py").returncode == 0, "y verificar lo confirma (código 0)")

otro = sql("SELECT id_reporte FROM indicadores.vista_reportes WHERE id_reporte <> "
           f"'{r['idReporte']}' ORDER BY fecha_registro DESC LIMIT 1")
esperado = sql(f"SELECT duracion_minutos IS NULL, magnitud FROM indicadores.vista_reportes WHERE id_reporte = '{otro}'")
sql(f"DELETE FROM indicadores.vista_reportes WHERE id_reporte = '{r['idReporte']}'")
sql(f"UPDATE indicadores.vista_reportes SET magnitud = 'VIVIENDA', confirmaciones_netas = 99 WHERE id_reporte = '{otro}'")
sql(f"INSERT INTO indicadores.vista_reportes (id_reporte, tipo_servicio) VALUES ('{uuid.uuid4()}', 'GAS')")
desalineada = script("reconstruir-indicadores.py")
print("     tras desalinearla:\n     " + desalineada.stdout.strip().replace("\n", "\n     "))
comprobar(desalineada.returncode == 2, "verificar detecta las diferencias (código 2)")
comprobar("Faltan en la vista: 1" in desalineada.stdout and "sobran (sin reporte): 1" in desalineada.stdout
          and "confirmaciones_netas: 1" in desalineada.stdout, "una faltante, una sobrante y los valores alterados")
comprobar(sql(f"SELECT count(*) FROM indicadores.vista_reportes WHERE id_reporte = '{r['idReporte']}'") == "0",
          "verificar no tocó la vista")
reconstruida = script("reconstruir-indicadores.py", "--aplicar")
comprobar(reconstruida.returncode == 0 and "alineada" in reconstruida.stdout, "reconstruir la alinea de nuevo")
comprobar(sql(f"SELECT tipo_servicio || '|' || codigo_municipio FROM indicadores.vista_reportes "
              f"WHERE id_reporte = '{r['idReporte']}'") == "ENERGIA|11001", "la fila borrada volvió desde el origen")
comprobar(sql(f"SELECT duracion_minutos IS NULL, magnitud FROM indicadores.vista_reportes WHERE id_reporte = '{otro}'")
          == esperado, "el valor alterado volvió al del origen")

terminar()
