#!/usr/bin/env bash
# =====================================================================
# Genera src/main/resources/db/datos/limites-municipales-mgn2024.bin.gz con los
# polígonos municipales oficiales del DANE (Marco Geoestadístico Nacional 2024,
# capa MGN_ADM_MPIO_GRAFICO, WGS84) para resolver el municipio de un reporte (ADR-27).
#
#   servicios/persistencia/herramientas/generar-limites-municipales.sh
#
# 1. Descarga MGN2024_MPIO_POLITICO.zip del geoportal del DANE (~96 MB).
# 2. Lo carga en un PostGIS temporal (imagen de infra/postgres).
# 3. Simplifica con ST_CoverageSimplify a 0,0001° (~11 m): los bordes compartidos se
#    simplifican igual en ambos lados, así que no quedan huecos ni solapes. Medido
#    contra los polígonos originales: 0 % de error en puntos al azar del territorio y
#    0,075 % a menos de 500 m de un límite (los centroides fallaban en 44 % y 60 %).
# 4. Exporta cada municipio como TWKB con 5 decimales (~1 m) en un binario comprimido:
#    por registro, el código DIVIPOLA (5 bytes ASCII), la longitud (4 bytes, big endian)
#    y el TWKB. Lo carga la migración V13 (db.migracion.V13__limites_municipales).
# =====================================================================
set -euo pipefail

RAIZ="$(cd "$(dirname "$0")/../../.." && pwd)"
SALIDA="$RAIZ/servicios/persistencia/src/main/resources/db/datos/limites-municipales-mgn2024.bin.gz"
URL="https://geoportal.dane.gov.co/descargas/mgn_2024/MGN2024_MPIO_POLITICO.zip"
TOLERANCIA=0.0001
TRABAJO="$(mktemp -d "${HOME}/.cache/servired-mgn.XXXXXX")"
CONTENEDOR="servired-mgn-$$"
trap 'docker rm -f "$CONTENEDOR" >/dev/null 2>&1 || true; rm -rf "$TRABAJO"' EXIT

echo "Descargando $URL"
curl -fsSL --max-time 1800 -o "$TRABAJO/mgn.zip" "$URL"
mkdir "$TRABAJO/shp"
python3 -I -c "import sys, zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" "$TRABAJO/mgn.zip" "$TRABAJO/shp"

docker build -q -t servired/postgres-postgis:17 "$RAIZ/infra/postgres" >/dev/null
docker run -d --name "$CONTENEDOR" -e POSTGRES_PASSWORD=temporal -v "$TRABAJO/shp:/shp:ro" \
  servired/postgres-postgis:17 >/dev/null
until docker exec "$CONTENEDOR" pg_isready -U postgres >/dev/null 2>&1; do sleep 1; done
sleep 2
echo "Cargando y simplificando (tolerancia $TOLERANCIA°)"
docker exec "$CONTENEDOR" sh -c "apt-get update -qq >/dev/null && apt-get install -y -qq postgis >/dev/null 2>&1 \
  && psql -U postgres -qc 'CREATE EXTENSION postgis' \
  && shp2pgsql -s 4326 -W UTF-8 /shp/MGN_ADM_MPIO_GRAFICO.shp public.mgn 2>/dev/null | psql -U postgres -q >/dev/null"
docker exec "$CONTENEDOR" psql -U postgres -tAc "
  COPY (SELECT mpio_cdpmp, encode(ST_AsTWKB(ST_SetSRID(ST_CoverageSimplify(geom, $TOLERANCIA) OVER (), 4326), 5), 'hex')
        FROM mgn ORDER BY mpio_cdpmp) TO STDOUT" > "$TRABAJO/limites.tsv"

python3 -I - "$TRABAJO/limites.tsv" "$SALIDA" <<'PY'
import gzip, struct, sys
n = 0
with open(sys.argv[1]) as f, gzip.open(sys.argv[2], "wb", compresslevel=9) as salida:
    for linea in f:
        codigo, hexa = linea.rstrip("\n").split("\t")
        assert len(codigo) == 5 and codigo.isdigit(), codigo
        twkb = bytes.fromhex(hexa)
        salida.write(codigo.encode("ascii") + struct.pack(">I", len(twkb)) + twkb)
        n += 1
print(f"{n} municipios en {sys.argv[2]}")
PY
