# Deploy Zuppon en institutocaacupepy.es

Guía para publicar el backend con **Nginx + Gunicorn + SSL (Let's Encrypt)** y pagos por transferencia.

## Requisitos

- VPS Ubuntu/Debian con dominio apuntando al servidor (`institutocaacupepy.es` → IP del VPS)
- Puertos **80** y **443** abiertos
- Acceso SSH como root o sudo

## 1. Subir el código al servidor

Desde tu PC con **Git** en el servidor (recomendado):

```bash
# En el VPS — primera vez
git clone https://github.com/TU_USUARIO/zup.git /var/www/zup
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
cd /var/www/zup/backend/deploy
chmod +x setup-vps.sh update.sh
sudo bash setup-vps.sh
```

## 3. Configurar `.env` en el servidor

```bash
sudo nano /var/www/zup/backend/.env
```

Contenido mínimo:

```env
SECRET_KEY=genera-un-secreto-largo-aqui
DATABASE_URL=sqlite:////var/www/zup/backend/zuppon.db

PAYMENT_ALIAS=tu.alias.bancario
PAYMENT_CEDULA=6208713
PAYMENT_HOLDER=Zup Delivery
GS_RATE=7300

PUBLIC_BASE_URL=https://institutocaacupepy.es

# Login Google (Web Client ID — mismo valor que google_web_client_id en Android)
GOOGLE_CLIENT_ID=820711440075-q4e0tqhg3vjf6d9bfgavo0od60hrbkd6.apps.googleusercontent.com
```

Permisos:

```bash
sudo chown www-data:www-data /var/www/zup/backend/.env
sudo chmod 600 /var/www/zup/backend/.env
sudo mkdir -p /var/www/zup/backend/uploads/receipts
sudo chown -R www-data:www-data /var/www/zup/backend/uploads
```

## Login Google — nginx (HTTPS)

Si al guardar username dice **"Token inválido o expirado"**, nginx suele estar
bloqueando el header `Authorization`. En el bloque **443** de nginx agregá:

```nginx
proxy_set_header Authorization $http_authorization;
```

```bash
sudo nginx -t && sudo systemctl reload nginx
sudo systemctl restart zup
```

La app también envía el token en el body (`access_token`) y en `X-Access-Token`
como respaldo, pero conviene arreglar nginx igual.

## 4. Desplegar / actualizar

```bash
sudo bash /var/www/zup/backend/deploy/update.sh
```

## 5. SSL con Let's Encrypt

```bash
sudo certbot --nginx -d institutocaacupepy.es
```

Certbot configura HTTPS y el redirect HTTP → HTTPS.

Verificar:

```bash
curl https://institutocaacupepy.es/health
```

Deberías ver `"payment": { "alias": "...", "cedula": "6208713" }`.

## 6. Confirmar pagos

Cuando un cliente sube el comprobante en la app, el pedido queda en **PENDING_REVIEW**.

Entrá al dashboard y tocá **Confirmar pago**:

```
https://institutocaacupepy.es/dashboard
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

## Notas

- **SQLite** en producción funciona para pruebas; para mucho tráfico conviene PostgreSQL (`DATABASE_URL=postgresql://...`).
- Renovar SSL: `certbot renew` (cron automático con certbot).
- Comprobantes guardados en `/var/www/zup/backend/uploads/receipts/`.
