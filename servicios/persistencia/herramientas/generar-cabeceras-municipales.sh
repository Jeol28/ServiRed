#!/usr/bin/env bash
# =====================================================================
# Genera src/main/resources/db/datos/cabeceras-municipales-mgn2024.bin.gz con las áreas
# urbanas de las cabeceras municipales (DANE, MGN 2024, capa MGN_URB_ZONA_URBANA,
# clas_ccdgo = 1) para la privacidad rural del mapa público (ADR-28). Los centros poblados
# (clase 2) cuentan como rurales, igual que en el DANE.
#
#   servicios/persistencia/herramientas/generar-cabeceras-municipales.sh
#
# Une los polígonos por municipio y simplifica con ST_SimplifyPreserveTopology a
# 0,00005° (~5 m): 0,3 % de diferencia de área. Exporta TWKB (5 decimales) con el mismo
# formato que generar-limites-municipales.sh; lo carga la migración V14.
# =====================================================================
set -euo pipefail

RAIZ="$(cd "$(dirname "$0")/../../.." && pwd)"
SALIDA="$RAIZ/servicios/persistencia/src/main/resources/db/datos/cabeceras-municipales-mgn2024.bin.gz"
URL="https://geoportal.dane.gov.co/descargas/mgn_2024/MGN2024_URB_ZONA_URBANA.zip"
TOLERANCIA=0.00005
TRABAJO="$(mktemp -d "${HOME}/.cache/servired-urb.XXXXXX")"
CONTENEDOR="servired-urb-$$"
trap 'docker rm -f "$CONTENEDOR" >/dev/null 2>&1 || true; rm -rf "$TRABAJO"' EXIT

echo "Descargando $URL"
curl -fsSL --max-time 1800 -o "$TRABAJO/urb.zip" "$URL"
mkdir "$TRABAJO/shp"
python3 -I -c "import sys, zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" "$TRABAJO/urb.zip" "$TRABAJO/shp"

docker build -q -t servired/postgres-postgis:17 "$RAIZ/infra/postgres" >/dev/null
docker run -d --name "$CONTENEDOR" -e POSTGRES_PASSWORD=temporal -v "$TRABAJO/shp:/shp:ro" \
  servired/postgres-postgis:17 >/dev/null
until docker exec "$CONTENEDOR" pg_isready -U postgres >/dev/null 2>&1; do sleep 1; done
sleep 2
docker exec "$CONTENEDOR" sh -c "apt-get update -qq >/dev/null && apt-get install -y -qq postgis >/dev/null 2>&1 \
  && psql -U postgres -qc 'CREATE EXTENSION postgis' \
  && shp2pgsql -s 4326 -W UTF-8 /shp/MGN_URB_ZONA_URBANA.shp public.zu 2>/dev/null | psql -U postgres -q >/dev/null"
docker exec "$CONTENEDOR" psql -U postgres -tAc "
  COPY (SELECT mpio_cdpmp, encode(ST_AsTWKB(ST_SimplifyPreserveTopology(ST_Multi(ST_Union(geom)), $TOLERANCIA), 5), 'hex')
        FROM zu WHERE clas_ccdgo = '1' GROUP BY mpio_cdpmp ORDER BY mpio_cdpmp) TO STDOUT" > "$TRABAJO/cabeceras.tsv"

python3 -I - "$TRABAJO/cabeceras.tsv" "$SALIDA" <<'PY'
import gzip, struct, sys
n = 0
with open(sys.argv[1]) as f, gzip.open(sys.argv[2], "wb", compresslevel=9) as salida:
    for linea in f:
        codigo, hexa = linea.rstrip("\n").split("\t")
        assert len(codigo) == 5 and codigo.isdigit(), codigo
        twkb = bytes.fromhex(hexa)
        salida.write(codigo.encode("ascii") + struct.pack(">I", len(twkb)) + twkb)
        n += 1
print(f"{n} cabeceras en {sys.argv[2]}")
PY
