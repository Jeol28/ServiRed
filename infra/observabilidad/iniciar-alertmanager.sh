#!/bin/sh
# Genera la configuración de Alertmanager y la arranca. Las alertas operativas se ven en
# Alertmanager (127.0.0.1:9093) y en Grafana; no se envían a ningún lado (ADR-33: se retiró
# el servicio de Auditoría, que las recibía). Para notificar, agregue un receptor aquí.
set -eu
cat > /tmp/alertmanager.yml <<YML
route:
  receiver: consola
  group_by: [alertname, job, queue, tipo]
  group_wait: 10s
  group_interval: 1m
  repeat_interval: 4h
receivers:
  - name: consola
YML
exec /bin/alertmanager --config.file=/tmp/alertmanager.yml --storage.path=/alertmanager
