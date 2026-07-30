"""
Zuppon Backend — Flask REST API
Maneja pedidos, repartidores y el ciclo completo de delivery.
"""

from dotenv import load_dotenv

load_dotenv()

import os
import uuid
from datetime import datetime

from flask import Flask, jsonify, request, render_template, send_from_directory, make_response
from flask_socketio import SocketIO, emit, join_room
from sqlalchemy import inspect, text, func

from database import db, Order, Driver, MenuItem, PaymentMessage, User, MENU_SEED
import payment_config
import auth

UPLOAD_DIR = os.path.join(os.path.dirname(__file__), "uploads", "receipts")
MENU_UPLOAD_DIR = os.path.join(os.path.dirname(__file__), "uploads", "menu")
ALLOWED_RECEIPT_EXT = {"jpg", "jpeg", "png", "webp", "gif"}


def _detect_image_ext(header: bytes, filename: str, content_type: str | None) -> str | None:
    if header.startswith(b"\xff\xd8\xff"):
        return "jpg"
    if header.startswith(b"\x89PNG\r\n\x1a\n"):
        return "png"
    if header[:4] == b"RIFF" and header[8:12] == b"WEBP":
        return "webp"
    if header.startswith(b"GIF87a") or header.startswith(b"GIF89a"):
        return "gif"

    ct = (content_type or "").lower()
    if "jpeg" in ct or "jpg" in ct:
        return "jpg"
    if "png" in ct:
        return "png"
    if "webp" in ct:
        return "webp"

    if filename and "." in filename:
        ext = filename.rsplit(".", 1)[-1].lower()
        if ext == "jpeg":
            ext = "jpg"
        if ext in ALLOWED_RECEIPT_EXT:
            return ext
    return None


def _ensure_order_columns():
    """Migraciones ligeras para columnas nuevas en orders."""
    inspector = inspect(db.engine)
    if "orders" not in inspector.get_table_names():
        return
    existing = {col["name"] for col in inspector.get_columns("orders")}
    alters = []
    if "dest_lat" not in existing:
        alters.append("ALTER TABLE orders ADD COLUMN dest_lat FLOAT DEFAULT 0.0")
    if "dest_lng" not in existing:
        alters.append("ALTER TABLE orders ADD COLUMN dest_lng FLOAT DEFAULT 0.0")
    if "amount_gs" not in existing:
        alters.append("ALTER TABLE orders ADD COLUMN amount_gs INTEGER DEFAULT 0")
    if "payment_status" not in existing:
        alters.append("ALTER TABLE orders ADD COLUMN payment_status VARCHAR(24) DEFAULT 'PAID'")
    if "paid_at" not in existing:
        alters.append("ALTER TABLE orders ADD COLUMN paid_at DATETIME")
    if "user_id" not in existing:
        alters.append("ALTER TABLE orders ADD COLUMN user_id INTEGER")
    if "client_phone" not in existing:
        alters.append("ALTER TABLE orders ADD COLUMN client_phone VARCHAR(32) DEFAULT ''")
    if not alters:
        return
    with db.engine.begin() as conn:
        for stmt in alters:
            conn.execute(text(stmt))


def _ensure_user_columns():
    """Migraciones ligeras para columnas nuevas en users."""
    inspector = inspect(db.engine)
    if "users" not in inspector.get_table_names():
        return
    existing = {col["name"] for col in inspector.get_columns("users")}
    if "is_driver" not in existing:
        with db.engine.begin() as conn:
            conn.execute(text("ALTER TABLE users ADD COLUMN is_driver BOOLEAN DEFAULT 0"))


def _public_base_url() -> str:
    return os.environ.get("PUBLIC_BASE_URL", "").rstrip("/")


def _menu_image_url(asset_image: str) -> str | None:
    if not asset_image or not asset_image.startswith("menu/"):
        return None
    path = f"/uploads/{asset_image}"
    base = _public_base_url()
    return f"{base}{path}" if base else path


def _menu_item_dict(item: MenuItem) -> dict:
    data = item.to_dict()
    data["image_url"] = _menu_image_url(item.asset_image)
    return data


