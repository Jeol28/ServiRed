#!/usr/bin/env python3
"""
Genera la migración con la semilla de departamentos y municipios de Colombia
(DIVIPOLA del DANE) a partir del conjunto de datos abiertos oficial:

    https://www.datos.gov.co/resource/gdxc-w37w.csv?$limit=5000

Columnas esperadas: cod_dpto, dpto, cod_mpio, nom_mpio, tipo_municipio,
longitud, latitud (con coma decimal). Los centroides se usan para resolver el
municipio más cercano a la ubicación de un reporte (ADR-08).

Uso:
    python3 herramientas/generar-semilla-divipola.py divipola.csv \
        > src/main/resources/db/migracion/V3__semilla_divipola.sql
"""
import csv
import sys

TIPOS = {"Municipio": "MUNICIPIO", "Isla": "ISLA", "Área no municipalizada": "AREA_NO_MUNICIPALIZADA"}


def texto(valor):
    return "'" + valor.strip().replace("'", "''") + "'"


def numero(valor):
    return format(float(valor.strip().replace(",", ".")), ".6f")


def main(ruta):
    with open(ruta, encoding="utf-8", newline="") as f:
        filas = list(csv.DictReader(f))

    departamentos = sorted({(f["cod_dpto"].zfill(2), f["dpto"].strip()) for f in filas})
    municipios = sorted(filas, key=lambda f: f["cod_mpio"])
    if len(departamentos) != 33 or len(municipios) < 1100:
        sys.exit(f"Datos inesperados: {len(departamentos)} departamentos, {len(municipios)} municipios")

    print("-- Semilla DIVIPOLA (DANE). Generado por herramientas/generar-semilla-divipola.py.")
    print("-- Fuente: datos.gov.co, conjunto gdxc-w37w. No editar a mano.")
    print(f"-- {len(departamentos)} departamentos y {len(municipios)} municipios / áreas no municipalizadas.\n")
    print("INSERT INTO catalogo.departamentos (codigo, nombre) VALUES")
    print(",\n".join(f"  ({texto(c)}, {texto(n)})" for c, n in departamentos) + ";\n")
    print("INSERT INTO catalogo.municipios (codigo, codigo_departamento, nombre, tipo, latitud, longitud) VALUES")
    print(",\n".join(
        f"  ({texto(m['cod_mpio'].zfill(5))}, {texto(m['cod_dpto'].zfill(2))}, {texto(m['nom_mpio'])}, "
        f"{texto(TIPOS[m['tipo_municipio'].strip()])}, {numero(m['latitud'])}, {numero(m['longitud'])})"
        for m in municipios) + ";")


if __name__ == "__main__":
    main(sys.argv[1])
