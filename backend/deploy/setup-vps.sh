#!/bin/bash
# ── Zuppon — instalación inicial en VPS Ubuntu/Debian ──
# Ejecutar como root en el servidor:
#   curl -sL ... | bash   O   bash setup-vps.sh
set -euo pipefail

DOMAIN="institutocaacupepy.es"
APP_DIR="/var/www/zuppon"
REPO_URL="${REPO_URL:-}"   # opcional: git clone URL

echo "==> Paquetes del sistema"
apt-get update
apt-get install -y python3 python3-venv python3-pip nginx certbot python3-certbot-nginx git

echo "==> Directorios"
mkdir -p /var/log/zuppon
chown www-data:www-data /var/log/zuppon

if [ ! -d "$APP_DIR/.git" ] && [ -n "$REPO_URL" ]; then
  git clone "$REPO_URL" "$APP_DIR"
elif [ ! -d "$APP_DIR/backend" ]; then
  echo "Copiá el proyecto a $APP_DIR (git clone o rsync) y volvé a correr update.sh"
  mkdir -p "$APP_DIR"
fi

echo "==> Nginx"
cp "$APP_DIR/backend/deploy/nginx-zuppon.conf" /etc/nginx/sites-available/zuppon
ln -sf /etc/nginx/sites-available/zuppon /etc/nginx/sites-enabled/zuppon
rm -f /etc/nginx/sites-enabled/default
nginx -t && systemctl reload nginx

echo "==> Systemd"
cp "$APP_DIR/backend/deploy/zuppon.service" /etc/systemd/system/zuppon.service
systemctl daemon-reload
systemctl enable zuppon

echo ""
echo "Próximos pasos:"
echo "  1. Crear $APP_DIR/backend/.env (copiar de .env.example + tokens Pagopar)"
echo "  2. bash $APP_DIR/backend/deploy/update.sh"
echo "  3. certbot --nginx -d $DOMAIN -d www.$DOMAIN --non-interactive --agree-tos -m TU_EMAIL"
echo "  4. En .env: PUBLIC_BASE_URL=https://$DOMAIN"
echo "  5. En panel Pagopar webhook: https://$DOMAIN/api/payments/pagopar/webhook"
