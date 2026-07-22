#!/bin/bash
# Crear .env de producción (editar tokens Pagopar después si hace falta)
set -euo pipefail

ENV_FILE="/var/www/zup/backend/.env"

if [ -f "$ENV_FILE" ]; then
  echo "Ya existe $ENV_FILE — no se sobrescribe."
  exit 0
fi

cat > "$ENV_FILE" << 'EOF'
SECRET_KEY=cambia-esto-por-un-secreto-largo-en-produccion
DATABASE_URL=sqlite:////var/www/zup/backend/zuppon.db

PAGOPAR_PUBLIC_KEY=a5dd82d9916476ba130d574eff463730
PAGOPAR_PRIVATE_KEY=44c2eb0358f009a6b2a1cf028c9129fb
PAGOPAR_API_URL=https://api.pagopar.com
PAGOPAR_CHECKOUT_URL=https://www.pagopar.com/pagos
PAGOPAR_CITY_ID=1
PAGOPAR_CATEGORY_ID=909
GS_RATE=7300

PUBLIC_BASE_URL=https://institutocaacupepy.es
EOF

chown www-data:www-data "$ENV_FILE"
chmod 600 "$ENV_FILE"
echo "Creado $ENV_FILE"
