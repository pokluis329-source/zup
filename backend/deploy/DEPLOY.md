# Deploy Zuppon en institutocaacupepy.es

Guía para publicar el backend con **Nginx + Gunicorn + SSL (Let's Encrypt)** y conectar Pagopar.

## Requisitos

- VPS Ubuntu/Debian con dominio apuntando al servidor (`institutocaacupepy.es` → IP del VPS)
- Puertos **80** y **443** abiertos
- Acceso SSH como root o sudo

## 1. Subir el código al servidor

Desde tu PC con **Git** en el servidor (recomendado):

```bash
# En el VPS — primera vez
git clone https://github.com/TU_USUARIO/zup.git /var/www/zuppon
```

Desde Windows con **OpenSSH** (PowerShell):

```powershell
cd backend\deploy
.\push-from-windows.ps1 -Server root@TU_IP_DEL_VPS
```

(Necesitás `tar` y `scp` — vienen con Windows 10+ / OpenSSH.)

## 2. Instalación inicial (una sola vez)

En el VPS:

```bash
cd /var/www/zuppon/backend/deploy
chmod +x setup-vps.sh update.sh
sudo bash setup-vps.sh
```

## 3. Configurar `.env` en el servidor

```bash
sudo nano /var/www/zuppon/backend/.env
```

Contenido mínimo:

```env
SECRET_KEY=genera-un-secreto-largo-aqui
DATABASE_URL=sqlite:////var/www/zuppon/backend/zuppon.db

PAGOPAR_PUBLIC_KEY=tu_token_publico
PAGOPAR_PRIVATE_KEY=tu_token_privado
PAGOPAR_API_URL=https://api.pagopar.com
PAGOPAR_CHECKOUT_URL=https://www.pagopar.com/pagos
PAGOPAR_CITY_ID=1
PAGOPAR_CATEGORY_ID=909
GS_RATE=7300

PUBLIC_BASE_URL=https://institutocaacupepy.es
```

Permisos:

```bash
sudo chown www-data:www-data /var/www/zuppon/backend/.env
sudo chmod 600 /var/www/zuppon/backend/.env
```

## 4. Desplegar / actualizar

```bash
sudo bash /var/www/zuppon/backend/deploy/update.sh
```

## 5. SSL con Let's Encrypt

```bash
sudo certbot --nginx -d institutocaacupepy.es -d www.institutocaacupepy.es
```

Certbot configura HTTPS y el redirect HTTP → HTTPS.

Verificar:

```bash
curl https://institutocaacupepy.es/health
```

Deberías ver `"pagopar_configured": true` y la URL del webhook.

## 6. Webhook Pagopar

En el panel [Pagopar/upay](https://www.pagopar.com/), configurá:

```
https://institutocaacupepy.es/api/payments/pagopar/webhook
```

## 7. App Android

La app ya apunta a:

```
https://institutocaacupepy.es/
```

Recompilá e instalá el APK después de que SSL esté activo.

## Comandos útiles

```bash
# Logs del backend
sudo tail -f /var/log/zuppon/error.log

# Reiniciar
sudo systemctl restart zuppon

# Estado
sudo systemctl status zuppon
curl https://institutocaacupepy.es/health
```

## URLs en producción

| Servicio | URL |
|----------|-----|
| Dashboard | https://institutocaacupepy.es/dashboard |
| Menú admin | https://institutocaacupepy.es/menu |
| API | https://institutocaacupepy.es/api/... |
| Health | https://institutocaacupepy.es/health |
| Webhook Pagopar | https://institutocaacupepy.es/api/payments/pagopar/webhook |

## Notas

- **SQLite** en producción funciona para pruebas; para mucho tráfico conviene PostgreSQL (`DATABASE_URL=postgresql://...`).
- Renovar SSL: `certbot renew` (cron automático con certbot).
- Si Pagopar falla al crear checkout, revisá logs: `journalctl -u zuppon -n 50`.
