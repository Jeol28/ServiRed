#!/bin/sh
# Ejecuta verificar-contratos.py en un contenedor con jsonschema (no instala nada en la máquina).
# Uso (desde la raíz):  sh pruebas/contratos/verificar-contratos.sh [script.py argumentos...]
set -e
RAIZ="$(cd "$(dirname "$0")/../.." && pwd)"
SCRIPT="${1:-pruebas/contratos/verificar-contratos.py}"
[ $# -gt 0 ] && shift
exec docker run --rm -v "$RAIZ:/repo:ro" ${SERVIRED_CAPTURA:+-v "$SERVIRED_CAPTURA:/captura:ro"} -w /repo \
  python:3.12-slim sh -c "pip install -q --disable-pip-version-check --root-user-action=ignore \
    jsonschema==4.23.0 rfc3339-validator==0.1.4 pyyaml==6.0.2 >/dev/null && \
    PYTHONPATH=/repo/pruebas/contratos python3 $SCRIPT $*"
