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
MENU_SEED = [
    # Populares
    (1,  "Burger Clásica",        "Carne, lechuga, tomate, queso cheddar",           8.50,  "🍔", "🔥 Populares",    True,  "burger.webp"),
    (2,  "Pizza Margarita",       "Salsa de tomate, mozzarella, albahaca",            9.00,  "🍕", "🔥 Populares",    True,  "pizza.webp"),
    (3,  "Tacos al Pastor x3",    "Cerdo, piña, cilantro, cebolla",                  7.00,  "🌮", "🔥 Populares",    True,  "tacos.webp"),
    (4,  "Pasta Carbonara",       "Espagueti, panceta, huevo, parmesano",             8.00,  "🍝", "🔥 Populares",    True,  "pasta.webp"),
    # Hamburguesas
    (5,  "Burger Clásica",        "Carne, lechuga, tomate, queso cheddar",           8.50,  "🍔", "🍔 Hamburguesas", False, "burger.webp"),
    (6,  "Burger BBQ Doble",      "Doble carne, tocino, salsa BBQ, aros de cebolla", 10.50, "🥩", "🍔 Hamburguesas", True,  "bbqburger.webp"),
    (7,  "Burger Vegana",         "Medallón de lentejas, aguacate, tomate",           9.00,  "🌿", "🍔 Hamburguesas", False, "burger.webp"),
    (8,  "Burger Hawaiana",       "Carne, piña, queso suizo, teriyaki",               9.50,  "🍍", "🍔 Hamburguesas", False, "burger.webp"),
    # Pizzas
    (9,  "Pizza Margarita",       "Salsa de tomate, mozzarella, albahaca",            9.00,  "🍕", "🍕 Pizzas",       False, "pizza.webp"),
    (10, "Pizza Pepperoni",       "Salsa de tomate, mozzarella, pepperoni",           10.00, "🍕", "🍕 Pizzas",       True,  "pizza.webp"),
    (11, "Pizza 4 Quesos",        "Mozzarella, gouda, gorgonzola, parmesano",         11.00, "🧀", "🍕 Pizzas",       False, "pizza.webp"),
    (12, "Pizza Vegetal",         "Champiñones, pimiento, cebolla, aceitunas",        9.50,  "🥦", "🍕 Pizzas",       False, "pizza.webp"),
    # Tacos
    (13, "Tacos al Pastor x3",    "Cerdo, piña, cilantro, cebolla",                  7.00,  "🌮", "🌮 Tacos",        False, "tacos.webp"),
    (14, "Tacos de Res x3",       "Carne de res, guacamole, salsa roja",              7.50,  "🌮", "🌮 Tacos",        True,  "tacos.webp"),
    (15, "Tacos de Camarón x3",   "Camarón a la plancha, col, limón",                 8.50,  "🦐", "🌮 Tacos",        False, "tacos.webp"),
    (16, "Quesadilla de Pollo",   "Pollo, queso, crema, guacamole",                   6.50,  "🧀", "🌮 Tacos",        False, "tacos.webp"),
    # Pastas
    (17, "Pasta Carbonara",       "Espagueti, panceta, huevo, parmesano",             8.00,  "🍝", "🍜 Pastas",       True,  "pasta.webp"),
    (18, "Pasta Bolognesa",       "Espagueti, carne molida, salsa de tomate",         8.50,  "🍝", "🍜 Pastas",       False, "pasta.webp"),
    (19, "Pasta Alfredo",         "Fettuccine, crema, mantequilla, parmesano",        8.00,  "🍝", "🍜 Pastas",       False, "pasta.webp"),
    (20, "Lasaña de Carne",       "Capas de pasta, carne, béchamel, queso",           9.50,  "🫕", "🍜 Pastas",       False, "lasagna.webp"),
    # Ensaladas
    (21, "Ensalada César",        "Pollo, lechuga romana, crutones, aderezo",         7.00,  "🥗", "🥗 Ensaladas",    True,  "salad.webp"),
    (22, "Ensalada Caprese",      "Tomate, mozzarella, albahaca, aceite de oliva",    6.50,  "🍅", "🥗 Ensaladas",    False, "salad.webp"),
    (23, "Bowl de Quinoa",        "Quinoa, aguacate, pepino, aderezo de limón",       8.00,  "🥑", "🥗 Ensaladas",    False, "salad.webp"),
    # Bebidas
    (24, "Refresco",              "Coca-Cola, Sprite o Fanta · 500 ml",               2.00,  "🥤", "🥤 Bebidas",      False, "drink.webp"),
    (25, "Agua Natural",          "Botella 600 ml",                                   1.50,  "💧", "🥤 Bebidas",      False, "drink.webp"),
    (26, "Jugo de Naranja",       "Natural, recién exprimido · 350 ml",               3.00,  "🍊", "🥤 Bebidas",      False, "drink.webp"),
    (27, "Malteada de Chocolate", "Helado, leche, chocolate · 400 ml",                4.50,  "🍫", "🥤 Bebidas",      True,  "drink.webp"),
]


class Order(db.Model):
    __tablename__ = "orders"

    id           = db.Column(db.Integer, primary_key=True)
    client_name  = db.Column(db.String(100), default="Cliente")
    items        = db.Column(db.Text, nullable=False)        # resumen del pedido
    destination  = db.Column(db.Text, nullable=False)        # dirección de entrega
    fare         = db.Column(db.Float, nullable=False)       # tarifa en USD

    status       = db.Column(db.String(20), default="PENDING")
    # PENDING → ACCEPTED → PICKED_UP → DELIVERING → COMPLETED / CANCELLED

    driver_id    = db.Column(db.Integer, db.ForeignKey("drivers.id"), nullable=True)
    driver_name  = db.Column(db.String(100), nullable=True)
    driver_vehicle = db.Column(db.String(100), nullable=True)

    created_at   = db.Column(db.DateTime, default=datetime.utcnow)
    accepted_at  = db.Column(db.DateTime, nullable=True)
    picked_up_at = db.Column(db.DateTime, nullable=True)
    completed_at = db.Column(db.DateTime, nullable=True)

    def to_dict(self):
        return {
            "id":             self.id,
            "client_name":    self.client_name,
            "items":          self.items,
            "destination":    self.destination,
            "fare":           self.fare,
            "fare_gs":        int(self.fare * 7300),
            "status":         self.status,
            "driver_id":      self.driver_id,
            "driver_name":    self.driver_name,
            "driver_vehicle": self.driver_vehicle,
            "created_at":     self.created_at.isoformat() if self.created_at else None,
            "accepted_at":    self.accepted_at.isoformat() if self.accepted_at else None,
            "picked_up_at":   self.picked_up_at.isoformat() if self.picked_up_at else None,
            "completed_at":   self.completed_at.isoformat() if self.completed_at else None,
        }


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