def _payment_welcome_message(amount_gs: int) -> str:
    info = payment_config.payment_info(amount_gs)
    gs_txt = f"{amount_gs:,}".replace(",", ".")
    return (
        f"Transferí Gs {gs_txt} al alias {info['alias']} "
        f"(CI {info['cedula']}).\n"
        f"Después enviá acá la foto del comprobante 📸"
    )


def _payment_ready(order) -> bool:
    return order.payment_status in ("PAID", "CASH_ON_DELIVERY")


def mark_order_paid(order: Order, notify_drivers: bool = True):
    """Marca un pedido como pagado y lo publica a repartidores."""
    if order.payment_status == "PAID":
        return order
    order.payment_status = "PAID"
    order.paid_at = datetime.utcnow()
    db.session.commit()
    if notify_drivers and order.status == "PENDING":
        socketio.emit("new_order", order.to_dict(), room="drivers")
    return order


def _seed_payment_chat(order: Order):
    msg = PaymentMessage(
        order_id=order.id,
        sender="system",
        msg_type="system",
        body=_payment_welcome_message(order.amount_gs),
    )
    db.session.add(msg)


def _client_label(user, fallback: str = "Cliente") -> str:
    if not user:
        return fallback
    if user.username:
        return f"@{user.username}"
    if user.display_name:
        return user.display_name
    if user.email:
        return user.email.split("@")[0]
    return fallback


def _can_access_order(order: Order, user_id: int | None) -> bool:
    if order.user_id is None:
        return True
    if user_id is None:
        return False
    return order.user_id == user_id


app = Flask(__name__)
app.config["SECRET_KEY"] = os.environ.get("SECRET_KEY", "zuppon-dev-secret")
app.config["SQLALCHEMY_DATABASE_URI"] = os.environ.get(
    "DATABASE_URL", "sqlite:///zuppon.db"
)
app.config["SQLALCHEMY_TRACK_MODIFICATIONS"] = False
app.config["MAX_CONTENT_LENGTH"] = 8 * 1024 * 1024

db.init_app(app)
socketio = SocketIO(app, cors_allowed_origins="*", async_mode="threading")

os.makedirs(UPLOAD_DIR, exist_ok=True)
os.makedirs(MENU_UPLOAD_DIR, exist_ok=True)


def _insert_menu_seed():
    for row in MENU_SEED:
        db.session.add(MenuItem(
            id=row[0], name=row[1], description=row[2], price=row[3],
            emoji=row[4], category=row[5], is_popular=row[6], asset_image=row[7],
        ))
    db.session.commit()


def _replace_menu_with_seed():
    MenuItem.query.delete()
    db.session.commit()
    _insert_menu_seed()


def _upgrade_legacy_menu_catalog():
    """Reemplaza el menú demo antiguo (pizzas, tacos, etc.) por el catálogo actual."""
    if MenuItem.query.filter(MenuItem.name.ilike("%Pizza%")).first():
        _replace_menu_with_seed()
        return
    if MenuItem.query.count() > len(MENU_SEED):
        _replace_menu_with_seed()


with app.app_context():
    db.create_all()
    _ensure_order_columns()
    _ensure_user_columns()
    if MenuItem.query.count() == 0:
        _insert_menu_seed()
    else:
        _upgrade_legacy_menu_catalog()


# ═════════════════════════════════════════════════════════════════════════════
#  REST — Auth (Google + username)
# ═════════════════════════════════════════════════════════════════════════════

@app.route("/api/v1/auth/google", methods=["POST"])
def auth_google():
    data = request.get_json() or {}
    raw_token = (data.get("id_token") or "").strip()
    if not raw_token:
        return jsonify({"error": "Falta id_token"}), 400

    try:
        claims = auth.verify_google_id_token(raw_token)
    except ValueError as exc:
        return jsonify({"error": str(exc)}), 401

    google_id = claims.get("sub")
    if not google_id:
        return jsonify({"error": "Token de Google inválido"}), 401

    user = User.query.filter_by(google_id=google_id).first()
    if not user:
        user = User(
            google_id=google_id,
            email=claims.get("email"),
            display_name=claims.get("name"),
        )
        db.session.add(user)
        db.session.commit()

    token = auth.create_access_token(user.id)
    return jsonify({"token": token, "user": user.to_dict()})


