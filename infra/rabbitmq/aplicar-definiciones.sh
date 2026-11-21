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

# Colas retiradas: importar definiciones no borra lo que ya existe en un broker en marcha.
# auditoria.eventos escuchaba «#» y, sin consumidor, acumularía todo (ADR-33).
for cola in auditoria.eventos auditoria.eventos.reintento auditoria.eventos.dlq; do
  codigo=$(curl -sS -o /dev/null -w "%{http_code}" -u "$RABBITMQ_USUARIO:$RABBITMQ_CLAVE" \
       -X DELETE "http://rabbitmq:15672/api/queues/%2F/$cola")
  [ "$codigo" = "204" ] && echo "Cola retirada eliminada: $cola"
done
exit 0
