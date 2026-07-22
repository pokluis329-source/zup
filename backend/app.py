"""
Zuppon Backend — Flask REST API
Maneja pedidos, repartidores y el ciclo completo de delivery.
"""

from dotenv import load_dotenv

load_dotenv()

from flask import Flask, jsonify, request, render_template
from flask_socketio import SocketIO, emit, join_room
from sqlalchemy import inspect, text
from database import db, Order, Driver, MenuItem, MENU_SEED
from datetime import datetime
import os

import pagopar
from pagopar import PagoparError


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
    if "pagopar_hash" not in existing:
        alters.append("ALTER TABLE orders ADD COLUMN pagopar_hash VARCHAR(128)")
    if "paid_at" not in existing:
        alters.append("ALTER TABLE orders ADD COLUMN paid_at DATETIME")
    if not alters:
        return
    with db.engine.begin() as conn:
        for stmt in alters:
            conn.execute(text(stmt))


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


def sync_payment_from_pagopar(order: Order) -> bool:
    if not order.pagopar_hash or order.payment_status == "PAID":
        return order.payment_status == "PAID"
    status = pagopar.get_order_status(order.pagopar_hash)
    if status.get("pagado"):
        mark_order_paid(order)
        return True
    return False

app = Flask(__name__)
app.config["SECRET_KEY"] = os.environ.get("SECRET_KEY", "zuppon-dev-secret")
app.config["SQLALCHEMY_DATABASE_URI"] = os.environ.get(
    "DATABASE_URL", "sqlite:///zuppon.db"
)
app.config["SQLALCHEMY_TRACK_MODIFICATIONS"] = False

db.init_app(app)
socketio = SocketIO(app, cors_allowed_origins="*", async_mode="threading")

# ── Crear tablas al arrancar ──────────────────────────────────────────────────
with app.app_context():
    db.create_all()
    _ensure_order_columns()
    # Seed del menú si la tabla está vacía
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
    """Listar todos los pedidos (más recientes primero)."""
    orders = Order.query.order_by(Order.created_at.desc()).all()
    return jsonify([o.to_dict() for o in orders])


@app.route("/api/orders/<int:order_id>", methods=["GET"])
def get_order(order_id):
    """Obtener un pedido específico."""
    order = Order.query.get_or_404(order_id)
    return jsonify(order.to_dict())


@app.route("/api/orders", methods=["POST"])
def create_order():
    """Cliente crea un nuevo pedido."""
    data = request.get_json()

    required = ["items", "destination", "fare"]
    if not all(k in data for k in required):
        return jsonify({"error": f"Faltan campos: {required}"}), 400

    order = Order(
        items=data["items"],
        destination=data["destination"],
        dest_lat=float(data.get("dest_lat", 0.0) or 0.0),
        dest_lng=float(data.get("dest_lng", 0.0) or 0.0),
        fare=float(data["fare"]),
        client_name=data.get("client_name", "Cliente"),
        amount_gs=pagopar.usd_to_gs(float(data["fare"])),
        payment_status="AWAITING_PAYMENT",
        status="PENDING",
    )
    db.session.add(order)
    db.session.commit()

    return jsonify(order.to_dict()), 201