@app.route("/api/v1/auth/me", methods=["GET"])
@auth.require_auth
def auth_me(user):
    return jsonify({"user": user.to_dict()})


@app.route("/api/v1/users/username", methods=["POST"])
@auth.require_auth
def set_username(user):
    if user.username:
        return jsonify({"error": "Ya tenés username"}), 409

    data = request.get_json() or {}
    username = auth.validate_username(data.get("username", ""))
    if not username:
        return jsonify({
            "error": "Username inválido (3–30 caracteres, solo a-z, 0-9, _)"
        }), 400

    if User.query.filter_by(username=username).first():
        return jsonify({"error": "Ese username ya está en uso"}), 409

    user.username = username
    db.session.commit()
    return jsonify({"user": user.to_dict()})


@app.route("/api/v1/users/me/orders", methods=["GET"])
@auth.require_auth
def my_orders(user):
    """Pedidos del usuario autenticado."""
    q = Order.query.filter_by(user_id=user.id).order_by(Order.created_at.desc())
    if request.args.get("active") == "1":
        q = q.filter(Order.status.notin_(["COMPLETED", "CANCELLED"]))
    limit = min(request.args.get("limit", 50, type=int), 100)
    orders = q.limit(limit).all()
    return jsonify([o.to_dict() for o in orders])


@app.route("/api/admin/users", methods=["GET"])
def admin_users():
    """Lista usuarios registrados (dashboard admin)."""
    order_counts = dict(
        db.session.query(Order.user_id, func.count(Order.id))
        .filter(Order.user_id.isnot(None))
        .group_by(Order.user_id)
        .all()
    )
    users = User.query.order_by(User.created_at.desc()).all()
    payload = [
        u.to_admin_dict(orders_count=order_counts.get(u.id, 0))
        for u in users
    ]
    return jsonify({"total": len(users), "users": payload})


@app.route("/api/admin/users/<int:user_id>/driver", methods=["POST"])
def admin_set_driver(user_id):
    """Designar o quitar acceso al panel de repartidor."""
    user = User.query.get_or_404(user_id)
    data = request.get_json() or {}
    user.is_driver = bool(data.get("is_driver", False))
    db.session.commit()

    orders_count = Order.query.filter_by(user_id=user.id).count()
    return jsonify({"user": user.to_admin_dict(orders_count=orders_count)})


# ═════════════════════════════════════════════════════════════════════════════
#  REST — Pedidos
# ═════════════════════════════════════════════════════════════════════════════

@app.route("/api/orders", methods=["GET"])
def get_orders():
    orders = Order.query.order_by(Order.created_at.desc()).all()
    return jsonify([o.to_dict() for o in orders])


@app.route("/api/orders/<int:order_id>", methods=["GET"])
def get_order(order_id):
    order = Order.query.get_or_404(order_id)
    user_id = auth.bearer_user_id()
    if not _can_access_order(order, user_id):
        return jsonify({"error": "No autorizado"}), 403
    return jsonify(order.to_dict())


@app.route("/api/orders", methods=["POST"])
def create_order():
    """Cliente crea un nuevo pedido (transferencia o efectivo al entregar)."""
    data = request.get_json() or {}
    required = ["items", "destination", "fare"]
    if not all(k in data for k in required):
        return jsonify({"error": f"Faltan campos: {required}"}), 400

    user_id = auth.bearer_user_id()
    user = db.session.get(User, user_id) if user_id else None
    client_name = _client_label(user, data.get("client_name", "Cliente"))
    client_phone = (data.get("client_phone") or "").strip()

    payment_method = (data.get("payment_method") or "transfer").strip().lower()
    payment_status = "CASH_ON_DELIVERY" if payment_method == "cash" else "AWAITING_PAYMENT"

    amount_gs = payment_config.usd_to_gs(float(data["fare"]))
    order = Order(
        items=data["items"],
        destination=data["destination"],
        dest_lat=float(data.get("dest_lat", 0.0) or 0.0),
        dest_lng=float(data.get("dest_lng", 0.0) or 0.0),
        fare=float(data["fare"]),
        client_name=client_name,
        client_phone=client_phone,
        user_id=user.id if user else None,
        amount_gs=amount_gs,
        payment_status=payment_status,
        status="PENDING",
    )
    db.session.add(order)
    db.session.commit()
    if payment_status == "AWAITING_PAYMENT":
        _seed_payment_chat(order)
        db.session.commit()
    else:
        socketio.emit("new_order", order.to_dict(), room="drivers")

    payload = order.to_dict()
    payload["payment"] = payment_config.payment_info(amount_gs)
    return jsonify(payload), 201


