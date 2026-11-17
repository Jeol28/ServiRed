# Infraestructura local

| Archivo | Uso |
|---|---|
| [`docker-compose.yml`](docker-compose.yml) | Todo el entorno. Se invoca desde la raíz (`compose.yaml` lo incluye). |
| [`rabbitmq/generar-definiciones.py`](rabbitmq/generar-definiciones.py) | Genera `definiciones.json` (exchanges, colas y bindings). |
| [`rabbitmq/aplicar-definiciones.sh`](rabbitmq/aplicar-definiciones.sh) | Lo ejecuta el contenedor `rabbitmq-init` para importar la topología. |
| [`almacen/iniciar-almacen.sh`](almacen/iniciar-almacen.sh) | Arranca SeaweedFS (S3) con la identidad de `.env`; sin ella rechaza el acceso anónimo. |
| [`generar-clave-jwt.sh`](generar-clave-jwt.sh) | Genera la clave RSA de firma de los JWT para `IDENTIDAD_JWT_CLAVE_PRIVADA`. |
| [`rabbitmq/verificar-topologia.py`](rabbitmq/verificar-topologia.py) | Prueba de humo: compara el broker con `definiciones.json` y prueba enrutamiento, DLQ y reintento con TTL en colas temporales (no toca las colas reales). |

## Observabilidad (perfil opcional)

```bash
TRAZAS_ACTIVAS=true docker compose --profile observabilidad up -d --build --wait
```

| Herramienta | URL (solo localhost) | Para qué |
|---|---|---|
| Prometheus | http://127.0.0.1:9090 | Métricas de los 7 servicios y de RabbitMQ; [reglas](observabilidad/reglas.yml) |
| Alertmanager | http://127.0.0.1:9093 | Envía las alertas al webhook interno de Auditoría |
| Grafana | http://127.0.0.1:3000 (admin / `GRAFANA_CLAVE`) | Tablero "SERVI-RED · Operación" |
| Jaeger | http://127.0.0.1:16686 | Trazas distribuidas (OTLP) |

Sin el perfil, las alertas de seguridad siguen activas dentro de Auditoría
([ADR-16](../docs/adr/ADR-16-auditoria-y-alertas.md)).

## Servicios del compose

| Servicio | Imagen / origen | Red | Puerto en el host |
|---|---|---|---|
| postgres-local | postgres:17-alpine | interna | — |
| almacen | chrislusf/seaweedfs:3.97 (S3) | interna | — (solo lo usa persistencia) |
| rabbitmq | rabbitmq:4.1-management-alpine | interna, borde | 127.0.0.1:15672 (consola) |
| rabbitmq-init | curlimages/curl | interna | — (termina al aplicar la topología) |
| persistencia | `servicios/persistencia` | interna, borde (salida a Supabase) | — |
| identidad, reportes, atencion, indicadores, auditoria | `servicios/*` | interna | — |
| prometheus, alertmanager, grafana, jaeger | perfil `observabilidad` | interna, borde | 9090, 9093, 3000, 16686 (127.0.0.1) |
| gateway | `servicios/gateway` | borde, interna | **8080** (actuator en 8081, solo interno) |

La red `interna` es `internal: true`: sin salida a Internet ni puertos publicados.

## Cambiar la topología de RabbitMQ
1. Edite `COLAS` en `rabbitmq/generar-definiciones.py` y ejecútelo.
2. `docker compose up rabbitmq-init` vuelve a importar las definiciones.
   Cambiar argumentos de una cola existente exige borrarla antes (RabbitMQ no
   permite redeclararla con otros argumentos).
3. `python3 infra/rabbitmq/verificar-topologia.py`.

Ver [ADR-10](../docs/adr/ADR-10-topologia-rabbitmq.md).
