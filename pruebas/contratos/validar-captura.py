#!/usr/bin/env python3
"""
Valida contra el contrato una lista de mensajes capturados de RabbitMQ:
[{"routing_key": "...", "sobre": {...}}, ...]. Imprime en la última línea un JSON con
{"validos": {tipo: n}, "invalidos": [{routing_key, origen, errores}]}.
Lo usa pruebas/e2e/contrato-eventos.py.
"""
import json
import sys
from collections import Counter

from contrato_eventos import errores_de

mensajes = json.load(open(sys.argv[1], encoding="utf-8"))
validos, invalidos = Counter(), []
for m in mensajes:
    errores = errores_de(m["sobre"], m["routing_key"])
    if errores:
        invalidos.append({"routing_key": m["routing_key"], "origen": m["sobre"].get("origen"), "errores": errores})
    else:
        validos[m["sobre"]["tipo"]] += 1
print(json.dumps({"validos": validos, "invalidos": invalidos}, ensure_ascii=False))
sys.exit(1 if invalidos else 0)
