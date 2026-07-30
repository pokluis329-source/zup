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

    private const val GS_RATE = 7300.0

    val categories = listOf(
        "🍔 Hamburguesas",
        "🥪 Sandwiches"
    )

    @DrawableRes
    fun backgroundFor(category: String): Int = when {
        category.contains("Hamburgues") -> R.drawable.bg_food_burger
        category.contains("Sandwich")   -> R.drawable.bg_food_burger
        else                            -> R.drawable.bg_food_popular
    }

    val items = listOf(
        MenuItem(
            1,
            "Hamburguesa Clásica",
            "Carne, lechuga, tomate, queso cheddar",
            35_000 / GS_RATE,
            "🍔",
            "🍔 Hamburguesas",
            true,
            "burger.webp"
        ),
        MenuItem(
            2,
            "Hamburguesa Especial",
            "Doble carne, queso, salsa de la casa",
            35_000 / GS_RATE,
            "🍔",
            "🍔 Hamburguesas",
            false,
            "bbqburger.webp"
        ),
        MenuItem(
            3,
            "Sandwich de pollo Navsville",
            "Pollo crujiente, pickles, salsa especial",
            25_000 / GS_RATE,
            "🥪",
            "🥪 Sandwiches",
            true,
            "burger.webp"
        )
    )

    fun byCategory(category: String): List<MenuItem> = items.filter { it.category == category }
}
