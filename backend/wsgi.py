"""Punto de entrada WSGI para Gunicorn (Socket.IO en modo threading)."""
from app import app, socketio  # noqa: F401
