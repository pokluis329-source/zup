package com.example.zuppon.model

import androidx.annotation.DrawableRes
import com.example.zuppon.R

data class MenuItem(
    val id: Int,
    val name: String,
    val description: String,
    val price: Double,
    val emoji: String,
    val category: String,
    val isPopular: Boolean = false,
    /** Nombre del archivo en assets/food/  p.ej. "burger.webp" */
    val assetImage: String = ""
)

object FoodMenu {

    val categories = listOf(
        "🔥 Populares", "🍔 Hamburguesas", "🍕 Pizzas",
        "🌮 Tacos", "🍜 Pastas", "🥗 Ensaladas", "🥤 Bebidas"
    )

    @DrawableRes
    fun backgroundFor(category: String): Int = when {
        category.contains("Populares")  -> R.drawable.bg_food_popular
        category.contains("Hamburgues") -> R.drawable.bg_food_burger
        category.contains("Pizza")      -> R.drawable.bg_food_pizza
        category.contains("Taco")       -> R.drawable.bg_food_taco
        category.contains("Pasta")      -> R.drawable.bg_food_pasta
        category.contains("Ensalada")   -> R.drawable.bg_food_salad
        category.contains("Bebida")     -> R.drawable.bg_food_drink
        else                            -> R.drawable.bg_food_popular
    }

    val items = listOf(
        // Populares
        MenuItem(1,  "Burger Clásica",        "Carne, lechuga, tomate, queso cheddar",           8.50,  "🍔", "🔥 Populares",    true,  "burger.webp"),
        MenuItem(2,  "Pizza Margarita",        "Salsa de tomate, mozzarella, albahaca",            9.00,  "🍕", "🔥 Populares",    true,  "pizza.webp"),
        MenuItem(3,  "Tacos al Pastor x3",     "Cerdo, piña, cilantro, cebolla",                  7.00,  "🌮", "🔥 Populares",    true,  "tacos.webp"),
        MenuItem(4,  "Pasta Carbonara",        "Espagueti, panceta, huevo, parmesano",             8.00,  "🍝", "🔥 Populares",    true,  "pasta.webp"),

        // Hamburguesas
        MenuItem(5,  "Burger Clásica",        "Carne, lechuga, tomate, queso cheddar",           8.50,  "🍔", "🍔 Hamburguesas", false, "burger.webp"),
        MenuItem(6,  "Burger BBQ Doble",      "Doble carne, tocino, salsa BBQ, aros de cebolla", 10.50, "🥩", "🍔 Hamburguesas", true,  "bbqburger.webp"),
        MenuItem(7,  "Burger Vegana",         "Medallón de lentejas, aguacate, tomate",           9.00,  "🌿", "🍔 Hamburguesas", false, "burger.webp"),
        MenuItem(8,  "Burger Hawaiana",       "Carne, piña, queso suizo, teriyaki",               9.50,  "🍍", "🍔 Hamburguesas", false, "burger.webp"),

        // Pizzas
        MenuItem(9,  "Pizza Margarita",       "Salsa de tomate, mozzarella, albahaca",            9.00,  "🍕", "🍕 Pizzas",       false, "pizza.webp"),
        MenuItem(10, "Pizza Pepperoni",       "Salsa de tomate, mozzarella, pepperoni",           10.00, "🍕", "🍕 Pizzas",       true,  "pizza.webp"),
        MenuItem(11, "Pizza 4 Quesos",        "Mozzarella, gouda, gorgonzola, parmesano",         11.00, "🧀", "🍕 Pizzas",       false, "pizza.webp"),
        MenuItem(12, "Pizza Vegetal",         "Champiñones, pimiento, cebolla, aceitunas",        9.50,  "🥦", "🍕 Pizzas",       false, "pizza.webp"),

        // Tacos
        MenuItem(13, "Tacos al Pastor x3",    "Cerdo, piña, cilantro, cebolla",                  7.00,  "🌮", "🌮 Tacos",        false, "tacos.webp"),
        MenuItem(14, "Tacos de Res x3",       "Carne de res, guacamole, salsa roja",              7.50,  "🌮", "🌮 Tacos",        true,  "tacos.webp"),
        MenuItem(15, "Tacos de Camarón x3",   "Camarón a la plancha, col, limón",                 8.50,  "🦐", "🌮 Tacos",        false, "tacos.webp"),
        MenuItem(16, "Quesadilla de Pollo",   "Pollo, queso, crema, guacamole",                   6.50,  "🧀", "🌮 Tacos",        false, "tacos.webp"),

        // Pastas
        MenuItem(17, "Pasta Carbonara",       "Espagueti, panceta, huevo, parmesano",             8.00,  "🍝", "🍜 Pastas",       true,  "pasta.webp"),
        MenuItem(18, "Pasta Bolognesa",       "Espagueti, carne molida, salsa de tomate",         8.50,  "🍝", "🍜 Pastas",       false, "pasta.webp"),
        MenuItem(19, "Pasta Alfredo",         "Fettuccine, crema, mantequilla, parmesano",        8.00,  "🍝", "🍜 Pastas",       false, "pasta.webp"),
        MenuItem(20, "Lasaña de Carne",       "Capas de pasta, carne, béchamel, queso",           9.50,  "🫕", "🍜 Pastas",       false, "lasagna.webp"),

        // Ensaladas
        MenuItem(21, "Ensalada César",        "Pollo, lechuga romana, crutones, aderezo",         7.00,  "🥗", "🥗 Ensaladas",    true,  "salad.webp"),
        MenuItem(22, "Ensalada Caprese",      "Tomate, mozzarella, albahaca, aceite de oliva",    6.50,  "🍅", "🥗 Ensaladas",    false, "salad.webp"),
        MenuItem(23, "Bowl de Quinoa",        "Quinoa, aguacate, pepino, aderezo de limón",       8.00,  "🥑", "🥗 Ensaladas",    false, "salad.webp"),

        // Bebidas
        MenuItem(24, "Refresco",              "Coca-Cola, Sprite o Fanta · 500 ml",               2.00,  "🥤", "🥤 Bebidas",      false, "drink.webp"),
        MenuItem(25, "Agua Natural",          "Botella 600 ml",                                   1.50,  "💧", "🥤 Bebidas",      false, "drink.webp"),
        MenuItem(26, "Jugo de Naranja",       "Natural, recién exprimido · 350 ml",               3.00,  "🍊", "🥤 Bebidas",      false, "drink.webp"),
        MenuItem(27, "Malteada de Chocolate", "Helado, leche, chocolate · 400 ml",                4.50,  "🍫", "🥤 Bebidas",      true,  "drink.webp")
    )

    fun byCategory(category: String): List<MenuItem> = items.filter { it.category == category }
}
