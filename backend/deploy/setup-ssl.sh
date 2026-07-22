#!/bin/bash
# SSL solo para el dominio principal (www no tiene DNS en este servidor)
set -euo pipefail
DOMAIN="institutocaacupepy.es"
certbot --nginx -d "$DOMAIN"
echo "OK. Probar: curl https://$DOMAIN/health"
