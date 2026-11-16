#!/bin/sh
# Arranca SeaweedFS con su gateway S3 y una única identidad con las claves de
# .env (ALMACEN_S3_ACCESO / ALMACEN_S3_SECRETO). Sin identidad configurada el
# S3 de SeaweedFS aceptaría peticiones anónimas, por eso se exige.
set -eu
: "${ALMACEN_S3_ACCESO:?definir en .env}"
: "${ALMACEN_S3_SECRETO:?definir en .env}"
mkdir -p /data
cat > /tmp/s3.json <<JSON
{"identities":[{"name":"persistencia",
  "credentials":[{"accessKey":"${ALMACEN_S3_ACCESO}","secretKey":"${ALMACEN_S3_SECRETO}"}],
  "actions":["Admin","Read","List","Write","Tagging"]}]}
JSON
exec weed server -dir=/data -ip.bind=0.0.0.0 -master.volumeSizeLimitMB=256 -volume.max=20 \
  -s3 -s3.port=8333 -s3.config=/tmp/s3.json
