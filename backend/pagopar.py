"""
Cliente Pagopar — iniciar transacción, consultar estado, validar webhook.
API 2.0: https://api.pagopar.com/api/comercios/2.0/iniciar-transaccion
"""

import hashlib
import os
from datetime import datetime, timedelta

import requests

PAGOPAR_API_URL = os.environ.get("PAGOPAR_API_URL", "https://api.pagopar.com")
PAGOPAR_CHECKOUT_URL = os.environ.get("PAGOPAR_CHECKOUT_URL", "https://www.pagopar.com/pagos")
PUBLIC_KEY = os.environ.get("PAGOPAR_PUBLIC_KEY", "")
PRIVATE_KEY = os.environ.get("PAGOPAR_PRIVATE_KEY", "")
CITY_ID = int(os.environ.get("PAGOPAR_CITY_ID", "1"))
CATEGORY_ID = os.environ.get("PAGOPAR_CATEGORY_ID", "909")
GS_RATE = int(os.environ.get("GS_RATE", "7300"))


class PagoparError(Exception):
    pass


def configured() -> bool:
    return bool(PUBLIC_KEY and PRIVATE_KEY)


def usd_to_gs(usd: float) -> int:
    return max(1000, int(round(float(usd) * GS_RATE)))


def payment_token(order_id: int, amount_gs: int) -> str:
    raw = f"{PRIVATE_KEY}{order_id}{amount_gs}"
    return hashlib.sha1(raw.encode()).hexdigest()


def consult_token() -> str:
    return hashlib.sha1(f"{PRIVATE_KEY}CONSULTA".encode()).hexdigest()


def verify_webhook_token(hash_pedido: str, token: str) -> bool:
    expected = hashlib.sha1(f"{PRIVATE_KEY}{hash_pedido}".encode()).hexdigest()
    return expected == token


def create_transaction(order, buyer: dict) -> dict:
    if not configured():
        raise PagoparError("Pagopar no configurado (faltan PAGOPAR_PUBLIC_KEY / PAGOPAR_PRIVATE_KEY)")

    amount_gs = order.amount_gs or usd_to_gs(order.fare)
    token = payment_token(order.id, amount_gs)
    max_date = (datetime.utcnow() + timedelta(days=1)).strftime("%Y-%m-%d %H:%M:%S")

    coords = buyer.get("coordenadas") or f"{order.dest_lat or 0},{order.dest_lng or 0}"
    nombre = buyer.get("nombre") or order.client_name or "Cliente Zuppon"

    payload = {
        "token": token,
        "public_key": PUBLIC_KEY,
        "monto_total": amount_gs,
        "tipo_pedido": "VENTA-COMERCIO",
        "id_pedido_comercio": order.id,
        "descripcion_resumen": f"Pedido Zuppon #{order.id}",
        "fecha_maxima_pago": max_date,
        "comprador": {
            "nombre": nombre,
            "email": buyer["email"],
            "documento": buyer.get("documento", "0000000"),
            "telefono": buyer.get("telefono", "0991000000"),
            "direccion": buyer.get("direccion") or order.destination,
            "ciudad": int(buyer.get("ciudad", CITY_ID)),
            "ruc": buyer.get("documento", "0000000"),
            "coordenadas": coords,
            "razon_social": nombre,
            "tipo_documento": buyer.get("tipo_documento", "CI"),
            "direccion_referencia": buyer.get("direccion_referencia", ""),
        },
        "compras_items": [
            {
                "ciudad": int(buyer.get("ciudad", CITY_ID)),
                "nombre": (order.items or "Pedido Zuppon")[:100],
                "cantidad": 1,
                "categoria": CATEGORY_ID,
                "public_key": PUBLIC_KEY,
                "url_imagen": "",
                "descripcion": (order.items or "Pedido Zuppon")[:200],
                "id_producto": order.id,
                "precio_total": amount_gs,
                "vendedor_telefono": "",
                "vendedor_direccion": "",
                "vendedor_direccion_referencia": "",
                "vendedor_direccion_coordenadas": "",
            }
        ],
    }

    forma_pago = os.environ.get("PAGOPAR_FORMA_PAGO", "").strip()
    if forma_pago:
        payload["forma_pago"] = int(forma_pago)

    url = f"{PAGOPAR_API_URL}/api/comercios/2.0/iniciar-transaccion"
    resp = requests.post(url, json=payload, timeout=30)
    body = resp.json()

    if not body.get("respuesta"):
        detail = body.get("resultado") or body.get("mensaje") or body.get("errores") or resp.text
        raise PagoparError(str(detail))

    resultado = body.get("resultado") or []
    if not resultado:
        raise PagoparError("Pagopar no devolvió hash de checkout")

    hash_val = resultado[0].get("data")
    if not hash_val:
        raise PagoparError("Hash de checkout vacío")

    return {
        "hash": hash_val,
        "checkout_url": f"{PAGOPAR_CHECKOUT_URL}/{hash_val}",
        "amount_gs": amount_gs,
    }


def get_order_status(hash_pedido: str) -> dict:
    if not configured() or not hash_pedido:
        return {}

    payload = {
        "token": consult_token(),
        "hash_pedido": hash_pedido,
        "token_publico": PUBLIC_KEY,
    }
    url = f"{PAGOPAR_API_URL}/api/pedidos/1.1/traer"
    resp = requests.post(url, json=payload, timeout=30)
    body = resp.json()
    if not body.get("respuesta"):
        return {}

    results = body.get("resultado") or []
    return results[0] if results else {}
