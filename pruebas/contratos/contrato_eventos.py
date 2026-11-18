"""
Validación de eventos contra su contrato (contratos/eventos): el AsyncAPI y un JSON Schema
2020-12 por evento. Un mensaje es válido si:
  - cumple el sobre (sobre-evento.v1.json),
  - su routing key es <tipo>.v<version> y el AsyncAPI tiene ese canal y ese mensaje,
  - sus datos cumplen <tipo>.v<version>.json,
con los formatos (uuid, date-time) validados, no solo anotados.

La usan verificar-contratos.py (el contrato y sus ejemplos) y la prueba e2e
pruebas/e2e/contrato-eventos.py (los mensajes reales que circulan por RabbitMQ).
"""
import json
from pathlib import Path

import yaml
from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource

RAIZ = Path(__file__).resolve().parents[2]
EVENTOS = RAIZ / "contratos" / "eventos"
ESQUEMAS = EVENTOS / "esquemas"
FORMATOS = FormatChecker()


def _cargar_esquemas():
    esquemas = {p.name: json.loads(p.read_text(encoding="utf-8")) for p in ESQUEMAS.glob("*.json")}
    registro = Registry().with_resources(
        (e["$id"], Resource.from_contents(e)) for e in esquemas.values())
    return esquemas, registro


ESQUEMAS_POR_ARCHIVO, REGISTRO = _cargar_esquemas()


def validador(archivo):
    return Draft202012Validator(ESQUEMAS_POR_ARCHIVO[archivo], registry=REGISTRO, format_checker=FORMATOS)


def mensajes_asyncapi():
    """{(tipo, version): {canal, archivo de datos}} según el AsyncAPI."""
    doc = yaml.safe_load((EVENTOS / "asyncapi.yaml").read_text(encoding="utf-8"))
    mensajes = {}
    for clave, m in doc["components"]["messages"].items():
        partes = m["payload"]["allOf"]
        sobre, especifico = partes[0]["$ref"], partes[1]["properties"]
        tipo, version = especifico["tipo"]["const"], especifico["version"]["const"]
        mensajes[(tipo, version)] = {"clave": clave, "sobre": Path(sobre).name,
                                     "datos": Path(especifico["datos"]["$ref"]).name}
    canales = {}
    for c in doc["channels"].values():
        for ref in c["messages"].values():
            canales[ref["$ref"].rsplit("/", 1)[1]] = c["address"]
    for (tipo, version), m in mensajes.items():
        m["canal"] = canales.get(m["clave"])
    return mensajes


ASYNCAPI = mensajes_asyncapi()


def errores_de(mensaje, routing_key=None):
    """Lista de errores de un sobre (dict); vacía si cumple el contrato."""
    errores = [f"sobre: {e.json_path}: {e.message}" for e in validador("sobre-evento.v1.json").iter_errors(mensaje)]
    if errores:
        return errores
    clave = (mensaje["tipo"], mensaje["version"])
    if clave not in ASYNCAPI:
        return [f"el AsyncAPI no define {clave[0]} v{clave[1]}"]
    esperado = f"{clave[0]}.v{clave[1]}"
    if routing_key is not None and routing_key != esperado:
        errores.append(f"routing key {routing_key!r}; el contrato dice {esperado!r}")
    errores += [f"datos: {e.json_path}: {e.message}"
                for e in validador(ASYNCAPI[clave]["datos"]).iter_errors(mensaje["datos"])]
    return errores
