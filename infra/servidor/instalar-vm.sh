#!/usr/bin/env bash
# =====================================================================
# SERVI-RED · instalación en una VM nueva (Ubuntu 24.04, ARM o x86) — ADR-19
#
#   curl -fsSL https://raw.githubusercontent.com/<usuario>/ServiRed/main/infra/servidor/instalar-vm.sh | bash
#   (o copiar este archivo a la VM y ejecutarlo)
#
# Instala Docker y Tailscale, clona el repositorio, genera los secretos,
# levanta todo y lo publica. Pide una sola cosa: abrir el enlace de inicio de
# sesión de Tailscale que se imprime.
# =====================================================================
set -euo pipefail
REPO="${REPO:-https://github.com/Jeol28/ServiRed.git}"
DESTINO="${DESTINO:-$HOME/ServiRed}"
NOMBRE="${NOMBRE:-servired}"   # nombre en Tailscale: https://<NOMBRE>.<su-red>.ts.net

echo "==> Paquetes base"
sudo apt-get update -y
sudo apt-get install -y ca-certificates curl git openssl python3

# Compilar Java, .NET y Angular puede superar la RAM de una VM de 8 GB: 4 GB de swap.
if ! swapon --show | grep -q /swapfile; then
  echo "==> Swap de 4 GB"
  sudo fallocate -l 4G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
  grep -q '/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null
fi

if ! command -v docker >/dev/null; then
  echo "==> Docker"
  curl -fsSL https://get.docker.com | sudo sh
  sudo usermod -aG docker "$USER"
fi

if ! command -v tailscale >/dev/null; then
  echo "==> Tailscale"
  curl -fsSL https://tailscale.com/install.sh | sh
fi
# Abra el enlace que aparece para unir la VM a su red Tailscale.
sudo tailscale up --operator="$USER" --hostname="$NOMBRE"

echo "==> Código"
if [[ -d "$DESTINO/.git" ]]; then git -C "$DESTINO" pull --ff-only; else git clone "$REPO" "$DESTINO"; fi

# 'sg docker' aplica el grupo docker sin cerrar la sesión.
sg docker -c "'$DESTINO/infra/servidor/servired.sh' preparar && '$DESTINO/infra/servidor/servired.sh' arrancar"
"$DESTINO/infra/servidor/servired.sh" publicar
