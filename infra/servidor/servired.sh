#!/usr/bin/env bash
# =====================================================================
# SERVI-RED · operación del servidor de pruebas (ADR-19)
#
#   infra/servidor/servired.sh preparar     genera .env con secretos nuevos (si no existe)
#   infra/servidor/servired.sh supabase     usa Supabase para la base de datos y las fotos
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
# Con Supabase no se levantan el PostgreSQL ni el almacén locales.
if grep -qE '^PERSISTENCIA_DB_URL=.*supabase' .env 2>/dev/null; then
  COMPOSE_FILE+=":infra/servidor/compose.supabase.yaml"
fi
# Construir de a pocas imágenes a la vez: evita quedarse sin memoria en VM pequeñas.
export COMPOSE_PARALLEL_LIMIT="${COMPOSE_PARALLEL_LIMIT:-2}"
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
IDENTIDAD_JWT_CLAVE_PRIVADA=$(sh infra/generar-clave-jwt.sh)
IDENTIDAD_ADMIN_CORREO=${ADMIN_CORREO:-admin@servired.local}
IDENTIDAD_ADMIN_CLAVE=$admin
GRAFANA_CLAVE=$(secreto)
TRAZAS_ACTIVAS=false
ENV
  aviso "Administrador: ${ADMIN_CORREO:-admin@servired.local}  clave: $admin"
  echo "   (guárdela ahora; también queda en .env, solo legible por este usuario)"
}

supabase() {
  [[ -f .env ]] || preparar
  command -v python3 >/dev/null || { echo "Falta python3" >&2; exit 1; }
  # Si se ejecuta dentro de "curl ... | bash", las respuestas se leen del teclado.
  if [[ ! -t 0 ]] && { true </dev/tty; } 2>/dev/null; then exec </dev/tty; fi
  cat <<'AYUDA'

Datos de su proyecto de Supabase (las claves no se muestran ni salen de esta máquina):
  1. Botón "Connect" > "Session pooler" (no "Direct connection", que solo es IPv6, ni el
     "Transaction pooler", que no sirve para las migraciones). Copie la URI:
       postgresql://postgres.<ref>:[YOUR-PASSWORD]@aws-0-<región>.pooler.supabase.com:5432/postgres
  2. Storage > cree un bucket PRIVADO llamado "evidencias".
  3. Project Settings > Storage > "S3 Connection": endpoint y región; y "New access key".
AYUDA
  local uri clave s3url s3region s3acceso s3secreto
  read -r -p "URI del Session pooler: " uri
  read -r -s -p "Clave de la base de datos: " clave; echo
  read -r -p "Endpoint S3 (https://<ref>.storage.supabase.co/storage/v1/s3): " s3url
  read -r -p "Región S3 (p. ej. sa-east-1): " s3region
  read -r -p "Access key ID: " s3acceso
  read -r -s -p "Secret access key: " s3secreto; echo
  cp -p .env ".env.antes-supabase"
  URI="$uri" CLAVE="$clave" S3URL="$s3url" S3REGION="$s3region" S3ACCESO="$s3acceso" S3SECRETO="$s3secreto" python3 - <<'PY'
import os, re, sys, urllib.parse
# Supabase muestra la URI con "[YOUR-PASSWORD]": se quita (la clave se pide aparte). Python 3.12+
# rechaza corchetes fuera de una dirección IPv6, así que se quitan antes de analizarla.
texto = re.sub(r":?\[[^\]]*\]@", "@", os.environ["URI"].strip())
u = urllib.parse.urlparse(texto)
if u.scheme not in ("postgres", "postgresql") or not (u.hostname or "").endswith("pooler.supabase.com") or (u.port or 5432) != 5432:
    sys.exit("La URI debe ser la del Session pooler (…pooler.supabase.com:5432).")
if not os.environ["S3URL"].strip().startswith("https://"):
    sys.exit("El endpoint S3 debe empezar por https://")
valores = {
    "PERSISTENCIA_DB_URL": f"jdbc:postgresql://{u.hostname}:5432{u.path or '/postgres'}?sslmode=require",
    "PERSISTENCIA_DB_USUARIO": urllib.parse.unquote(u.username or "postgres"),
    "PERSISTENCIA_DB_CLAVE": os.environ["CLAVE"],
    "ALMACEN_S3_URL": os.environ["S3URL"].strip().rstrip("/"),
    "ALMACEN_S3_REGION": os.environ["S3REGION"].strip(),
    "ALMACEN_S3_ACCESO": os.environ["S3ACCESO"].strip(),
    "ALMACEN_S3_SECRETO": os.environ["S3SECRETO"].strip(),
    "ALMACEN_S3_BUCKET": "evidencias",
    "ALMACEN_S3_CREAR_BUCKET": "false",
}
# Comillas simples: Docker Compose las toma literales (un "$" en la clave no se interpreta).
for k, v in valores.items():
    if "'" in v:
        sys.exit(f"{k} contiene una comilla simple; genere otro valor en Supabase.")
valores = {k: f"'{v}'" for k, v in valores.items()}
lineas = open(".env").read().splitlines()
vistas = set()
for i, l in enumerate(lineas):
    clave = l.split("=", 1)[0]
    if clave in valores:
        lineas[i] = f"{clave}={valores[clave]}"
        vistas.add(clave)
lineas += [f"{k}={v}" for k, v in valores.items() if k not in vistas]
open(".env", "w").write(chr(10).join(lineas) + chr(10))
print(f"Supabase configurado: {u.hostname} (usuario {valores['PERSISTENCIA_DB_USUARIO'].strip(chr(39))}).")
PY
  chmod 600 .env ".env.antes-supabase"
  aviso "Configuración anterior guardada en .env.antes-supabase. Ahora: servired.sh arrancar"
  echo "   Persistencia crea las tablas en Supabase al arrancar (migraciones)."
}