@app.route("/api/payment-info", methods=["GET"])
def get_payment_info():
    amount = request.args.get("amount_gs", type=int)
    return jsonify(payment_config.payment_info(amount))


@app.route("/api/orders/<int:order_id>/payment-status", methods=["GET"])
def payment_status(order_id):
    order = Order.query.get_or_404(order_id)
    return jsonify({
        "order_id": order.id,
        "payment_status": order.payment_status,
        "paid": _payment_ready(order),
        "payment": payment_config.payment_info(order.amount_gs),
    })


@app.route("/api/orders/<int:order_id>/messages", methods=["GET"])
def get_payment_messages(order_id):
    Order.query.get_or_404(order_id)
    base = _public_base_url()
    msgs = (
        PaymentMessage.query.filter_by(order_id=order_id)
        .order_by(PaymentMessage.created_at.asc())
        .all()
    )
    return jsonify([m.to_dict(base) for m in msgs])


@app.route("/api/orders/<int:order_id>/messages", methods=["POST"])
def post_payment_message(order_id):
    order = Order.query.get_or_404(order_id)
    data = request.get_json() or {}
    body = (data.get("body") or "").strip()
    if not body:
        return jsonify({"error": "Mensaje vacío"}), 400
    msg = PaymentMessage(
        order_id=order.id,
        sender=data.get("sender", "client"),
        msg_type="text",
        body=body,
    )
    db.session.add(msg)
    db.session.commit()
    return jsonify(msg.to_dict(_public_base_url())), 201


@app.route("/api/orders/<int:order_id>/messages/receipt", methods=["POST"])
def upload_receipt(order_id):
    order = Order.query.get_or_404(order_id)
    if _payment_ready(order):
        return jsonify({"error": "El pedido ya está pagado"}), 409

    file = request.files.get("image") or request.files.get("file")
    if not file:
        return jsonify({"error": "Falta la imagen del comprobante"}), 400

    header = file.stream.read(12)
    file.stream.seek(0)
    ext = _detect_image_ext(header, file.filename, file.content_type)
    if not ext:
        return jsonify({"error": "Formato no permitido (jpg, png, webp)"}), 400

    filename = f"{order_id}_{uuid.uuid4().hex[:12]}.{ext}"
    dest = os.path.join(UPLOAD_DIR, filename)
    try:
        file.save(dest)
    except OSError as exc:
        return jsonify({"error": f"No se pudo guardar la imagen: {exc}"}), 500

    msg = PaymentMessage(
        order_id=order.id,
        sender="client",
        msg_type="image",
        body="Comprobante de transferencia",
        image_path=filename,
    )
    db.session.add(msg)
    order.payment_status = "PENDING_REVIEW"
    db.session.commit()

    admin_msg = PaymentMessage(
        order_id=order.id,
        sender="system",
        msg_type="system",
        body="Recibimos tu comprobante ✅ Te confirmamos cuando verifiquemos el pago.",
    )
    db.session.add(admin_msg)
    db.session.commit()

    return jsonify(msg.to_dict(_public_base_url())), 201


@app.route("/api/orders/<int:order_id>/approve-payment", methods=["POST"])
def approve_payment(order_id):
    """Dashboard / admin confirma que llegó la transferencia."""
    order = Order.query.get_or_404(order_id)
    if order.payment_status == "PAID":
        return jsonify(order.to_dict())

    mark_order_paid(order)
    msg = PaymentMessage(
        order_id=order.id,
        sender="admin",
        msg_type="system",
        body="Pago confirmado ✅ Buscando repartidor…",
    )
    db.session.add(msg)
    db.session.commit()
    socketio.emit("payment_approved", order.to_dict(), room=f"order_{order.id}")
    return jsonify(order.to_dict())


