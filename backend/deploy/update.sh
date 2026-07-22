#!/bin/bash
# ── Zuppon — actualizar código y reiniciar servicio ──
# En el VPS:  bash /var/www/zuppon/backend/deploy/update.sh
set -euo pipefail

APP_DIR="/var/www/zuppon"
BACKEND="$APP_DIR/backend"

cd "$APP_DIR"
if [ -d .git ]; then
  git pull --ff-only
fi

cd "$BACKEND"

if [ ! -f .env ]; then
  echo "ERROR: falta $BACKEND/.env"
  echo "Copiá .env.example → .env y completá PAGOPAR_* y PUBLIC_BASE_URL"
  exit 1
fi

python3 -m venv venv
./venv/bin/pip install -q -U pip
./venv/bin/pip install -q -r requirements.txt

chown -R www-data:www-data "$BACKEND" /var/log/zuppon 2>/dev/null || true

systemctl restart zuppon
systemctl status zuppon --no-pager || true

echo ""
echo "Deploy OK. Probar:"
echo "  curl -s http://127.0.0.1:5000/health | python3 -m json.tool"
echo "  curl -s https://institutocaacupepy.es/health | python3 -m json.tool"
