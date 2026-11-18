#!/usr/bin/env python3
"""
Genera infra/rabbitmq/definiciones.json: la topología de mensajería de SERVI-RED.

Uso:  python3 infra/rabbitmq/generar-definiciones.py

Topología por cada cola consumidora <q>:
  servired.eventos (topic) --patrones--> <q>            cola principal (quorum)
  <q> --rechazo definitivo--> servired.dlx (direct) --> <q>.dlq
  consumidor --falla transitoria--> <q>.reintento (TTL)
  <q>.reintento --TTL vencido--> servired.reintentos (direct) --> <q>

No se usa el exchange por defecto ("") como DLX: al importar definiciones
queda guardado como lista vacía y las colas quorum descartan el mensaje.

Las colas de mensajes fallidos (<q>.dlq) no tienen límite de entregas (R2). Las quorum
traen 20 por defecto, y una DLQ no tiene a dónde reenviar lo que lo excede: leer un
mensaje y devolverlo a la cola (la consola de RabbitMQ, «Get messages») suma una entrega,
y a la lectura 21 el mensaje se BORRA. Comprobado en RabbitMQ 4.1.
"""
import json
from pathlib import Path

VHOST = "/"
EXCHANGE_EVENTOS = "servired.eventos"
EXCHANGE_DLX = "servired.dlx"
EXCHANGE_REINTENTOS = "servired.reintentos"
TTL_REINTENTO_MS = 10_000
POLITICA_DLQ = "dlq-sin-limite-de-entregas"

# Sonda de antigüedad (ADR-21): Persistencia la publica cada 2 s y debe llegar a TODAS
# las colas consumidoras (auditoria.eventos ya la recibe con "#").
SONDA = "sonda.v1"

# Cola de cada consumidor -> patrones de routing key (<agregado>.<evento>.v<N>)
COLAS = {
    "atencion.eventos-reportes": [
        "reporte.creado.v1",
        "restablecimiento.registrado.v1",
        "validacion.registrada.v1",
        "denuncia.recibida.v1",
        SONDA,
    ],
    "reportes.eventos-casos": ["caso.actualizado.v1", "caso.cerrado.v1", SONDA],
    "indicadores.eventos": ["reporte.#", "restablecimiento.#", "validacion.#", "caso.#", SONDA],
    "auditoria.eventos": ["#"],
}


def exchange(nombre, tipo):
    return {"name": nombre, "vhost": VHOST, "type": tipo, "durable": True,
            "auto_delete": False, "internal": False, "arguments": {}}


def cola(nombre, **argumentos):
    return {"name": nombre, "vhost": VHOST, "durable": True, "auto_delete": False,
            "arguments": {"x-queue-type": "quorum", **argumentos}}


def binding(origen, destino, clave):
    return {"source": origen, "vhost": VHOST, "destination": destino,
            "destination_type": "queue", "routing_key": clave, "arguments": {}}


def main():
    d = {
        "exchanges": [exchange(EXCHANGE_EVENTOS, "topic"),
                      exchange(EXCHANGE_DLX, "direct"),
                      exchange(EXCHANGE_REINTENTOS, "direct")],
        "queues": [],
        "bindings": [],
        "policies": [{
            "vhost": VHOST, "name": POLITICA_DLQ, "pattern": r"\.dlq$", "apply-to": "quorum_queues",
            "priority": 0, "definition": {"delivery-limit": -1},
        }],
    }
    for q, patrones in COLAS.items():
        d["queues"] += [
            cola(q, **{"x-dead-letter-exchange": EXCHANGE_DLX, "x-dead-letter-routing-key": q}),
            cola(f"{q}.reintento", **{"x-message-ttl": TTL_REINTENTO_MS,
                                      "x-dead-letter-exchange": EXCHANGE_REINTENTOS,
                                      "x-dead-letter-routing-key": q}),
            cola(f"{q}.dlq"),
        ]
        d["bindings"] += [binding(EXCHANGE_EVENTOS, q, p) for p in patrones]
        d["bindings"].append(binding(EXCHANGE_DLX, f"{q}.dlq", q))
        d["bindings"].append(binding(EXCHANGE_REINTENTOS, q, q))

    destino = Path(__file__).with_name("definiciones.json")
    destino.write_text(json.dumps(d, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"{destino}: {len(d['queues'])} colas, {len(d['bindings'])} bindings")


if __name__ == "__main__":
    main()
