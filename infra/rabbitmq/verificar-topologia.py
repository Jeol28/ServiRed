#!/usr/bin/env python3
"""
Prueba de humo de la topología de RabbitMQ contra el entorno de Docker Compose.

1. Las colas, sus argumentos y los bindings del broker coinciden con
   definiciones.json (sin tocar las colas reales, que ya tienen consumidores).
2. La mecánica de enrutamiento topic, DLQ y reintento con TTL funciona, probada
   sobre un juego de colas temporales con los mismos argumentos y los mismos
   exchanges de rechazo y reintento (servired.dlx, servired.reintentos). La entrada es
   un exchange topic temporal, no servired.eventos, para no dejar mensajes de prueba en
   las colas reales. Al final se eliminan.

Uso (desde la raíz, con el compose arriba):
    python3 infra/rabbitmq/verificar-topologia.py
"""
import base64
import json
import os
import sys
import time
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

API = "http://127.0.0.1:15672/api"
AQUI = Path(__file__).resolve().parent


def leer_env():
    valores = {}
    for linea in (AQUI.parents[1] / ".env").read_text(encoding="utf-8").splitlines():
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


def q(nombre):
    return urllib.parse.quote(nombre, safe="")


fallos = []


def comprobar(condicion, descripcion):
    print(("  OK    " if condicion else "  FALLA ") + descripcion)
    if not condicion:
        fallos.append(descripcion)


# ---------------------------------------------------------------- 1. Definiciones
print("1) El broker tiene la topología de definiciones.json")
esperado = json.loads((AQUI / "definiciones.json").read_text(encoding="utf-8"))
colas_vivas = {c["name"]: c for c in llamar("GET", "/queues/%2F")}
for cola in esperado["queues"]:
    viva = colas_vivas.get(cola["name"])
    comprobar(viva is not None and viva["arguments"] == cola["arguments"],
              f"cola {cola['name']} con sus argumentos")
bindings_vivos = {(b["source"], b["destination"], b["routing_key"]) for b in llamar("GET", "/bindings/%2F")}
faltantes = [b for b in esperado["bindings"]
             if (b["source"], b["destination"], b["routing_key"]) not in bindings_vivos]
comprobar(not faltantes, f"{len(esperado['bindings'])} bindings presentes"
          + (f" (faltan {faltantes})" if faltantes else ""))
politicas_vivas = {p["name"]: p for p in llamar("GET", "/policies/%2F")}
for politica in esperado.get("policies", []):
    viva = politicas_vivas.get(politica["name"])
    comprobar(viva is not None and viva["definition"] == politica["definition"] and viva["pattern"] == politica["pattern"],
              f"política {politica['name']} ({politica['definition']})")

# ---------------------------------------------------------------- 2. Mecánica
base = f"humo.{uuid.uuid4().hex[:8]}"
clave = f"{base}.prueba.v1"
cola_principal, cola_reintento, cola_dlq = base, f"{base}.reintento", f"{base}.dlq"
exchange_humo = f"{base}.eventos"


def declarar(nombre, **argumentos):
    llamar("PUT", f"/queues/%2F/{q(nombre)}", {"durable": True, "auto_delete": False,
                                               "arguments": {"x-queue-type": "quorum", **argumentos}})


def enlazar(exchange, cola, rk):
    llamar("POST", f"/bindings/%2F/e/{exchange}/q/{q(cola)}", {"routing_key": rk})


def publicar(exchange, rk, marca):
    llamar("POST", f"/exchanges/%2F/{exchange}/publish", {
        "properties": {"delivery_mode": 2}, "routing_key": rk,
        "payload": json.dumps({"marca": marca}), "payload_encoding": "string"})


def tomar(cola, ackmode="ack_requeue_false", espera_s=3.0):
    limite = time.time() + espera_s
    while True:
        msgs = llamar("POST", f"/queues/%2F/{q(cola)}/get", {"count": 50, "ackmode": ackmode, "encoding": "auto"})
        if msgs or time.time() > limite:
            return [json.loads(m["payload"])["marca"] for m in msgs]
        time.sleep(0.3)


try:
    declarar(cola_principal, **{"x-dead-letter-exchange": "servired.dlx", "x-dead-letter-routing-key": base})
    declarar(cola_reintento, **{"x-message-ttl": 1500, "x-dead-letter-exchange": "servired.reintentos",
                                "x-dead-letter-routing-key": base})
    declarar(cola_dlq)
    llamar("PUT", f"/exchanges/%2F/{q(exchange_humo)}", {"type": "topic", "durable": False, "auto_delete": False})
    enlazar(exchange_humo, cola_principal, clave)
    enlazar("servired.dlx", cola_dlq, base)
    enlazar("servired.reintentos", cola_principal, base)

    print("2) Enrutamiento topic")
    m = str(uuid.uuid4())
    publicar(exchange_humo, clave, m)
    comprobar(tomar(cola_principal, ackmode="ack_requeue_true") == [m], "el evento llega a su cola")

    print("3) Rechazo definitivo -> cola de mensajes fallidos")
    llamar("POST", f"/queues/%2F/{q(cola_principal)}/get",
           {"count": 1, "ackmode": "reject_requeue_false", "encoding": "auto"})
    comprobar(tomar(cola_dlq) == [m], "el rechazado queda en la DLQ")

    print("4) Reintento con TTL -> vuelve a la cola principal")
    m2 = str(uuid.uuid4())
    publicar("amq.default", cola_reintento, m2)
    comprobar(tomar(cola_principal, espera_s=0.5) == [], "no está en la principal antes del TTL")
    comprobar(tomar(cola_principal, espera_s=10) == [m2], "vuelve a la principal tras el TTL")
finally:
    try:
        llamar("DELETE", f"/exchanges/%2F/{q(exchange_humo)}")
    except Exception:  # noqa: BLE001 - limpieza de mejor esfuerzo
        pass
    for cola in (cola_principal, cola_reintento, cola_dlq):
        try:
            llamar("DELETE", f"/queues/%2F/{q(cola)}")
        except Exception:  # noqa: BLE001 - limpieza de mejor esfuerzo
            pass

print("Resultado:", "TODO OK" if not fallos else f"{len(fallos)} fallo(s)")
sys.exit(1 if fallos else 0)
