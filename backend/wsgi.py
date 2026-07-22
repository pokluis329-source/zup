"""Punto de entrada WSGI para Gunicorn + Eventlet (Socket.IO)."""
from app import app, socketio  # noqa: F401
