#!/usr/bin/env bash
# =====================================================================
# SERVI-RED · operación del servidor de pruebas (ADR-19)
#
#   infra/servidor/servired.sh preparar     genera .env con secretos nuevos (si no existe)
#   infra/servidor/servired.sh arrancar     construye y levanta todo (espera a que esté sano)
#   infra/servidor/servired.sh publicar     publica con Tailscale y muestra las direcciones
#   infra/servidor/servired.sh actualizar   git pull + arrancar
#   infra/servidor/servired.sh estado       contenedores, publicación y salud
#   infra/servidor/servired.sh detener      apaga todo (los datos se conservan)
#
# OBSERVABILIDAD=1 agrega Prometheus, Alertmanager, Grafana y Jaeger.
# =====================================================================
set -euo pipefail

RAIZ="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$RAIZ"
export COMPOSE_FILE="compose.yaml:infra/servidor/compose.servidor.yaml"
PERFILES=()
if [[ "${OBSERVABILIDAD:-0}" == "1" ]]; then
  PERFILES=(--profile observabilidad)
  export TRAZAS_ACTIVAS=true
fi

aviso() { printf '\n==> %s\n' "$*"; }
secreto() { openssl rand -hex 24; }

preparar() {
  if [[ -f .env ]]; then
    aviso ".env ya existe: no se modifica"
    return
  fi
  command -v openssl >/dev/null || { echo "Falta openssl" >&2; exit 1; }
  local db rabbit admin
  db="$(secreto)"; rabbit="$(secreto)"; admin="$(openssl rand -base64 18 | tr -d '/+=')"
  aviso "Generando .env con secretos nuevos"
  umask 077
  cat > .env <<ENV
# Generado por infra/servidor/servired.sh el $(date -u +%Y-%m-%dT%H:%MZ). NO versionar.
PERSISTENCIA_DB_URL=jdbc:postgresql://postgres-local:5432/servired
PERSISTENCIA_DB_USUARIO=servired
PERSISTENCIA_DB_CLAVE=$db
POSTGRES_LOCAL_USUARIO=servired
POSTGRES_LOCAL_CLAVE=$db
ALMACEN_S3_URL=http://almacen:8333
ALMACEN_S3_REGION=us-east-1
ALMACEN_S3_ACCESO=servired-almacen
ALMACEN_S3_SECRETO=$(secreto)
ALMACEN_S3_BUCKET=evidencias
ALMACEN_S3_CREAR_BUCKET=true
RABBITMQ_USUARIO=servired
RABBITMQ_CLAVE=$rabbit
PERSISTENCIA_CLAVE_IDENTIDAD=$(secreto)
PERSISTENCIA_CLAVE_REPORTES=$(secreto)
PERSISTENCIA_CLAVE_ATENCION=$(secreto)
PERSISTENCIA_CLAVE_INDICADORES=$(secreto)
PERSISTENCIA_CLAVE_AUDITORIA=$(secreto)
IDENTIDAD_JWT_CLAVE_PRIVADA=$(sh infra/generar-clave-jwt.sh)
IDENTIDAD_ADMIN_CORREO=${ADMIN_CORREO:-admin@servired.local}
IDENTIDAD_ADMIN_CLAVE=$admin
ALERTAS_TOKEN=$(secreto)
GRAFANA_CLAVE=$(secreto)
TRAZAS_ACTIVAS=false
ENV
  aviso "Administrador: ${ADMIN_CORREO:-admin@servired.local}  clave: $admin"
  echo "   (guárdela ahora; también queda en .env, solo legible por este usuario)"
}

arrancar() {
  [[ -f .env ]] || preparar
  aviso "Construyendo y levantando (la primera vez tarda varios minutos)"
  docker compose ${PERFILES[@]+"${PERFILES[@]}"} up -d --build --wait
  docker image prune -f >/dev/null
  aviso "Listo: $(curl -fsS http://127.0.0.1:8080/readyz >/dev/null && echo 'gateway sano' || echo 'gateway SIN responder')"
}

publicar() {
  command -v tailscale >/dev/null || { echo "Instale Tailscale e inicie sesión (tailscale up)" >&2; exit 1; }
  tailscale status >/dev/null || { echo "Tailscale no tiene sesión: ejecute 'tailscale up'" >&2; exit 1; }
  aviso "Publicando con Tailscale"
  # Público (Funnel): portal de operación y, en su /api, la API de las apps.
  # Los ciudadanos solo usan las apps (como Waze): el portal público queda privado.
  tailscale funnel --bg 4200
  # Privado (Serve, solo su red Tailscale): portal público y, si está, Grafana.
  tailscale serve --bg --https=8443 4300
  if [[ "${OBSERVABILIDAD:-0}" == "1" ]]; then tailscale serve --bg --https=10000 3000; fi
  local nombre
  nombre="$(tailscale status --json | python3 -c 'import json,sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))')"
  aviso "Direcciones"
  echo "   Portal de operación (internet):   https://$nombre/"
  echo "   API para las apps (internet):     https://$nombre/   (base del gateway)"
  echo "   Portal público (su tailnet):      https://$nombre:8443/"
  if [[ "${OBSERVABILIDAD:-0}" == "1" ]]; then echo "   Grafana (su tailnet):             https://$nombre:10000/"; fi
}

actualizar() {
  aviso "Actualizando el código"
  git pull --ff-only
  arrancar
}

estado() {
  docker compose ${PERFILES[@]+"${PERFILES[@]}"} ps --format 'table {{.Service}}\t{{.Status}}'
  if command -v tailscale >/dev/null; then aviso "Publicación"; tailscale serve status || true; fi
  aviso "Gateway: $(curl -fsS http://127.0.0.1:8080/readyz >/dev/null && echo sano || echo 'sin responder')"
}

detener() {
  docker compose ${PERFILES[@]+"${PERFILES[@]}"} down
}

case "${1:-}" in
  preparar|arrancar|publicar|actualizar|estado|detener) "$1" ;;
  *) sed -n '3,13p' "$0"; exit 1 ;;
esac