@app.route("/uploads/receipts/<path:filename>")
def serve_receipt(filename):
    return send_from_directory(UPLOAD_DIR, filename)


@app.route("/uploads/menu/<path:filename>")
def serve_menu_image(filename):
    return send_from_directory(MENU_UPLOAD_DIR, filename)


@app.route("/api/orders/<int:order_id>/accept", methods=["POST"])
def accept_order(order_id):
    order = Order.query.get_or_404(order_id)
    data = request.get_json() or {}

    if order.status != "PENDING":
        return jsonify({"error": "El pedido no está disponible"}), 409
    if not _payment_ready(order):
        return jsonify({"error": "El pedido aún no fue pagado"}), 409

    order.status = "ACCEPTED"
    order.driver_id = data.get("driver_id")
    order.driver_name = data.get("driver_name", "Repartidor")
    order.driver_vehicle = data.get("driver_vehicle", "Moto")
    order.accepted_at = datetime.utcnow()
    db.session.commit()

    socketio.emit("order_updated", order.to_dict())
    return jsonify(order.to_dict())


@app.route("/api/orders/<int:order_id>/picked_up", methods=["POST"])
def picked_up(order_id):
    order = Order.query.get_or_404(order_id)
    order.status = "PICKED_UP"
    order.picked_up_at = datetime.utcnow()
    db.session.commit()
    socketio.emit("order_updated", order.to_dict())
    return jsonify(order.to_dict())


@app.route("/api/orders/<int:order_id>/delivering", methods=["POST"])
def delivering(order_id):
    order = Order.query.get_or_404(order_id)
    order.status = "DELIVERING"
    db.session.commit()
    socketio.emit("order_updated", order.to_dict())
    return jsonify(order.to_dict())


@app.route("/api/orders/<int:order_id>/complete", methods=["POST"])
def complete_order(order_id):
    order = Order.query.get_or_404(order_id)
    order.status = "COMPLETED"
    order.completed_at = datetime.utcnow()
    db.session.commit()
    socketio.emit("order_updated", order.to_dict())
    return jsonify(order.to_dict())


@app.route("/api/orders/<int:order_id>/cancel", methods=["POST"])
def cancel_order(order_id):
    order = Order.query.get_or_404(order_id)
    if order.status in ("COMPLETED", "CANCELLED"):
        return jsonify({"error": "No se puede cancelar"}), 409
    order.status = "CANCELLED"
    order.payment_status = "CANCELLED"
    db.session.commit()
    socketio.emit("order_updated", order.to_dict())
    return jsonify(order.to_dict())


@app.route("/api/orders/<int:order_id>", methods=["DELETE"])
def delete_order(order_id):
    order = Order.query.get_or_404(order_id)
    order_data = order.to_dict()
    for msg in PaymentMessage.query.filter_by(order_id=order_id).all():
        if msg.image_path:
            path = os.path.join(UPLOAD_DIR, msg.image_path)
            if os.path.isfile(path):
                os.remove(path)
        db.session.delete(msg)
    db.session.delete(order)
    db.session.commit()
    socketio.emit("order_deleted", {"id": order_id, "order": order_data})
    return jsonify({"ok": True, "deleted_id": order_id})


# ═════════════════════════════════════════════════════════════════════════════
#  REST — Repartidores
# ═════════════════════════════════════════════════════════════════════════════

@app.route("/api/drivers", methods=["GET"])
def get_drivers():
    drivers = Driver.query.filter_by(is_online=True).all()
    return jsonify([d.to_dict() for d in drivers])


@app.route("/api/drivers/register", methods=["POST"])
def register_driver():
    data = request.get_json()
    driver = Driver.query.filter_by(device_id=data.get("device_id")).first()

    if not driver:
        driver = Driver(
            device_id=data.get("device_id", "unknown"),
            name=data.get("name", "Repartidor"),
            vehicle=data.get("vehicle", "Moto"),
            plate=data.get("plate", ""),
            rating=5.0,
        )
        db.session.add(driver)

    driver.is_online = data.get("is_online", True)
    driver.lat = data.get("lat")
    driver.lng = data.get("lng")
    driver.updated_at = datetime.utcnow()
    db.session.commit()

    return jsonify(driver.to_dict()), 200