arrancar() {
  [[ -f .env ]] || preparar
  aviso "Construyendo y levantando (la primera vez tarda varios minutos)"
  docker compose ${PERFILES[@]+"${PERFILES[@]}"} up -d --build --wait
  if [[ "$COMPOSE_FILE" == *compose.supabase.yaml ]]; then
    # Con Supabase sobran el PostgreSQL y el almacén locales (sus volúmenes se conservan).
    docker compose --profile local rm -sf postgres-local almacen >/dev/null 2>&1 || true
  fi
  docker image prune -f >/dev/null
  aviso "Listo: $(curl -fsS http://127.0.0.1:8080/readyz >/dev/null && echo 'gateway sano' || echo 'gateway SIN responder')"
}

publicar() {
  command -v tailscale >/dev/null || { echo "Instale Tailscale e inicie sesión (tailscale up)" >&2; exit 1; }
  tailscale status >/dev/null || { echo "Tailscale no tiene sesión: ejecute 'tailscale up'" >&2; exit 1; }
  aviso "Publicando con Tailscale"
  # Una sola dirección pública (Funnel): el portal público en la raíz, el de
  # operación en /operacion/ (con inicio de sesión) y la API de las apps en /api.
  tailscale funnel --bg 4300
  # Publicaciones de versiones anteriores de este script que ya no se usan.
  tailscale serve --https=8443 off >/dev/null 2>&1 || true
  # Privado (Serve, solo su red Tailscale): Grafana, si está activa la observabilidad.
  if [[ "${OBSERVABILIDAD:-0}" == "1" ]]; then tailscale serve --bg --https=10000 3000; fi
  local nombre
  nombre="$(tailscale status --json | python3 -c 'import json,sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))')"
  aviso "Direcciones"
  echo "   Portal público (internet):        https://$nombre/"
  echo "   Portal de operación (internet):   https://$nombre/operacion/   (con inicio de sesión)"
  echo "   API para las apps (internet):     https://$nombre/   (base del gateway)"
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
  preparar|supabase|arrancar|publicar|actualizar|estado|detener) "$1" ;;
  *) sed -n '3,14p' "$0"; exit 1 ;;
esac
