"""Autenticación Google + JWT."""

import os
import re
from datetime import datetime, timedelta
from functools import wraps

import jwt
from flask import jsonify, request
from google.auth.transport import requests as google_requests
from google.oauth2 import id_token

USERNAME_RE = re.compile(r"^[a-z0-9_]{3,30}$")


def _secret() -> str:
    return os.environ.get("SECRET_KEY", "zuppon-dev-secret")


def google_client_id() -> str:
    return os.environ.get("GOOGLE_CLIENT_ID", "").strip()


def create_access_token(user_id: int) -> str:
    now = datetime.utcnow()
    payload = {
        "sub": user_id,
        "iat": now,
        "exp": now + timedelta(days=30),
    }
    token = jwt.encode(payload, _secret(), algorithm="HS256")
    if isinstance(token, bytes):
        token = token.decode("utf-8")
    return token


def decode_access_token(token: str) -> int | None:
    try:
        payload = jwt.decode(token, _secret(), algorithms=["HS256"])
        return int(payload["sub"])
    except (jwt.PyJWTError, TypeError, ValueError):
        return None


def verify_google_id_token(raw_token: str) -> dict:
    client_id = google_client_id()
    if not client_id:
        raise ValueError("GOOGLE_CLIENT_ID no configurado en el servidor")
    return id_token.verify_oauth2_token(
        raw_token, google_requests.Request(), client_id
    )


def validate_username(value: str) -> str | None:
    username = (value or "").strip().lower()
    if not USERNAME_RE.fullmatch(username):
        return None
    return username


def bearer_user_id():
    auth = request.headers.get("Authorization", "")
    if not auth.startswith("Bearer "):
        return None
    return decode_access_token(auth[7:].strip())


def require_auth(f):
    @wraps(f)
    def wrapper(*args, **kwargs):
        from database import User, db

        user_id = bearer_user_id()
        if not user_id:
            return jsonify({"error": "Token inválido o expirado"}), 401
        user = db.session.get(User, user_id)
        if not user:
            return jsonify({"error": "Usuario no encontrado"}), 401
        return f(user, *args, **kwargs)

    return wrapper
