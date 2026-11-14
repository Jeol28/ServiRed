#!/bin/sh
# Importa la topología (exchanges, colas y bindings) en RabbitMQ mediante la
# API de administración. Es idempotente: se puede ejecutar varias veces.
# No incluye usuarios: las credenciales viven solo en .env.
set -eu
curl -fsS -u "$RABBITMQ_USUARIO:$RABBITMQ_CLAVE" \
     -H "content-type: application/json" \
     -X POST --data @/definiciones/definiciones.json \
     "http://rabbitmq:15672/api/definitions"
echo "Topología de RabbitMQ aplicada"
