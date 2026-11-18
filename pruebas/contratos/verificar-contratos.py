#!/usr/bin/env python3
"""
Verifica el contrato de eventos (contratos/eventos):
  1. El AsyncAPI y los esquemas son consistentes: cada mensaje apunta a esquemas que
     existen, cada canal usa la routing key <tipo>.v<version>, cada tipo del sobre tiene
     su mensaje y ningún esquema queda huérfano.
  2. Los esquemas son JSON Schema 2020-12 válidos.
  3. Cada evento tiene un ejemplo completo y uno mínimo, y ambos cumplen el contrato.
  4. Un evento que rompe el contrato se rechaza (el validador no deja pasar todo).

Uso (desde la raíz):  sh pruebas/contratos/verificar-contratos.sh
"""
import copy
import json
import sys

from jsonschema import Draft202012Validator

from contrato_eventos import ASYNCAPI, EVENTOS, ESQUEMAS_POR_ARCHIVO, errores_de

fallos = []


def comprobar(condicion, descripcion):
    print(("  OK    " if condicion else "  FALLA ") + descripcion)
    if not condicion:
        fallos.append(descripcion)


print("1. AsyncAPI y esquemas")
tipos_sobre = set(ESQUEMAS_POR_ARCHIVO["sobre-evento.v1.json"]["properties"]["tipo"]["enum"])
for (tipo, version), m in sorted(ASYNCAPI.items()):
    comprobar(m["sobre"] == "sobre-evento.v1.json" and m["datos"] in ESQUEMAS_POR_ARCHIVO,
              f"{tipo} v{version}: sobre y datos ({m['datos']}) existen")
    comprobar(m["canal"] == f"{tipo}.v{version}", f"{tipo} v{version}: canal {m['canal']!r}")
comprobar(tipos_sobre == {t for t, _ in ASYNCAPI},
          f"cada tipo del sobre tiene su mensaje en el AsyncAPI ({sorted(tipos_sobre ^ {t for t, _ in ASYNCAPI})})")
usados = {"sobre-evento.v1.json"} | {m["datos"] for m in ASYNCAPI.values()}
comprobar(usados == set(ESQUEMAS_POR_ARCHIVO), f"ningún esquema huérfano ({sorted(set(ESQUEMAS_POR_ARCHIVO) - usados)})")

print("2. Esquemas válidos (JSON Schema 2020-12)")
for archivo, esquema in sorted(ESQUEMAS_POR_ARCHIVO.items()):
    try:
        Draft202012Validator.check_schema(esquema)
        comprobar(esquema.get("$schema", "").endswith("2020-12/schema"), f"{archivo}")
    except Exception as e:  # noqa: BLE001
        comprobar(False, f"{archivo}: {e}")

print("3. Ejemplos del contrato")
for (tipo, version) in sorted(ASYNCAPI):
    for variante in ("completo", "minimo"):
        ruta = EVENTOS / "ejemplos" / f"{tipo}.v{version}.{variante}.json"
        if not ruta.exists():
            comprobar(False, f"falta el ejemplo {ruta.name}")
            continue
        errores = errores_de(json.loads(ruta.read_text(encoding="utf-8")), f"{tipo}.v{version}")
        comprobar(not errores, f"{ruta.name} {errores or ''}")

print("4. El validador rechaza lo que rompe el contrato")
base = json.loads((EVENTOS / "ejemplos" / "reporte.creado.v1.completo.json").read_text(encoding="utf-8"))
casos = {
    "idEvento que no es UUID": lambda m: m.update(idEvento="evento-1"),
    "fecha sin zona horaria": lambda m: m["datos"].update(fechaRegistro="2026-11-18T15:04:05"),
    "campo desconocido en datos": lambda m: m["datos"].update(municipio="Bogotá"),
    "falta un campo obligatorio": lambda m: m["datos"].pop("magnitud"),
    "enum en minúsculas": lambda m: m["datos"].update(tipoServicio="acueducto"),
    "latitud fuera de Colombia": lambda m: m["datos"]["ubicacion"].update(latitud=40.4),
    "versión sin contrato": lambda m: m.update(version=2),
}
for descripcion, romper in casos.items():
    m = copy.deepcopy(base)
    romper(m)
    comprobar(bool(errores_de(m, f"{m['tipo']}.v{m['version']}")), f"rechaza: {descripcion}")
comprobar(bool(errores_de(base, "reporte.creado.v2")), "rechaza: routing key que no corresponde")

print("Resultado:", "TODO OK" if not fallos else f"{len(fallos)} fallo(s)")
sys.exit(1 if fallos else 0)
