#!/bin/sh
# Genera la configuración de Alertmanager con el token de .env (ALERTAS_TOKEN)
# y la arranca. Todas las alertas se envían al webhook interno de Auditoría.
set -eu
: "${ALERTAS_TOKEN:?definir en .env}"
cat > /tmp/alertmanager.yml <<YML
route:
  receiver: auditoria
  group_by: [alertname, job, queue, tipo]
  group_wait: 10s
  group_interval: 1m
  repeat_interval: 4h
receivers:
  - name: auditoria
    webhook_configs:
      - url: http://auditoria:8080/interno/v1/alertas
        send_resolved: true
        http_config:
          authorization:
            type: Bearer
            credentials: ${ALERTAS_TOKEN}
YML
exec /bin/alertmanager --config.file=/tmp/alertmanager.yml --storage.path=/alertmanager
