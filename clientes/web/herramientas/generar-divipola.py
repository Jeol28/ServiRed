#!/usr/bin/env python3
"""
Genera el catálogo de departamentos y municipios (DIVIPOLA del DANE) para las
listas desplegables de los portales, a partir de la misma semilla que usa la
base de datos (V3__semilla_divipola.sql). Así hay una sola fuente.

Uso (desde clientes/web):
    python3 herramientas/generar-divipola.py
"""
import json
import re
import unicodedata
from pathlib import Path

RAIZ = Path(__file__).resolve().parents[3]
SEMILLA = RAIZ / "servicios/persistencia/src/main/resources/db/migracion/V3__semilla_divipola.sql"
SALIDA = RAIZ / "clientes/web/projects/compartido/src/lib/territorio/divipola.ts"

MINUSCULAS = {"de", "del", "la", "las", "los", "el", "y", "e", "en"}


def nombre_propio(texto):
    """'EL CARMEN DE VIBORAL' -> 'El Carmen de Viboral'; 'BOGOTÁ, D.C.' -> 'Bogotá, D.C.'."""
    palabras = []
    for i, p in enumerate(texto.lower().split(" ")):
        if "." in p:
            palabras.append(p.upper())
        elif i > 0 and p in MINUSCULAS:
            palabras.append(p)
        else:
            palabras.append("-".join(s[:1].upper() + s[1:] for s in p.split("-")))
    return " ".join(palabras)


def orden(texto):
    """Orden alfabético en español sin tildes: 'Ábrego' va con la A, no al final."""
    return unicodedata.normalize("NFD", texto.lower()).encode("ascii", "ignore").decode()


def main():
    sql = SEMILLA.read_text(encoding="utf-8")
    deps = re.findall(r"\('(\d{2})', '((?:[^']|'')+)'\)", sql.split("INSERT INTO catalogo.municipios")[0])
    muns = re.findall(r"\('(\d{5})', '(\d{2})', '((?:[^']|'')+)', '[A-Z_]+',", sql)
    if len(deps) != 33 or len(muns) < 1100:
        raise SystemExit(f"Datos inesperados: {len(deps)} departamentos, {len(muns)} municipios")

    catalogo = []
    for codigo, nombre in sorted(deps, key=lambda d: orden(d[1])):
        propios = sorted(
            ([m[0][2:], nombre_propio(m[2].replace("''", "'"))] for m in muns if m[1] == codigo),
            key=lambda m: orden(m[1]))
        catalogo.append([codigo, nombre_propio(nombre.replace("''", "'")), propios])

    cuerpo = json.dumps(catalogo, ensure_ascii=False, separators=(",", ":"))
    SALIDA.write_text(
        "// Catálogo DIVIPOLA (DANE): [código del departamento, nombre, [[sufijo del municipio, nombre]]].\n"
        "// Generado por clientes/web/herramientas/generar-divipola.py desde V3__semilla_divipola.sql.\n"
        "// No editar a mano.\n"
        f"export const DIVIPOLA: [string, string, [string, string][]][] = {cuerpo};\n",
        encoding="utf-8")
    print(f"{len(deps)} departamentos y {len(muns)} municipios -> {SALIDA.relative_to(RAIZ)}")


if __name__ == "__main__":
    main()