@app.route("/api/orders/checkout", methods=["POST"])
def checkout_order():
    """Crea pedido + transacción Pagopar y devuelve URL de pago."""
    data = request.get_json() or {}
    required = ["items", "destination", "fare", "buyer_email"]
    if not all(k in data for k in required):
        return jsonify({"error": f"Faltan campos: {required}"}), 400

    buyer = data.get("buyer") or {}
    buyer_email = data.get("buyer_email") or buyer.get("email")
    if not buyer_email:
        return jsonify({"error": "buyer_email es obligatorio"}), 400

    order = Order(
        items=data["items"],
        destination=data["destination"],
        dest_lat=float(data.get("dest_lat", 0.0) or 0.0),
        dest_lng=float(data.get("dest_lng", 0.0) or 0.0),
        fare=float(data["fare"]),
        client_name=data.get("client_name") or buyer.get("nombre") or "Cliente",
        amount_gs=pagopar.usd_to_gs(float(data["fare"])),
        payment_status="AWAITING_PAYMENT",
        status="PENDING",
    )
    db.session.add(order)
    db.session.commit()

    buyer_payload = {
        "nombre": buyer.get("nombre") or order.client_name,
        "email": buyer_email,
        "telefono": buyer.get("telefono") or data.get("buyer_phone", "0991000000"),
        "documento": buyer.get("documento") or data.get("buyer_document", "0000000"),
        "direccion": buyer.get("direccion") or order.destination,
        "direccion_referencia": buyer.get("direccion_referencia", ""),
        "coordenadas": buyer.get("coordenadas")
        or f"{order.dest_lat},{order.dest_lng}",
    }

    try:
        tx = pagopar.create_transaction(order, buyer_payload)
    except PagoparError as exc:
        db.session.delete(order)
        db.session.commit()
        return jsonify({"error": str(exc)}), 502

    order.pagopar_hash = tx["hash"]
    order.amount_gs = tx["amount_gs"]
    db.session.commit()

    payload = order.to_dict()
    payload["checkout_url"] = tx["checkout_url"]
    return jsonify(payload), 201


@app.route("/api/orders/<int:order_id>/payment-status", methods=["GET"])
def payment_status(order_id):
    """Consulta estado de pago (sincroniza con Pagopar si hace falta)."""
    order = Order.query.get_or_404(order_id)
    if order.payment_status != "PAID":
        sync_payment_from_pagopar(order)
        db.session.refresh(order)
    return jsonify({
        "order_id": order.id,
        "payment_status": order.payment_status,
        "paid": order.payment_status == "PAID",
        "checkout_url": order.to_dict().get("checkout_url"),
        "pagopar_hash": order.pagopar_hash,
    })


@app.route("/api/payments/pagopar/webhook", methods=["POST"])
def pagopar_webhook():
    """Pagopar notifica cuando un pago se confirma."""
    data = request.get_json(silent=True) or {}
    resultado = data.get("resultado") or []
    if not resultado and isinstance(data.get("hash_pedido"), str):
        resultado = [data]

    processed = []
    for item in resultado:
        hash_pedido = item.get("hash_pedido") or item.get("hash")
        token = item.get("token")
        if not hash_pedido:
            continue
        if token and not pagopar.verify_webhook_token(hash_pedido, token):
            continue
        order = Order.query.filter_by(pagopar_hash=hash_pedido).first()
        if not order:
            continue
        if item.get("pagado") is True or item.get("pagado") == 1:
            mark_order_paid(order)
            processed.append(order.id)
        elif token:
            sync_payment_from_pagopar(order)
            if order.payment_status == "PAID":
                processed.append(order.id)

    return jsonify({"ok": True, "processed": processed})


@app.route("/api/orders/<int:order_id>/accept", methods=["POST"])
def accept_order(order_id):
    """Repartidor acepta un pedido."""
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
    """Repartidor recogió el pedido."""
    order = Order.query.get_or_404(order_id)
    order.status = "PICKED_UP"
    order.picked_up_at = datetime.utcnow()
    db.session.commit()
    socketio.emit("order_updated", order.to_dict())
    return jsonify(order.to_dict())


@app.route("/api/orders/<int:order_id>/delivering", methods=["POST"])
def delivering(order_id):
    """Repartidor salió a entregar."""
    order = Order.query.get_or_404(order_id)
    order.status = "DELIVERING"
    db.session.commit()
    socketio.emit("order_updated", order.to_dict())
    return jsonify(order.to_dict())


@app.route("/api/orders/<int:order_id>/complete", methods=["POST"])
def complete_order(order_id):
    """Repartidor completó la entrega."""
    order = Order.query.get_or_404(order_id)
    order.status = "COMPLETED"
    order.completed_at = datetime.utcnow()
    db.session.commit()
    socketio.emit("order_updated", order.to_dict())
    return jsonify(order.to_dict())


