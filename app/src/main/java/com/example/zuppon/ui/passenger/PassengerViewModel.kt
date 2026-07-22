package com.example.zuppon.ui.passenger

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.zuppon.model.FoodMenu
import com.example.zuppon.model.MenuItem
import com.example.zuppon.model.TripRequest
import com.example.zuppon.model.TripState
import com.example.zuppon.network.MenuItemDto
import com.example.zuppon.network.NetworkRepository
import com.example.zuppon.repository.TripRepository

class PassengerViewModel : ViewModel() {

    val tripState: LiveData<TripState> = TripRepository.tripState

    // ── Menú ────────────────────────────────────────────────────────────────

    // Lista completa de items (server o fallback local)
    private var allItems: List<MenuItem> = FoodMenu.items

    private val _categories = MutableLiveData<List<String>>(FoodMenu.categories)
    val categories: LiveData<List<String>> = _categories

    private val _selectedCategory = MutableLiveData(FoodMenu.categories.first())
    val selectedCategory: LiveData<String> = _selectedCategory

    private val _menuItems = MutableLiveData<List<MenuItem>>(FoodMenu.byCategory(FoodMenu.categories.first()))
    val menuItems: LiveData<List<MenuItem>> = _menuItems

    private val _menuLoading = MutableLiveData(false)
    val menuLoading: LiveData<Boolean> = _menuLoading

    /** id → cantidad en el carrito */
    private val _cart = MutableLiveData<Map<Int, Int>>(emptyMap())
    val cart: LiveData<Map<Int, Int>> = _cart

    private val _cartTotal = MutableLiveData(0.0)
    val cartTotal: LiveData<Double> = _cartTotal

    private val _cartCount = MutableLiveData(0)
    val cartCount: LiveData<Int> = _cartCount

    // ── Estimados ────────────────────────────────────────────────────────────

    private val _estimatedMinutes = MutableLiveData<Int>()
    val estimatedMinutes: LiveData<Int> = _estimatedMinutes

    // ── Rating ───────────────────────────────────────────────────────────────

    private val _rating = MutableLiveData(5)
    val rating: LiveData<Int> = _rating

    // ── Init — cargar menú del backend ───────────────────────────────────────

    init {
        loadMenuFromServer()
    }

    fun loadMenuFromServer() {
        _menuLoading.value = true
        NetworkRepository.fetchMenu(
            onSuccess = { dtos ->
                _menuLoading.value = false
                if (dtos.isNotEmpty()) {
                    applyServerMenu(dtos)
                }
                // Si la respuesta está vacía, mantenemos el fallback local
            },
            onError = {
                _menuLoading.value = false
                // Sin red → usar items hardcodeados, sin hacer nada más
            }
        )
    }

    private fun applyServerMenu(dtos: List<MenuItemDto>) {
        val activeItems = dtos.filter { it.is_active }

        // Convertir DTOs a MenuItem — los items con is_popular=true se incluyen
        // tanto en "🔥 Populares" como en su categoría propia
        val converted = activeItems.map { dto ->
            MenuItem(
                id          = dto.id,
                name        = dto.name,
                description = dto.description,
                price       = dto.price,
                emoji       = dto.emoji,
                category    = dto.category,
                isPopular   = dto.is_popular,
                assetImage  = dto.asset_image
            )
        }

        // Construir "🔥 Populares" dinámicamente: todos los items con is_popular=true,
        // independientemente de su categoría real
        val popularItems = activeItems
            .filter { it.is_popular }
            .map { dto ->
                MenuItem(
                    id          = dto.id,
                    name        = dto.name,
                    description = dto.description,
                    price       = dto.price,
                    emoji       = dto.emoji,
                    category    = "🔥 Populares",   // forzar categoría visual
                    isPopular   = true,
                    assetImage  = dto.asset_image
                )
            }

        // allItems = populares (categoría virtual) + todos los demás
        allItems = popularItems + converted

        // Reconstruir categorías: "🔥 Populares" primero si hay populares,
        // luego el resto en el orden original del catálogo
        val realCats = FoodMenu.categories
            .filter { cat -> cat != "🔥 Populares" && converted.any { it.category == cat } }
        val orderedCats = if (popularItems.isNotEmpty())
            listOf("🔥 Populares") + realCats
        else
            realCats

        _categories.value = orderedCats

        val current  = _selectedCategory.value ?: orderedCats.first()
        val validCat = if (orderedCats.contains(current)) current else orderedCats.first()
        _selectedCategory.value = validCat
        _menuItems.value = allItems.filter { it.category == validCat }
    }

    // ── Acciones de menú ─────────────────────────────────────────────────────

    fun selectCategory(category: String) {
        _selectedCategory.value = category
        _menuItems.value = allItems.filter { it.category == category }
    }

    fun addToCart(item: MenuItem) {
        val current = _cart.value.orEmpty().toMutableMap()
        current[item.id] = (current[item.id] ?: 0) + 1
        _cart.value = current
        recalcCart(current)
    }

    fun removeFromCart(item: MenuItem) {
        val current = _cart.value.orEmpty().toMutableMap()
        val qty = (current[item.id] ?: 0) - 1
        if (qty <= 0) current.remove(item.id) else current[item.id] = qty
        _cart.value = current
        recalcCart(current)
    }

    private fun recalcCart(cart: Map<Int, Int>) {
        val total = cart.entries.sumOf { (id, qty) ->
            val price = allItems.find { it.id == id }?.price
                ?: FoodMenu.items.find { it.id == id }?.price
                ?: 0.0
            price * qty
        }
        val count = cart.values.sum()
        _cartTotal.value = total
        _cartCount.value = count
        _estimatedMinutes.value = 20 + (total.toInt() % 15)
    }

    // ── Pedir ────────────────────────────────────────────────────────────────

    fun requestOrder(
        deliveryAddress: String,
        destLat: Double = 0.0,
        destLng: Double = 0.0,
        buyerEmail: String,
        buyerPhone: String = "",
        buyerName: String = "Cliente",
        onCheckoutReady: (String) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (deliveryAddress.isBlank() || buyerEmail.isBlank()) return
        val fare = _cartTotal.value ?: 0.0
        val cartSummary = buildCartSummary()
        val request = TripRequest(
            passengerName = cartSummary.ifBlank { "Pedido" },
            destination   = deliveryAddress,
            fare          = fare,
            destLat       = destLat,
            destLng       = destLng
        )
        TripRepository.passengerRequestTrip(
            request = request,
            buyerEmail = buyerEmail,
            buyerPhone = buyerPhone,
            buyerName = buyerName,
            onCheckoutReady = onCheckoutReady,
            onError = onError
        )
    }

    fun checkPayment(onPaid: () -> Unit = {}, onPending: () -> Unit = {}, onError: (String) -> Unit = {}) {
        TripRepository.passengerCheckPayment(onPaid, onPending, onError)
    }

    private fun buildCartSummary(): String {
        return _cart.value.orEmpty().entries.joinToString(", ") { (id, qty) ->
            val name = allItems.find { it.id == id }?.name
                ?: FoodMenu.items.find { it.id == id }?.name
                ?: ""
            "$name x$qty"
        }
    }

    fun cancelOrder() = TripRepository.passengerCancelTrip()

    fun setRating(stars: Int) { _rating.value = stars }

    fun startNewOrder() {
        _cart.value = emptyMap()
        _cartTotal.value = 0.0
        _cartCount.value = 0
        TripRepository.reset()
        // Recargar menú por si hubo cambios
        loadMenuFromServer()
    }
}
