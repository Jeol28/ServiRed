#!/usr/bin/env python3
"""
Colas de mensajes fallidos (DLQ) de SERVI-RED (R2).

Un evento llega a <cola>.dlq cuando su consumidor no pudo procesarlo: falla permanente
(datos inválidos) o falla transitoria que agotó sus reintentos. Este script permite
revisarlos, devolverlos a su cola una vez corregida la causa, o descartarlos dejando
copia.

  python3 infra/operacion/dlq.py listar
  python3 infra/operacion/dlq.py ver atencion.eventos-reportes [--cantidad 20]
  python3 infra/operacion/dlq.py reprocesar atencion.eventos-reportes
  python3 infra/operacion/dlq.py descartar atencion.eventos-reportes --confirmar [--archivo copia.json]

<cola> es la cola del consumidor (sin .dlq). Usa la API de administración de RabbitMQ
(127.0.0.1:15672) con las credenciales de .env (o de SERVIRED_ENV=<archivo>).

- ver no consume: lee y devuelve cada mensaje a la cola. Es seguro porque las DLQ no
  tienen límite de entregas (política dlq-sin-limite-de-entregas); sin ella, en una cola
  quorum el mensaje se borraría a la lectura 21.
- reprocesar usa un shovel con ack-mode on-confirm: cada mensaje se confirma en la DLQ
  solo después de que la cola principal lo recibió, así que no se pierde ninguno. Mueve
  los que había al empezar y termina solo. Los consumidores son idempotentes por
  idEvento: reprocesar un evento ya aplicado no lo duplica.
- descartar guarda primero todos los mensajes en un archivo JSON y después vacía la cola.
"""
import argparse
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

RAIZ = Path(__file__).resolve().parents[2]
API = os.environ.get("RABBITMQ_API", "http://127.0.0.1:15672/api")


def leer_env():
    valores = {}
    archivo = RAIZ / os.environ.get("SERVIRED_ENV", ".env")
    for linea in archivo.read_text(encoding="utf-8").splitlines():
        if "=" in linea and not linea.lstrip().startswith("#"):
            clave, valor = linea.split("=", 1)
            valor = valor.strip()
            if len(valor) >= 2 and valor[0] == valor[-1] and valor[0] in "'\"":
                valor = valor[1:-1]
            valores[clave.strip()] = valor
    return valores


ENV = leer_env()
AUTH = "Basic " + base64.b64encode(f"{ENV['RABBITMQ_USUARIO']}:{ENV['RABBITMQ_CLAVE']}".encode()).decode()


def api(metodo, ruta, cuerpo=None):
    peticion = urllib.request.Request(API + ruta, method=metodo,
                                      headers={"Authorization": AUTH, "content-type": "application/json"},
                                      data=json.dumps(cuerpo).encode() if cuerpo is not None else None)
    try:
        with urllib.request.urlopen(peticion, timeout=30) as r:
            contenido = r.read()
            return json.loads(contenido) if contenido else None
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise SystemExit(f"RabbitMQ respondió {e.code} a {metodo} {ruta}: {e.read()[:300]!r}")


def q(nombre):
    return urllib.parse.quote(nombre, safe="")


def dlq_de(cola):
    return cola if cola.endswith(".dlq") else f"{cola}.dlq"


def principal_de(cola):
    return cola[:-4] if cola.endswith(".dlq") else cola


def mensajes(cola):
    """Cantidad según la API de administración (se actualiza cada pocos segundos)."""
    datos = api("GET", f"/queues/%2F/{q(cola)}")
    if datos is None:
        raise SystemExit(f"No existe la cola {cola}")
    return datos.get("messages", 0)


def exigir_politica(cola):
    politica = (api("GET", f"/queues/%2F/{q(cola)}") or {}).get("effective_policy_definition") or {}
    if politica.get("delivery-limit") != -1:
        raise SystemExit(f"{cola} no tiene la política sin límite de entregas: leerla podría BORRAR mensajes. "
                         "Aplique la topología (docker compose up rabbitmq-init) antes de continuar.")


def leer_todos(cola, cantidad):
    """Lee sin consumir: cada mensaje vuelve a la cola (seguro con la política de las DLQ)."""
    exigir_politica(cola)
    return api("POST", f"/queues/%2F/{q(cola)}/get",
               {"count": cantidad, "ackmode": "ack_requeue_true", "encoding": "auto"}) or []


def resumen(m):
    try:
        sobre = json.loads(m["payload"])
        tipo = sobre.get("tipo", "(JSON sin «tipo»: no es un sobre de evento)") if isinstance(sobre, dict) \
            else "(JSON sin «tipo»: no es un sobre de evento)"
    except (json.JSONDecodeError, TypeError):
        sobre, tipo = {}, "(no es JSON: mensaje ilegible)"
    if not isinstance(sobre, dict):
        sobre = {}
    cabeceras = m["properties"].get("headers") or {}
    muerte = (cabeceras.get("x-death") or [{}])[0]
    cuando = muerte.get("time")
    if isinstance(cuando, (int, float)):
        cuando = datetime.fromtimestamp(cuando, timezone.utc).isoformat(timespec="seconds")
    return {
        "routingKey": m.get("routing_key"),
        "tipo": tipo,
        "contenido": str(m.get("payload"))[:80],
        "idEvento": sobre.get("idEvento"),
        "correlationId": sobre.get("correlationId") or m["properties"].get("correlation_id"),
        "intentos": cabeceras.get("x-intentos"),
        "rechazo": {"motivo": muerte.get("reason"), "cola": muerte.get("queue"), "veces": muerte.get("count"),
                    "fecha": cuando},
    }


