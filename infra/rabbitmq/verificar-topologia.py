#!/usr/bin/env python3
"""
Prueba de humo de la topología de RabbitMQ contra el entorno levantado con
Docker Compose. Verifica enrutamiento topic, cola de mensajes fallidos y
reintento con TTL. Consume los mensajes para medir (los contadores de las
colas quorum se actualizan con retraso).

Uso (desde la raíz, con el compose arriba):
    python3 infra/rabbitmq/verificar-topologia.py
"""
import base64
import json
import os
import sys
import time
import urllib.request
import uuid
from pathlib import Path

API = "http://127.0.0.1:15672/api"


def leer_env():
    valores = {}
    env = Path(__file__).resolve().parents[2] / ".env"
    for linea in env.read_text(encoding="utf-8").splitlines():
        if "=" in linea and not linea.lstrip().startswith("#"):
            clave, valor = linea.split("=", 1)
            valores[clave.strip()] = valor.strip()
    return {**valores, **os.environ}


ENV = leer_env()
AUTH = base64.b64encode(f"{ENV['RABBITMQ_USUARIO']}:{ENV['RABBITMQ_CLAVE']}".encode()).decode()


def llamar(metodo, ruta, cuerpo=None):
    peticion = urllib.request.Request(
        f"{API}{ruta}", method=metodo,
        data=json.dumps(cuerpo).encode() if cuerpo is not None else None,
        headers={"Authorization": f"Basic {AUTH}", "content-type": "application/json"})
    with urllib.request.urlopen(peticion) as r:
        contenido = r.read()
        return json.loads(contenido) if contenido else None


def publicar(exchange, clave, marca):
    llamar("POST", f"/exchanges/%2F/{exchange}/publish", {
        "properties": {"delivery_mode": 2}, "routing_key": clave,
        "payload": json.dumps({"marca": marca}), "payload_encoding": "string"})


def tomar(cola, ackmode="ack_requeue_false", espera_s=3.0):
    """Devuelve las marcas de los mensajes de la cola (esperando hasta espera_s)."""
    limite = time.time() + espera_s
    while True:
        msgs = llamar("POST", f"/queues/%2F/{cola}/get",
                      {"count": 50, "ackmode": ackmode, "encoding": "auto"})
        if msgs or time.time() > limite:
            return [json.loads(m["payload"])["marca"] for m in msgs]
        time.sleep(0.3)


def vaciar(*colas):
    for c in colas:
        llamar("DELETE", f"/queues/%2F/{c}/contents")


fallos = []


def comprobar(condicion, descripcion):
    print(("  OK   " if condicion else "  FALLA ") + descripcion)
    if not condicion:
        fallos.append(descripcion)


COLAS = ["atencion.eventos-reportes", "indicadores.eventos", "auditoria.eventos",
         "reportes.eventos-casos", "auditoria.eventos.reintento", "auditoria.eventos.dlq"]
vaciar(*COLAS)

print("1) Enrutamiento topic de reporte.creado.v1")
m = str(uuid.uuid4())
publicar("servired.eventos", "reporte.creado.v1", m)
for c in ["atencion.eventos-reportes", "indicadores.eventos"]:
    comprobar(tomar(c) == [m], f"llega a {c}")
comprobar(tomar("auditoria.eventos", ackmode="ack_requeue_true") == [m], "llega a auditoria.eventos")
comprobar(tomar("reportes.eventos-casos", espera_s=1) == [], "NO llega a reportes.eventos-casos")

print("2) Rechazo definitivo -> cola de mensajes fallidos")
llamar("POST", "/queues/%2F/auditoria.eventos/get",
       {"count": 1, "ackmode": "reject_requeue_false", "encoding": "auto"})
comprobar(tomar("auditoria.eventos.dlq") == [m], "el rechazado queda en auditoria.eventos.dlq")

print("3) Reintento con TTL -> vuelve a la cola principal")
m2 = str(uuid.uuid4())
publicar("amq.default", "auditoria.eventos.reintento", m2)
comprobar(tomar("auditoria.eventos", espera_s=1) == [], "no está en la principal antes del TTL")
comprobar(tomar("auditoria.eventos", espera_s=15) == [m2], "vuelve a auditoria.eventos tras el TTL")

vaciar(*COLAS)
print("Resultado:", "TODO OK" if not fallos else f"{len(fallos)} fallo(s)")
sys.exit(1 if fallos else 0)