@app.route("/api/orders/<int:order_id>/cancel", methods=["POST"])
def cancel_order(order_id):
    """Cliente cancela el pedido."""
    order = Order.query.get_or_404(order_id)
    if order.status in ("COMPLETED", "CANCELLED"):
        return jsonify({"error": "No se puede cancelar"}), 409
    order.status = "CANCELLED"
    db.session.commit()
    socketio.emit("order_updated", order.to_dict())
    return jsonify(order.to_dict())


@app.route("/api/orders/<int:order_id>", methods=["DELETE"])
def delete_order(order_id):
    """Eliminar un pedido de la base de datos."""
    order = Order.query.get_or_404(order_id)
    order_data = order.to_dict()
    db.session.delete(order)
    db.session.commit()
    socketio.emit("order_deleted", {"id": order_id, "order": order_data})
    return jsonify({"ok": True, "deleted_id": order_id})


# ═════════════════════════════════════════════════════════════════════════════
#  REST — Repartidores
# ═════════════════════════════════════════════════════════════════════════════

@app.route("/api/drivers", methods=["GET"])
def get_drivers():
    """Listar repartidores online."""
    drivers = Driver.query.filter_by(is_online=True).all()
    return jsonify([d.to_dict() for d in drivers])


@app.route("/api/drivers/register", methods=["POST"])
def register_driver():
    """Registrar o actualizar un repartidor."""
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
    """Actualizar ubicación GPS del repartidor en tiempo real."""
    driver = Driver.query.get_or_404(driver_id)
    data = request.get_json()
    driver.lat = data["lat"]
    driver.lng = data["lng"]
    driver.updated_at = datetime.utcnow()
    db.session.commit()

    # Emitir a la sala del pedido activo si tiene uno
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
#  WebSocket — Salas en tiempo real
# ═════════════════════════════════════════════════════════════════════════════

@socketio.on("join_drivers")
def on_join_drivers():
    """Repartidor se une a la sala de pedidos nuevos."""
    join_room("drivers")
    emit("joined", {"room": "drivers"})


@socketio.on("join_order")
def on_join_order(data):
    """Cliente se une a la sala de su pedido para recibir updates."""
    order_id = data.get("order_id")
    if order_id:
        join_room(f"order_{order_id}")
        emit("joined", {"room": f"order_{order_id}"})


# ═════════════════════════════════════════════════════════════════════════════
#  Health check
# ═════════════════════════════════════════════════════════════════════════════

@app.route("/health")
def health():
    base = os.environ.get("PUBLIC_BASE_URL", "").rstrip("/")
    webhook = f"{base}/api/payments/pagopar/webhook" if base else None
    return jsonify({
        "status": "ok",
        "app": "Zuppon Backend",
        "pagopar_configured": pagopar.configured(),
        "public_base_url": base or None,
        "pagopar_webhook_url": webhook,
    })


@app.route("/")
@app.route("/dashboard")
def dashboard():
    return render_template("dashboard.html")


# ═════════════════════════════════════════════════════════════════════════════
#  REST — Menú
# ═════════════════════════════════════════════════════════════════════════════

@app.route("/api/menu", methods=["GET"])
def get_menu():
    """Listar todos los items activos del menú."""
    items = MenuItem.query.order_by(MenuItem.category, MenuItem.id).all()
    return jsonify([i.to_dict() for i in items])


@app.route("/api/menu", methods=["POST"])
def create_menu_item():
    """Crear un nuevo item del menú."""
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
    """Actualizar un item del menú."""
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
    """Eliminar un item del menú."""
    item = MenuItem.query.get_or_404(item_id)
    db.session.delete(item)
    db.session.commit()
    return jsonify({"ok": True})


# ── Página web del editor de menú ─────────────────────────────────────────────
@app.route("/menu")
def menu_editor():
    return render_template("menu.html")


if __name__ == "__main__":
    socketio.run(app, host="0.0.0.0", port=5000, debug=True)
