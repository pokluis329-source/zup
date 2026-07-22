"""
Zuppon Backend — Flask REST API
Maneja pedidos, repartidores y el ciclo completo de delivery.
"""

from dotenv import load_dotenv

load_dotenv()

import os
import uuid
from datetime import datetime

from flask import Flask, jsonify, request, render_template, send_from_directory
from flask_socketio import SocketIO, emit, join_room
from sqlalchemy import inspect, text

from database import db, Order, Driver, MenuItem, PaymentMessage, MENU_SEED
import payment_config

UPLOAD_DIR = os.path.join(os.path.dirname(__file__), "uploads", "receipts")
ALLOWED_RECEIPT_EXT = {"jpg", "jpeg", "png", "webp", "gif"}


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
    if not alters:
        return
    with db.engine.begin() as conn:
        for stmt in alters:
            conn.execute(text(stmt))


def _public_base_url() -> str:
    return os.environ.get("PUBLIC_BASE_URL", "").rstrip("/")


def _payment_welcome_message(amount_gs: int) -> str:
    info = payment_config.payment_info(amount_gs)
    gs_txt = f"{amount_gs:,}".replace(",", ".")
    return (
        f"Transferí Gs {gs_txt} al alias {info['alias']} "
        f"(CI {info['cedula']}).\n"
        f"Después enviá acá la foto del comprobante 📸"
    )


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

with app.app_context():
    db.create_all()
    _ensure_order_columns()
    if MenuItem.query.count() == 0:
        for row in MENU_SEED:
            db.session.add(MenuItem(
                id=row[0], name=row[1], description=row[2], price=row[3],
                emoji=row[4], category=row[5], is_popular=row[6], asset_image=row[7]
            ))
        db.session.commit()


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
    return jsonify(order.to_dict())


@app.route("/api/orders", methods=["POST"])
def create_order():
    """Cliente crea un nuevo pedido (pago por transferencia)."""
    data = request.get_json() or {}
    required = ["items", "destination", "fare"]
    if not all(k in data for k in required):
        return jsonify({"error": f"Faltan campos: {required}"}), 400

    amount_gs = payment_config.usd_to_gs(float(data["fare"]))
    order = Order(
        items=data["items"],
        destination=data["destination"],
        dest_lat=float(data.get("dest_lat", 0.0) or 0.0),
        dest_lng=float(data.get("dest_lng", 0.0) or 0.0),
        fare=float(data["fare"]),
        client_name=data.get("client_name", "Cliente"),
        amount_gs=amount_gs,
        payment_status="AWAITING_PAYMENT",
        status="PENDING",
    )
    db.session.add(order)
    db.session.commit()
    _seed_payment_chat(order)
    db.session.commit()

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
        "paid": order.payment_status == "PAID",
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
    if order.payment_status == "PAID":
        return jsonify({"error": "El pedido ya está pagado"}), 409

    file = request.files.get("image") or request.files.get("file")
    if not file or not file.filename:
        return jsonify({"error": "Falta la imagen del comprobante"}), 400

    ext = file.filename.rsplit(".", 1)[-1].lower() if "." in file.filename else ""
    if ext not in ALLOWED_RECEIPT_EXT:
        return jsonify({"error": "Formato no permitido (jpg, png, webp)"}), 400

    filename = f"{order_id}_{uuid.uuid4().hex[:12]}.{ext}"
    file.save(os.path.join(UPLOAD_DIR, filename))

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


@app.route("/api/orders/<int:order_id>/accept", methods=["POST"])
def accept_order(order_id):
    order = Order.query.get_or_404(order_id)
    data = request.get_json() or {}

    if order.status != "PENDING":
        return jsonify({"error": "El pedido no está disponible"}), 409
    if order.payment_status != "PAID":
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
#  Health / Dashboard / Menú
# ═════════════════════════════════════════════════════════════════════════════

@app.route("/health")
def health():
    return jsonify({
        "status": "ok",
        "app": "Zuppon Backend",
        "payment": payment_config.payment_info(),
    })


@app.route("/")
@app.route("/dashboard")
def dashboard():
    return render_template("dashboard.html")


@app.route("/api/menu", methods=["GET"])
def get_menu():
    items = MenuItem.query.order_by(MenuItem.category, MenuItem.id).all()
    return jsonify([i.to_dict() for i in items])


@app.route("/api/menu", methods=["POST"])
def create_menu_item():
    data = request.get_json()
    if not data.get("name") or data.get("price") is None:
        return jsonify({"error": "name y price son obligatorios"}), 400
    item = MenuItem(
        name        = data["name"],
        description = data.get("description", ""),
        price       = float(data["price"]),
        emoji       = data.get("emoji", "🍔"),
        category    = data.get("category", "🔥 Populares"),
        is_popular  = bool(data.get("is_popular", False)),
        asset_image = data.get("asset_image", ""),
        is_active   = bool(data.get("is_active", True)),
    )
    db.session.add(item)
    db.session.commit()
    return jsonify(item.to_dict()), 201


@app.route("/api/menu/<int:item_id>", methods=["PUT"])
def update_menu_item(item_id):
    item = MenuItem.query.get_or_404(item_id)
    data = request.get_json()
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
    return jsonify(item.to_dict())


@app.route("/api/menu/<int:item_id>", methods=["DELETE"])
def delete_menu_item(item_id):
    item = MenuItem.query.get_or_404(item_id)
    db.session.delete(item)
    db.session.commit()
    return jsonify({"ok": True})


@app.route("/menu")
def menu_editor():
    return render_template("menu.html")


if __name__ == "__main__":
    socketio.run(app, host="0.0.0.0", port=5000, debug=True)
