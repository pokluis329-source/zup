"""
Modelos de base de datos SQLAlchemy para Zuppon.
"""

from flask_sqlalchemy import SQLAlchemy
from datetime import datetime

db = SQLAlchemy()


class MenuItem(db.Model):
    __tablename__ = "menu_items"

    id          = db.Column(db.Integer, primary_key=True)
    name        = db.Column(db.String(100), nullable=False)
    description = db.Column(db.Text, default="")
    price       = db.Column(db.Float, nullable=False)        # precio en USD
    emoji       = db.Column(db.String(10), default="🍔")
    category    = db.Column(db.String(50), nullable=False)
    is_popular  = db.Column(db.Boolean, default=False)
    asset_image = db.Column(db.String(100), default="")
    is_active   = db.Column(db.Boolean, default=True)
    created_at  = db.Column(db.DateTime, default=datetime.utcnow)
    updated_at  = db.Column(db.DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

    def to_dict(self):
        return {
            "id":          self.id,
            "name":        self.name,
            "description": self.description,
            "price":       self.price,
            "price_gs":    int(self.price * 7300),
            "emoji":       self.emoji,
            "category":    self.category,
            "is_popular":  self.is_popular,
            "asset_image": self.asset_image,
            "is_active":   self.is_active,
        }


# Items del menú inicial — se usan para el seed la primera vez
_GS = 7300.0
MENU_SEED = [
    (
        1,
        "Hamburguesa Clásica",
        "Carne, lechuga, tomate, queso cheddar",
        35_000 / _GS,
        "🍔",
        "🍔 Hamburguesas",
        True,
        "burger.webp",
    ),
    (
        2,
        "Hamburguesa Especial",
        "Doble carne, queso, salsa de la casa",
        35_000 / _GS,
        "🍔",
        "🍔 Hamburguesas",
        False,
        "bbqburger.webp",
    ),
    (
        3,
        "Sandwich de pollo Navsville",
        "Pollo crujiente, pickles, salsa especial",
        25_000 / _GS,
        "🥪",
        "🥪 Sandwiches",
        True,
        "burger.webp",
    ),
]


class Order(db.Model):
    __tablename__ = "orders"

    id           = db.Column(db.Integer, primary_key=True)
    user_id      = db.Column(db.Integer, db.ForeignKey("users.id"), nullable=True)
    client_name  = db.Column(db.String(100), default="Cliente")
    client_phone = db.Column(db.String(32), default="")
    items        = db.Column(db.Text, nullable=False)        # resumen del pedido
    destination  = db.Column(db.Text, nullable=False)        # dirección de entrega
    dest_lat     = db.Column(db.Float, default=0.0)            # coords de entrega (mapa)
    dest_lng     = db.Column(db.Float, default=0.0)
    fare         = db.Column(db.Float, nullable=False)       # tarifa en USD
    amount_gs    = db.Column(db.Integer, default=0)           # monto cobrado en Gs

    payment_status = db.Column(db.String(24), default="AWAITING_PAYMENT")
    # AWAITING_PAYMENT → PENDING_REVIEW → PAID | CASH_ON_DELIVERY | CANCELLED

    status       = db.Column(db.String(20), default="PENDING")
    # PENDING → ACCEPTED → PICKED_UP → DELIVERING → COMPLETED / CANCELLED

    driver_id    = db.Column(db.Integer, db.ForeignKey("drivers.id"), nullable=True)
    driver_name  = db.Column(db.String(100), nullable=True)
    driver_vehicle = db.Column(db.String(100), nullable=True)

    paid_at      = db.Column(db.DateTime, nullable=True)

    created_at   = db.Column(db.DateTime, default=datetime.utcnow)
    accepted_at  = db.Column(db.DateTime, nullable=True)
    picked_up_at = db.Column(db.DateTime, nullable=True)
    completed_at = db.Column(db.DateTime, nullable=True)

    def to_dict(self):
        owner = self.owner if self.user_id else None
        return {
            "id":             self.id,
            "user_id":        self.user_id,
            "client_name":    self.client_name,
            "client_phone":   self.client_phone or "",
            "buyer": {
                "user_id":      self.user_id,
                "username":     owner.username if owner else None,
                "email":        owner.email if owner else None,
                "display_name": owner.display_name if owner else None,
                "phone":        self.client_phone or "",
                "label": (
                    f"@{owner.username}" if owner and owner.username
                    else (owner.display_name if owner and owner.display_name else self.client_name)
                ) or "Cliente",
            },
            "items":          self.items,
            "destination":    self.destination,
            "dest_lat":       self.dest_lat or 0.0,
            "dest_lng":       self.dest_lng or 0.0,
            "fare":           self.fare,
            "fare_gs":        self.amount_gs or int(self.fare * 7300),
            "amount_gs":      self.amount_gs or int(self.fare * 7300),
            "payment_status": self.payment_status,
            "status":         self.status,
            "driver_id":      self.driver_id,
            "driver_name":    self.driver_name,
            "driver_vehicle": self.driver_vehicle,
            "created_at":     self.created_at.isoformat() if self.created_at else None,
            "accepted_at":    self.accepted_at.isoformat() if self.accepted_at else None,
            "picked_up_at":   self.picked_up_at.isoformat() if self.picked_up_at else None,
            "completed_at":   self.completed_at.isoformat() if self.completed_at else None,
            "paid_at":        self.paid_at.isoformat() if self.paid_at else None,
        }


class PaymentMessage(db.Model):
    __tablename__ = "payment_messages"

    id         = db.Column(db.Integer, primary_key=True)
    order_id   = db.Column(db.Integer, db.ForeignKey("orders.id"), nullable=False)
    sender     = db.Column(db.String(20), default="client")   # client | admin | system
    msg_type   = db.Column(db.String(20), default="text")     # text | image | system
    body       = db.Column(db.Text, default="")
    image_path = db.Column(db.String(255), nullable=True)
    created_at = db.Column(db.DateTime, default=datetime.utcnow)

    order = db.relationship("Order", backref=db.backref("payment_messages", lazy=True))

    def to_dict(self, base_url: str = ""):
        data = {
            "id":         self.id,
            "order_id":   self.order_id,
            "sender":     self.sender,
            "type":       self.msg_type,
            "body":       self.body,
            "created_at": self.created_at.isoformat() if self.created_at else None,
        }
        if self.image_path:
            path = f"/uploads/receipts/{self.image_path}"
            data["image_url"] = f"{base_url.rstrip('/')}{path}" if base_url else path
        return data


class User(db.Model):
    __tablename__ = "users"

    id            = db.Column(db.Integer, primary_key=True)
    google_id     = db.Column(db.String(128), unique=True, nullable=False)
    email         = db.Column(db.String(255), nullable=True)
    display_name  = db.Column(db.String(255), nullable=True)
    username      = db.Column(db.String(30), unique=True, nullable=True)
    password_hash = db.Column(db.String(255), nullable=True)
    is_driver     = db.Column(db.Boolean, default=False)
    created_at    = db.Column(db.DateTime, default=datetime.utcnow)

    def to_dict(self):
        return {
            "id":             self.id,
            "email":          self.email,
            "display_name":   self.display_name,
            "username":       self.username,
            "needs_username": self.username is None,
            "is_driver":      bool(self.is_driver),
        }

    def to_admin_dict(self, orders_count: int = 0):
        data = self.to_dict()
        data["created_at"] = self.created_at.isoformat() if self.created_at else None
        data["orders_count"] = orders_count
        return data

    orders = db.relationship(
        "Order",
        backref="owner",
        lazy=True,
        foreign_keys="Order.user_id",
    )


class Driver(db.Model):
    __tablename__ = "drivers"

    id         = db.Column(db.Integer, primary_key=True)
    device_id  = db.Column(db.String(100), unique=True, nullable=False)
    name       = db.Column(db.String(100), default="Repartidor")
    vehicle    = db.Column(db.String(100), default="Moto")
    plate      = db.Column(db.String(20), default="")
    rating     = db.Column(db.Float, default=5.0)
    is_online  = db.Column(db.Boolean, default=False)
    lat        = db.Column(db.Float, nullable=True)
    lng        = db.Column(db.Float, nullable=True)
    created_at = db.Column(db.DateTime, default=datetime.utcnow)
    updated_at = db.Column(db.DateTime, default=datetime.utcnow)

    orders = db.relationship("Order", backref="driver", lazy=True,
                              foreign_keys=[Order.driver_id])

    def to_dict(self):
        return {
            "id":        self.id,
            "device_id": self.device_id,
            "name":      self.name,
            "vehicle":   self.vehicle,
            "plate":     self.plate,
            "rating":    self.rating,
            "is_online": self.is_online,
            "lat":       self.lat,
            "lng":       self.lng,
        }
