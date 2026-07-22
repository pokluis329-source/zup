"""Datos de transferencia bancaria (alias / cédula)."""
import os

GS_RATE = int(os.environ.get("GS_RATE", "7300"))
PAYMENT_ALIAS = os.environ.get("PAYMENT_ALIAS", "zup.cacupe")
PAYMENT_CEDULA = os.environ.get("PAYMENT_CEDULA", "6208713")
PAYMENT_HOLDER = os.environ.get("PAYMENT_HOLDER", "Zup Delivery")


def usd_to_gs(usd: float) -> int:
    return max(1000, int(round(float(usd) * GS_RATE)))


def payment_info(amount_gs: int | None = None) -> dict:
    info = {
        "alias": PAYMENT_ALIAS,
        "cedula": PAYMENT_CEDULA,
        "holder": PAYMENT_HOLDER,
        "currency": "PYG",
    }
    if amount_gs is not None:
        info["amount_gs"] = amount_gs
    return info
