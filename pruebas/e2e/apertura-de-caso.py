#!/usr/bin/env python3
"""
Atención publica caso.actualizado al abrir el caso (corrección de fondo del hallazgo de R14).

Antes, Atención abría el caso sin emitir un evento. La vista de Indicadores solo conocía el
estado, la prioridad y la empresa cuando el caso cambiaba después: los mapas e indicadores
por empresa y las exportaciones a la SSPD dejaban fuera esos reportes, o no coincidían entre
sí. Comprueba:

  1. Recién abierto, sin ningún cambio, el caso ya está en la vista con estado RECIBIDO, su
     prioridad y su empresa.
  2. El ciudadano sigue viendo su reporte en RECIBIDO (Reportes ignora esa apertura).
  3. Hay exactamente un caso.actualizado de apertura en el outbox, y Auditoría lo registra.
  4. Una redelivery del reporte.creado no publica una segunda apertura.
  5. Tras reconstruir una vez los datos viejos, los reportes nuevos dejan la vista alineada con
     el origen (reconstruir-indicadores.py en modo verificar devuelve 0).

Uso (desde la raíz):  python3 pruebas/e2e/apertura-de-caso.py
"""
import base64
import json
import subprocess
import sys
import time
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone

from comun import (ENV, RABBIT_API, RAIZ, buscar_caso, comprobar, entrar_como_admin, esperar, http,
                   registrar_y_entrar, sql, terminar)


def reconstruir(*args):
    return subprocess.run([sys.executable, "infra/operacion/reconstruir-indicadores.py", *args], cwd=RAIZ,
                          capture_output=True, text=True)


def aperturas(id_reporte):
    return sql("SELECT count(*) FROM infraestructura.outbox WHERE tipo = 'caso.actualizado' "
               f"AND sobre->'datos'->>'idReporte' = '{id_reporte}' AND NOT sobre->'datos' ? 'estadoAnterior'")


print("0. Reparar una vez los datos anteriores a la corrección")
previa = reconstruir("--aplicar")
comprobar(previa.returncode == 0, "la vista queda alineada antes de empezar")

autor = registrar_y_entrar()[0]["tokenAcceso"]
admin = entrar_como_admin()["tokenAcceso"]
inicio = (datetime.now(timezone(timedelta(hours=-5))) - timedelta(minutes=40)).isoformat(timespec="seconds")
ids = []
for magnitud in ("VIVIENDA", "BARRIO"):
    estado, _, r = http("POST", "/api/v1/reportes", {"tipoServicio": "ENERGIA", "ubicacion": {
        "latitud": 4.6287, "longitud": -74.0648}, "inicioEstimado": inicio, "magnitud": magnitud},
        correlacion=str(uuid.uuid4()), token=autor)
    comprobar(estado == 201, f"reporte {magnitud} creado ({estado})")
    ids.append(r["idReporte"])
id_reporte = ids[0]

print("1. El caso recién abierto ya está completo en la vista")
comprobar(esperar(lambda: buscar_caso(admin, id_reporte) is not None, 30), "Atención abrió el caso")
caso = buscar_caso(admin, id_reporte)
comprobar(caso["estado"] == "RECIBIDO", f"el caso no cambió después de abrirse ({caso['estado']})")
esperado = f"RECIBIDO|{caso['prioridad']}|{caso.get('idEmpresa') or ''}"
fila = lambda: sql(f"SELECT estado_caso || '|' || prioridad || '|' || coalesce(id_empresa::text, '') "
                   f"FROM indicadores.vista_reportes WHERE id_reporte = '{id_reporte}'")
comprobar(esperar(lambda: fila() == esperado, 20), f"vista: estado, prioridad y empresa = {esperado} ({fila()})")
comprobar(bool(caso.get("idEmpresa")), "el caso quedó enrutado a una empresa (la que filtra al operador)")

print("2. El ciudadano no ve cambios por la apertura")
estado, _, propio = http("GET", f"/api/v1/reportes/{id_reporte}", token=autor)
comprobar(estado == 200 and propio["estado"] == "RECIBIDO", f"su reporte sigue en RECIBIDO ({propio.get('estado')})")

print("3. Una sola apertura en el outbox, y Auditoría la registra")
comprobar(aperturas(id_reporte) == "1", f"un caso.actualizado de apertura ({aperturas(id_reporte)})")
comprobar(esperar(lambda: sql("SELECT count(*) FROM auditoria.registros WHERE accion = 'CASO_ACTUALIZADO' "
                              f"AND recurso_id = '{caso['idCaso']}'") == "1", 20), "Auditoría registró la apertura")

print("4. Una redelivery del reporte.creado no publica otra apertura")
sobre = json.loads(sql("SELECT sobre FROM infraestructura.outbox WHERE tipo = 'reporte.creado' "
                       f"AND sobre->'datos'->>'idReporte' = '{id_reporte}'"))
auth = base64.b64encode(f"{ENV['RABBITMQ_USUARIO']}:{ENV['RABBITMQ_CLAVE']}".encode()).decode()
peticion = urllib.request.Request(f"{RABBIT_API}/exchanges/%2F/amq.default/publish", method="POST",
                                  headers={"Authorization": f"Basic {auth}", "content-type": "application/json"},
                                  data=json.dumps({"routing_key": "atencion.eventos-reportes", "payload": json.dumps(sobre),
                                                   "payload_encoding": "string", "properties": {
                                                       "delivery_mode": 2, "content_type": "application/json",
                                                       "message_id": sobre["idEvento"],
                                                       "correlation_id": sobre["correlationId"],
                                                       "type": sobre["tipo"]}}).encode())
with urllib.request.urlopen(peticion, timeout=10) as x:
    comprobar(json.loads(x.read()).get("routed"), "reporte.creado reenviado a la cola de Atención")
time.sleep(5)
comprobar(aperturas(id_reporte) == "1", f"sigue habiendo una sola apertura ({aperturas(id_reporte)})")
comprobar(buscar_caso(admin, id_reporte)["idCaso"] == caso["idCaso"], "y el mismo caso")

print("5. Los reportes nuevos no desalinean la vista")
comprobar(esperar(lambda: all(sql(f"SELECT estado_caso FROM indicadores.vista_reportes WHERE id_reporte = '{i}'")
                              == "RECIBIDO" for i in ids), 30), "todos los reportes nuevos tienen el estado del caso")
verificacion = reconstruir()
print("     " + verificacion.stdout.strip().replace("\n", "\n     "))
comprobar(verificacion.returncode == 0, "reconstruir-indicadores.py (verificar) no encuentra diferencias")

terminar()