@app.route("/api/drivers/<int:driver_id>/location", methods=["POST"])
def update_location(driver_id):
    driver = Driver.query.get_or_404(driver_id)
    data = request.get_json()
    driver.lat = data["lat"]
    driver.lng = data["lng"]
    driver.updated_at = datetime.utcnow()
    db.session.commit()

    active = Order.query.filter_by(
        driver_id=driver_id, status="DELIVERING"
    ).first() or Order.query.filter_by(
        driver_id=driver_id, status="ACCEPTED"
    ).first()

    if active:
        socketio.emit("driver_location", {
            "order_id": active.id,
            "driver_id": driver_id,
            "lat": driver.lat,
            "lng": driver.lng,
        })

    return jsonify({"ok": True})


# ═════════════════════════════════════════════════════════════════════════════
#  WebSocket
# ═════════════════════════════════════════════════════════════════════════════

@socketio.on("join_drivers")
def on_join_drivers():
    join_room("drivers")
    emit("joined", {"room": "drivers"})


@socketio.on("join_order")
def on_join_order(data):
    order_id = data.get("order_id")
    if order_id:
        join_room(f"order_{order_id}")
        emit("joined", {"room": f"order_{order_id}"})


# ═════════════════════════════════════════════════════════════════════════════
#  Llamadas de voz in-app (señalización WebRTC)
# ═════════════════════════════════════════════════════════════════════════════

_call_signals: dict[int, list] = {}


def _prune_call_signals(order_id: int, max_items: int = 80):
    bucket = _call_signals.get(order_id)
    if bucket and len(bucket) > max_items:
        _call_signals[order_id] = bucket[-max_items:]


@app.route("/api/v1/orders/<int:order_id>/call/signal", methods=["POST"])
def post_call_signal(order_id):
    """Recibe offer/answer/ice/ring/hangup y lo guarda para el otro participante."""
    data = request.get_json() or {}
    signal_type = (data.get("type") or "").strip()
    sender = (data.get("from") or "").strip()
    if signal_type not in ("ring", "offer", "answer", "ice", "hangup", "reject"):
        return jsonify({"error": "type inválido"}), 400
    if sender not in ("client", "driver"):
        return jsonify({"error": "from inválido"}), 400

    payload = {
        "id": str(uuid.uuid4()),
        "ts": datetime.utcnow().timestamp(),
        "type": signal_type,
        "from": sender,
        "sdp": data.get("sdp"),
        "candidate": data.get("candidate"),
        "sdp_mid": data.get("sdp_mid"),
        "sdp_mline_index": data.get("sdp_mline_index"),
    }
    _call_signals.setdefault(order_id, []).append(payload)
    _prune_call_signals(order_id)
    return jsonify({"ok": True, "id": payload["id"], "ts": payload["ts"]})


@app.route("/api/v1/orders/<int:order_id>/call/signal", methods=["GET"])
def get_call_signals(order_id):
    """Polling de señales WebRTC desde `since` (timestamp unix)."""
    since = request.args.get("since", 0, type=float)
    bucket = _call_signals.get(order_id, [])
    out = [s for s in bucket if s.get("ts", 0) > since]
    return jsonify(out)


# ═════════════════════════════════════════════════════════════════════════════
#  Health / Dashboard / Menú
# ═════════════════════════════════════════════════════════════════════════════

@app.route("/health")
def health():
    return jsonify({
        "status": "ok",
        "app": "Zuppon Backend",
        "payment": payment_config.payment_info(),
        "google_auth": bool(auth.google_client_id()),
    })


@app.route("/")
@app.route("/dashboard")
def dashboard():
    return render_template("dashboard.html")


@app.route("/api/menu", methods=["GET"])
def get_menu():
    items = MenuItem.query.order_by(MenuItem.category, MenuItem.id).all()
    return jsonify([_menu_item_dict(i) for i in items])


