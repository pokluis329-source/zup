#!/bin/bash
# ── Zuppon — instalación inicial en VPS Ubuntu/Debian ──
# Ejecutar como root en el servidor:
#   curl -sL ... | bash   O   bash setup-vps.sh
set -euo pipefail

DOMAIN="institutocaacupepy.es"
APP_DIR="/var/www/zup"
REPO_URL="${REPO_URL:-}"

echo "==> Paquetes del sistema"
apt-get update
apt-get install -y python3 python3-venv python3-pip nginx certbot python3-certbot-nginx git

echo "==> Directorios"
mkdir -p /var/log/zuppon "$APP_DIR"
chown www-data:www-data /var/log/zuppon 2>/dev/null || true

if [ ! -f "$APP_DIR/backend/deploy/nginx-zuppon.conf" ]; then
  echo ""
  echo "ERROR: No está el código en $APP_DIR"
  echo "Primero subí el proyecto. Desde tu PC (PowerShell):"
  echo "  cd backend\\deploy"
  echo "  .\\push-from-windows.ps1 -Server root@TU_IP"
  echo ""
  echo "O en el VPS:"
  echo "  git clone https://github.com/TU_USUARIO/zup.git $APP_DIR"
  exit 1
fi

echo "==> Nginx"
cp "$APP_DIR/backend/deploy/nginx-zuppon.conf" /etc/nginx/sites-available/zuppon
ln -sf /etc/nginx/sites-available/zuppon /etc/nginx/sites-enabled/zuppon
rm -f /etc/nginx/sites-enabled/default
nginx -t && systemctl reload nginx

echo "==> Systemd (servicio zup)"
cp "$APP_DIR/backend/deploy/zup.service" /etc/systemd/system/zup.service
systemctl daemon-reload
systemctl enable zup

echo ""
echo "Próximos pasos:"
echo "  1. nano $APP_DIR/backend/.env   (alias + cédula de transferencia)"
echo "  2. bash $APP_DIR/backend/deploy/update.sh"
echo "  3. certbot --nginx -d $DOMAIN   (SIN www — no tiene DNS)"
