#!/bin/bash
# ── Zup — actualizar código y reiniciar servicio ──
# En el VPS:  bash /var/www/zup/backend/deploy/update.sh
set -euo pipefail

APP_DIR="/var/www/zup"
BACKEND="$APP_DIR/backend"
SERVICE="${SERVICE:-zup}"

cd "$APP_DIR"
if [ -d .git ]; then
  git pull --ff-only || true
fi

cd "$BACKEND"

if [ ! -f .env ]; then
  echo "ERROR: falta $BACKEND/.env"
  exit 1
fi

python3 -m venv venv
./venv/bin/pip install -q -U pip
./venv/bin/pip install -q -r requirements.txt

mkdir -p "$BACKEND/uploads/receipts"
chown -R www-data:www-data "$BACKEND/uploads" 2>/dev/null || true

# Instalar/actualizar unit systemd
cp "$BACKEND/deploy/zup.service" /etc/systemd/system/zup.service
systemctl daemon-reload
systemctl enable zup

chown -R www-data:www-data "$BACKEND"

systemctl restart "$SERVICE"
sleep 2
systemctl status "$SERVICE" --no-pager || true

echo ""
curl -sf http://127.0.0.1:5003/health && echo "" || {
  echo "Backend no responde en :5003 — ver logs:"
  echo "  journalctl -u zup -n 40 --no-pager"
  exit 1
}

echo "Deploy OK."
