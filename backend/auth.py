"""Autenticación Google + JWT."""

import os
import re
import time
from datetime import timedelta
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
    now = int(time.time())
    payload = {
        "sub": str(user_id),
        "iat": now,
        "exp": now + int(timedelta(days=30).total_seconds()),
    }
    token = jwt.encode(payload, _secret(), algorithm="HS256")
    if isinstance(token, bytes):
        token = token.decode("utf-8")
    return token


def decode_access_token(token: str) -> int | None:
    token = (token or "").strip()
    if not token:
        return None
    try:
        payload = jwt.decode(
            token,
            _secret(),
            algorithms=["HS256"],
            leeway=30,
        )
        sub = payload.get("sub")
        if sub is None:
            return None
        return int(sub)
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


def extract_access_token() -> str | None:
    """Lee JWT desde header Authorization, X-Access-Token o body JSON."""
    auth = (request.headers.get("Authorization") or "").strip()
    if auth.lower().startswith("bearer "):
        token = auth[7:].strip()
        if token:
            return token

    x_token = (request.headers.get("X-Access-Token") or "").strip()
    if x_token:
        return x_token

    data = request.get_json(silent=True) or {}
    for key in ("access_token", "token"):
        val = data.get(key)
        if val:
            return str(val).strip()

    q = (request.args.get("access_token") or request.args.get("token") or "").strip()
    return q or None


def bearer_user_id() -> int | None:
    token = extract_access_token()
    if not token:
        return None
    return decode_access_token(token)


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
