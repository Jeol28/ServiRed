# Infraestructura local

| Archivo | Uso |
|---|---|
| [`docker-compose.yml`](docker-compose.yml) | Todo el entorno. Se invoca desde la raíz (`compose.yaml` lo incluye). |
| [`rabbitmq/generar-definiciones.py`](rabbitmq/generar-definiciones.py) | Genera `definiciones.json` (exchanges, colas y bindings). |
| [`rabbitmq/aplicar-definiciones.sh`](rabbitmq/aplicar-definiciones.sh) | Lo ejecuta el contenedor `rabbitmq-init` para importar la topología. |
| [`rabbitmq/verificar-topologia.py`](rabbitmq/verificar-topologia.py) | Prueba de humo: enrutamiento, DLQ y reintento con TTL. |

## Servicios del compose

| Servicio | Imagen / origen | Red | Puerto en el host |
|---|---|---|---|
| postgres-local | postgres:17-alpine | interna | — |
| rabbitmq | rabbitmq:4.1-management-alpine | interna, borde | 127.0.0.1:15672 (consola) |
| rabbitmq-init | curlimages/curl | interna | — (termina al aplicar la topología) |
| persistencia | `servicios/persistencia` | interna, borde (salida a Supabase) | — |
| identidad, reportes, atencion, indicadores, auditoria | `servicios/*` | interna | — |
| gateway | `servicios/gateway` | borde, interna | **8080** |

La red `interna` es `internal: true`: sin salida a Internet ni puertos publicados.

## Cambiar la topología de RabbitMQ
1. Edite `COLAS` en `rabbitmq/generar-definiciones.py` y ejecútelo.
2. `docker compose up rabbitmq-init` vuelve a importar las definiciones.
   Cambiar argumentos de una cola existente exige borrarla antes (RabbitMQ no
   permite redeclararla con otros argumentos).
3. `python3 infra/rabbitmq/verificar-topologia.py`.

Ver [ADR-10](../docs/adr/ADR-10-topologia-rabbitmq.md).
