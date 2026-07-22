#!/bin/bash
# Diagnóstico rápido de 502 Bad Gateway
set -uo pipefail

echo "=== systemctl status zup ==="
systemctl status zup --no-pager -l 2>&1 | head -20 || true

echo ""
echo "=== últimos logs zup ==="
journalctl -u zup -n 30 --no-pager 2>&1 || true

echo ""
echo "=== puerto 5003 ==="
ss -tlnp | grep 5003 || echo "Nada escuchando en 5003"

echo ""
echo "=== curl local ==="
curl -s -o /dev/null -w "HTTP %{http_code}\n" http://127.0.0.1:5003/health || echo "curl falló"

echo ""
echo "=== venv / gunicorn ==="
ls -la /var/www/zup/backend/venv/bin/gunicorn 2>&1 || echo "Falta venv o gunicorn"

echo ""
echo "=== .env ==="
test -f /var/www/zup/backend/.env && echo "OK .env existe" || echo "FALTA .env"

echo ""
echo "=== probar import (www-data) ==="
cd /var/www/zup/backend
sudo -u www-data ./venv/bin/python -c "from wsgi import app; print('import OK')" 2>&1 || true
