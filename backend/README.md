# Zuppon Backend

API REST + WebSocket en Flask para la app de delivery Zuppon.

## Instalación

```bash
cd backend
python -m venv venv
venv\Scripts\activate        # Windows
pip install -r requirements.txt
```

## Arrancar

```bash
python app.py
```
Corre en http://localhost:5000

## Producción (institutocaacupepy.es)

Ver guía completa: [deploy/DEPLOY.md](deploy/DEPLOY.md)

Resumen:

```bash
# En el VPS
sudo bash /var/www/zup/backend/deploy/setup-vps.sh   # primera vez
sudo bash /var/www/zup/backend/deploy/update.sh      # cada actualización
sudo certbot --nginx -d institutocaacupepy.es -d www.institutocaacupepy.es
```

Pago por transferencia: configurá `PAYMENT_ALIAS` y `PAYMENT_CEDULA` en `.env`.
Confirmá pagos desde https://institutocaacupepy.es/dashboard

## Endpoints

### Pedidos
| Método | Ruta | Descripción |
|--------|------|-------------|
| GET  | /api/orders | Listar todos |
| GET  | /api/orders/:id | Ver uno |
| POST | /api/orders | Crear pedido (cliente) |
| GET | /api/orders/:id/payment-status | Estado del pago |
| GET | /api/orders/:id/messages | Chat de comprobante |
| POST | /api/orders/:id/messages/receipt | Subir foto comprobante |
| POST | /api/orders/:id/approve-payment | Confirmar pago (dashboard) |
| POST | /api/orders/:id/accept | Aceptar (repartidor) |
| POST | /api/orders/:id/picked_up | Recogido |
| POST | /api/orders/:id/delivering | En camino |
| POST | /api/orders/:id/complete | Entregado |
| POST | /api/orders/:id/cancel | Cancelar |
| DELETE | /api/orders/:id | Eliminar pedido |

### Repartidores
| Método | Ruta | Descripción |
|--------|------|-------------|
| GET  | /api/drivers | Repartidores online |
| POST | /api/drivers/register | Registrarse / conectarse |
| POST | /api/drivers/:id/location | Actualizar GPS |

### WebSocket (Socket.IO)
- `join_drivers` — repartidor se suscribe a pedidos nuevos
- `join_order` — cliente escucha updates de su pedido
- Eventos emitidos: `new_order`, `order_updated`, `driver_location`

## Crear pedido (ejemplo)

```bash
curl -X POST http://localhost:5000/api/orders \
  -H "Content-Type: application/json" \
  -d '{"items":"Burger x1","destination":"Av. Mariscal Lopez 123","fare":8.5}'
```
