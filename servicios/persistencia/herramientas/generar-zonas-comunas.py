#!/usr/bin/env python3
"""
Genera src/main/resources/db/datos/zonas-comunas.tsv.gz con las localidades y comunas oficiales
de las ciudades que las publican como datos abiertos (ADR-35). La migración V20 las carga como
zonas de servicio (simplificadas a ~5 m y reparadas con ST_MakeValid).

Fuentes:
  * Bogotá: localidades, Catastro Bogotá / IDECA (datosabiertos.bogota.gov.co, «Localidad.
    Bogotá D.C», CC BY 4.0), servicio ordenamientoterritorial/localidad.
  * Medellín: comunas y corregimientos, GeoMedellín (capa «Comuna Catastral»).
  * Cali: comunas, IDESC (geoservicio dapm:pdt_dpa_comunas).
  * Barranquilla: localidades, unión de los «Barrios de Barranquilla según POT»
    (datos.gov.co qui6-qeux, CC BY-SA 4.0) por su localidad.
Cartagena y Bucaramanga no publican polígonos abiertos de sus localidades o comunas.

Formato: una línea por polígono, «clave TAB nombre TAB fuente TAB geometría GeoJSON» (WGS84,
6 decimales). Una clave puede repetirse (Barranquilla): la migración une sus partes.

Uso (desde servicios/persistencia):
    python3 herramientas/generar-zonas-comunas.py
"""
import gzip
import json
import sys
import urllib.request
from pathlib import Path

SALIDA = Path(__file__).resolve().parent.parent / "src/main/resources/db/datos/zonas-comunas.tsv.gz"
FUENTES = {
    "bogota": ("https://serviciosgis.catastrobogota.gov.co/arcgis/rest/services/ordenamientoterritorial/"
               "localidad/MapServer/0/query?where=1%3D1&outFields=LocCodigo,LocNombre&outSR=4326&f=geojson"),
    "medellin": ("https://www.medellin.gov.co/servidormapas/rest/services/ccio_ind_turism/"
                 "VA_Establecimiento_Comercial/MapServer/5/query?where=1%3D1&outFields=comuna,nombre"
                 "&outSR=4326&f=geojson"),
    "cali": ("https://ws-idesc.cali.gov.co/geoserver/dapm/ows?service=WFS&version=1.0.0&request=GetFeature"
             "&typeName=dapm:pdt_dpa_comunas&outputFormat=application/json&srsName=EPSG:4326"),
    "barranquilla": "https://www.datos.gov.co/resource/qui6-qeux.geojson?$limit=5000",
}
ORIGEN = {
    "bogota": "Catastro Bogotá / IDECA, localidades (CC BY 4.0)",
    "medellin": "Alcaldía de Medellín / GeoMedellín, comunas y corregimientos",
    "cali": "Alcaldía de Santiago de Cali / IDESC, comunas",
    "barranquilla": "Alcaldía de Barranquilla, barrios según POT unidos por localidad (CC BY-SA 4.0)",
}
LOCALIDADES_BARRANQUILLA = {"Riomar": "1", "Norte - Centro Historico": "2", "Suroccidente": "3",
                            "Metropolitana": "4", "Suroriental": "5", "Suroriente": "5"}


def leer(url):
    peticion = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (servired)"})
    with urllib.request.urlopen(peticion, timeout=300) as r:
        return json.load(r)


def redondear(c):
    return [redondear(x) for x in c] if isinstance(c[0], list) else [round(c[0], 6), round(c[1], 6)]


def geometria(g):
    return json.dumps({"type": g["type"], "coordinates": redondear(g["coordinates"])}, separators=(",", ":"))


def main():
    filas = []
    for f in leer(FUENTES["bogota"])["features"]:
        p = {k.lower(): v for k, v in f["properties"].items()}
        filas.append((f"11001-L{p['loccodigo']}", p["locnombre"].title(), ORIGEN["bogota"], f["geometry"]))
    for f in leer(FUENTES["medellin"])["features"]:
        p = f["properties"]
        tipo = "Corregimiento" if int(p["comuna"]) >= 50 else "Comuna"
        filas.append((f"05001-C{p['comuna']}", f"{tipo} {p['nombre'].title()}", ORIGEN["medellin"], f["geometry"]))
    for f in leer(FUENTES["cali"])["features"]:
        p = f["properties"]
        filas.append((f"76001-C{p['comcodigo']}", p["comnombre"], ORIGEN["cali"], f["geometry"]))
    sin_localidad = 0
    for f in leer(FUENTES["barranquilla"])["features"]:
        loc = (f["properties"].get("localidad") or "").split(",")[0].strip()
        if loc not in LOCALIDADES_BARRANQUILLA or not f.get("geometry"):
            sin_localidad += 1
            continue
        filas.append((f"08001-L{LOCALIDADES_BARRANQUILLA[loc]}", f"Localidad {loc}", ORIGEN["barranquilla"],
                      f["geometry"]))
    claves = {c for c, *_ in filas}
    esperadas = {"11001": 20, "05001": 21, "76001": 22, "08001": 5}
    for mun, n in esperadas.items():
        k = len({c for c in claves if c.startswith(mun)})
        if k != n:
            sys.exit(f"{mun}: se esperaban {n} zonas y hay {k}")
    with gzip.open(SALIDA, "wt", encoding="utf-8", compresslevel=9) as s:
        for clave, nombre, origen, g in sorted(filas, key=lambda x: x[0]):
            s.write(f"{clave}\t{nombre}\t{origen}\t{geometria(g)}\n")
    print(f"{len(claves)} zonas ({len(filas)} polígonos; {sin_localidad} barrios sin localidad) en {SALIDA}")


if __name__ == "__main__":
    main()