def listar(_args):
    colas = [c for c in api("GET", "/queues/%2F") if c["name"].endswith(".dlq")]
    if not colas:
        print("No hay colas de mensajes fallidos.")
        return 0
    total = 0
    print(f"{'Cola de fallidos':45} {'Mensajes':>8}")
    for c in sorted(colas, key=lambda c: c["name"]):
        n = mensajes(c["name"])
        total += n
        print(f"{c['name']:45} {n:>8}")
    print(f"\n{total} mensaje(s) en total." + (" Revíselos con: dlq.py ver <cola>" if total else ""))
    return 0


def ver(args):
    cola = dlq_de(args.cola)
    lista = leer_todos(cola, args.cantidad)
    if not lista:
        print(f"{cola} está vacía.")
        return 0
    print(f"{len(lista)} mensaje(s) en {cola} (de {mensajes(cola)}). Busque la causa en los logs por correlationId:\n")
    for i, m in enumerate(lista, 1):
        print(f"{i}. {json.dumps(resumen(m), ensure_ascii=False)}")
    return 0


def reprocesar(args):
    dlq, principal = dlq_de(args.cola), principal_de(args.cola)
    if api("GET", f"/queues/%2F/{q(principal)}") is None:
        raise SystemExit(f"No existe la cola principal {principal}")
    antes = mensajes(dlq)
    if antes == 0:
        print(f"{dlq} está vacía: nada que reprocesar.")
        return 0
    nombre = f"reprocesar-{principal}-{int(time.time())}"
    api("PUT", f"/parameters/shovel/%2F/{q(nombre)}", {"value": {
        "src-protocol": "amqp091", "src-uri": "amqp://", "src-queue": dlq,
        "dest-protocol": "amqp091", "dest-uri": "amqp://", "dest-queue": principal,
        # Confirma en la DLQ solo cuando la cola principal lo recibió: no se pierde ninguno.
        "ack-mode": "on-confirm",
        # Mueve los que hay ahora y se borra solo.
        "src-delete-after": "queue-length",
    }})
    limite = time.time() + 120
    while time.time() < limite:
        time.sleep(2)
        if api("GET", f"/parameters/shovel/%2F/{q(nombre)}") is None:
            break
    else:
        api("DELETE", f"/parameters/shovel/%2F/{q(nombre)}")
        print(f"El shovel no terminó en 2 minutos; se detuvo. Quedan {mensajes(dlq)} en {dlq}.")
        return 1
    time.sleep(6)   # la API de administración actualiza los conteos cada pocos segundos
    quedan = mensajes(dlq)
    print(f"Se devolvieron a {principal} los {antes} mensaje(s) de {dlq}. Ahora quedan en la DLQ: {quedan}.")
    if quedan:
        print("Los que quedan volvieron a fallar en el consumidor: revíselos con «ver» (la causa no se corrigió).")
    return 0


def descartar(args):
    dlq = dlq_de(args.cola)
    n = mensajes(dlq)
    if n == 0:
        print(f"{dlq} está vacía.")
        return 0
    if not args.confirmar:
        print(f"{dlq} tiene {n} mensaje(s). Descartarlos borra eventos que no se procesaron: "
              "repita con --confirmar (antes se guardan en un archivo).")
        return 1
    copia = Path(args.archivo or f"dlq-{principal_de(args.cola)}-{datetime.now():%Y%m%d-%H%M%S}.json")
    lista = leer_todos(dlq, max(n, 1) + 100)
    copia.write_text(json.dumps([{"routingKey": m.get("routing_key"), "propiedades": m["properties"],
                                  "payload": m["payload"], "codificacion": m.get("payload_encoding")}
                                 for m in lista], ensure_ascii=False, indent=2), encoding="utf-8")
    if len(lista) < n:
        print(f"Solo se pudieron copiar {len(lista)} de {n}: no se descarta nada. Copia parcial en {copia}.")
        return 1
    api("DELETE", f"/queues/%2F/{q(dlq)}/contents")
    print(f"{len(lista)} mensaje(s) copiados en {copia} y descartados de {dlq}.")
    return 0


def main():
    p = argparse.ArgumentParser(description="Colas de mensajes fallidos de SERVI-RED (R2)")
    sub = p.add_subparsers(dest="comando", required=True)
    sub.add_parser("listar", help="mensajes en cada cola de fallidos").set_defaults(f=listar)
    v = sub.add_parser("ver", help="revisar los mensajes sin consumirlos")
    v.add_argument("cola")
    v.add_argument("--cantidad", type=int, default=20)
    v.set_defaults(f=ver)
    r = sub.add_parser("reprocesar", help="devolverlos a su cola una vez corregida la causa")
    r.add_argument("cola")
    r.set_defaults(f=reprocesar)
    d = sub.add_parser("descartar", help="guardarlos en un archivo y vaciar la cola")
    d.add_argument("cola")
    d.add_argument("--confirmar", action="store_true")
    d.add_argument("--archivo")
    d.set_defaults(f=descartar)
    args = p.parse_args()
    sys.exit(args.f(args))


if __name__ == "__main__":
    main()
