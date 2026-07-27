#!/bin/bash
# Crear .env de producción
set -euo pipefail

ENV_FILE="${1:-/var/www/zup/backend/.env}"

cat > "$ENV_FILE" <<'EOF'
SECRET_KEY=change-me-in-production
DATABASE_URL=sqlite:////var/www/zup/backend/zuppon.db
PUBLIC_BASE_URL=https://institutocaacupepy.es

PAYMENT_ALIAS=zup.cacupe
PAYMENT_CEDULA=6208713
PAYMENT_HOLDER=Zup Delivery
GS_RATE=7300

# Google Sign-In (Web Client ID)
GOOGLE_CLIENT_ID=820711440075-q4e0tqhg3vjf6d9bfgavo0od60hrbkd6.apps.googleusercontent.com
EOF

chmod 640 "$ENV_FILE"
echo "Creado $ENV_FILE — editá PAYMENT_ALIAS si hace falta"