def _store_menu_upload(file) -> tuple[str | None, str | None]:
    """Guarda imagen de producto. Devuelve (asset_image, error)."""
    if not file or not file.filename:
        return None, "Falta el archivo de imagen"

    header = file.stream.read(12)
    file.stream.seek(0)
    ext = _detect_image_ext(header, file.filename, file.content_type)
    if not ext:
        return None, "Formato no permitido (jpg, png, webp, gif)"

    filename = f"{uuid.uuid4().hex[:16]}.{ext}"
    dest = os.path.join(MENU_UPLOAD_DIR, filename)
    try:
        file.save(dest)
    except OSError as exc:
        return None, f"No se pudo guardar la imagen: {exc}"

    return f"menu/{filename}", None


def _form_bool(val) -> bool:
    if isinstance(val, bool):
        return val
    if val is None:
        return False
    return str(val).strip().lower() in ("1", "true", "yes", "on")


def _menu_payload_from_request() -> tuple[dict | None, str | None]:
    """JSON o multipart/form-data (campo image opcional)."""
    uploaded_file = request.files.get("image") or request.files.get("file")
    uploaded_asset, upload_err = (None, None)
    if uploaded_file and uploaded_file.filename:
        uploaded_asset, upload_err = _store_menu_upload(uploaded_file)
        if upload_err:
            return None, upload_err

    if request.form:
        data = request.form.to_dict()
        if "price" in data and data["price"] != "":
            data["price"] = float(data["price"])
        if "is_popular" in data:
            data["is_popular"] = _form_bool(data["is_popular"])
        if "is_active" in data:
            data["is_active"] = _form_bool(data["is_active"])
    else:
        data = request.get_json(silent=True) or {}

    if uploaded_asset:
        data["asset_image"] = uploaded_asset
    return data, None


@app.route("/api/menu/upload-image", methods=["POST"])
def upload_menu_image():
    """Sube una foto del producto desde el editor web."""
    file = request.files.get("image") or request.files.get("file")
    asset_image, err = _store_menu_upload(file)
    if err:
        return jsonify({"error": err}), 400
    return jsonify({
        "asset_image": asset_image,
        "image_url": _menu_image_url(asset_image),
    }), 201


@app.route("/api/menu", methods=["POST"])
def create_menu_item():
    data, err = _menu_payload_from_request()
    if err:
        return jsonify({"error": err}), 400
    if not data or not data.get("name") or data.get("price") is None:
        return jsonify({"error": "name y price son obligatorios"}), 400
    item = MenuItem(
        name        = data["name"],
        description = data.get("description", ""),
        price       = float(data["price"]),
        emoji       = data.get("emoji", "🍔"),
        category    = data.get("category", "🍔 Hamburguesas"),
        is_popular  = bool(data.get("is_popular", False)),
        asset_image = data.get("asset_image", ""),
        is_active   = bool(data.get("is_active", True)),
    )
    db.session.add(item)
    db.session.commit()
    return jsonify(_menu_item_dict(item)), 201


@app.route("/api/menu/<int:item_id>", methods=["PUT"])
def update_menu_item(item_id):
    item = MenuItem.query.get_or_404(item_id)
    data, err = _menu_payload_from_request()
    if err:
        return jsonify({"error": err}), 400
    if not data:
        return jsonify({"error": "Sin datos"}), 400
    if "name"        in data: item.name        = data["name"]
    if "description" in data: item.description = data["description"]
    if "price"       in data: item.price       = float(data["price"])
    if "emoji"       in data: item.emoji       = data["emoji"]
    if "category"    in data: item.category    = data["category"]
    if "is_popular"  in data: item.is_popular  = bool(data["is_popular"])
    if "asset_image" in data: item.asset_image = data["asset_image"]
    if "is_active"   in data: item.is_active   = bool(data["is_active"])
    item.updated_at = datetime.utcnow()
    db.session.commit()
    return jsonify(_menu_item_dict(item))


@app.route("/api/menu/<int:item_id>", methods=["DELETE"])
def delete_menu_item(item_id):
    item = MenuItem.query.get_or_404(item_id)
    db.session.delete(item)
    db.session.commit()
    return jsonify({"ok": True})


@app.route("/menu")
def menu_editor():
    resp = make_response(render_template("menu.html"))
    resp.headers["Cache-Control"] = "no-store, no-cache, must-revalidate"
    return resp


if __name__ == "__main__":
    socketio.run(app, host="0.0.0.0", port=5000, debug=True)
