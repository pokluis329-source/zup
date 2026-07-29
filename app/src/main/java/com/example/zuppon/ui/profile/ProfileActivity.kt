package com.example.zuppon.ui.profile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.zuppon.R
import com.example.zuppon.auth.AuthRepository
import com.example.zuppon.auth.LoginActivity
import com.example.zuppon.network.OrderDto
import com.example.zuppon.repository.TripRepository
import com.example.zuppon.util.UserSession
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Locale

class ProfileActivity : AppCompatActivity() {

    private lateinit var tvName: TextView
    private lateinit var tvEmail: TextView
    private lateinit var tvGuest: TextView
    private lateinit var tvNoOrders: TextView
    private lateinit var llOrders: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var btnLogin: MaterialButton
    private lateinit var btnLogout: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        tvName = findViewById(R.id.tv_profile_name)
        tvEmail = findViewById(R.id.tv_profile_email)
        tvGuest = findViewById(R.id.tv_profile_guest)
        tvNoOrders = findViewById(R.id.tv_no_orders)
        llOrders = findViewById(R.id.ll_profile_orders)
        progress = findViewById(R.id.progress_orders)
        btnLogin = findViewById(R.id.btn_profile_login)
        btnLogout = findViewById(R.id.btn_logout)

        findViewById<MaterialToolbar>(R.id.toolbar_profile).setNavigationOnClickListener {
            finish()
        }

        btnLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }

        btnLogout.setOnClickListener { performLogout() }

        bindUser()
        loadOrders()
    }

    override fun onResume() {
        super.onResume()
        bindUser()
        loadOrders()
    }

    private fun bindUser() {
        val user = UserSession.getUser()
        val loggedIn = UserSession.isLoggedIn() && user != null

        if (loggedIn) {
            tvName.text = user!!.displayLabel()
            tvEmail.text = user.email.orEmpty()
            tvEmail.visibility = if (user.email.isNullOrBlank()) View.GONE else View.VISIBLE
            tvGuest.visibility = View.GONE
            btnLogin.visibility = View.GONE
            btnLogout.visibility = View.VISIBLE
        } else {
            tvName.text = "Invitado"
            tvEmail.visibility = View.GONE
            tvGuest.visibility = View.VISIBLE
            btnLogin.visibility = View.VISIBLE
            btnLogout.visibility = View.GONE
        }
    }

    private fun loadOrders() {
        llOrders.removeAllViews()
        if (!UserSession.isLoggedIn()) {
            tvNoOrders.visibility = View.VISIBLE
            tvNoOrders.text = "Iniciá sesión para ver tus pedidos."
            progress.visibility = View.GONE
            return
        }

        progress.visibility = View.VISIBLE
        tvNoOrders.visibility = View.GONE

        AuthRepository.fetchMyOrders(
            activeOnly = false,
            onSuccess = { orders ->
                progress.visibility = View.GONE
                if (orders.isEmpty()) {
                    tvNoOrders.visibility = View.VISIBLE
                    tvNoOrders.text = "Todavía no tenés pedidos."
                    return@fetchMyOrders
                }
                tvNoOrders.visibility = View.GONE
                orders.forEach { addOrderRow(it) }
            },
            onError = {
                progress.visibility = View.GONE
                tvNoOrders.visibility = View.VISIBLE
                tvNoOrders.text = "No se pudieron cargar los pedidos."
            }
        )
    }

    private fun addOrderRow(order: OrderDto) {
        val row = LayoutInflater.from(this)
            .inflate(R.layout.item_order_history, llOrders, false)

        row.findViewById<TextView>(R.id.tv_order_destination).text =
            "📍 ${order.destination}"
        row.findViewById<TextView>(R.id.tv_order_fare).text =
            formatGs(order.amount_gs.takeIf { it > 0 } ?: order.fare_gs)
        row.findViewById<TextView>(R.id.tv_order_status).text =
            statusLabel(order)
        row.findViewById<TextView>(R.id.tv_order_time).text =
            order.created_at?.let { formatDate(it) } ?: "#${order.id}"

        llOrders.addView(row)
    }

    private fun statusLabel(order: OrderDto): String = when {
        order.status == "COMPLETED" -> "🎉 Entregado"
        order.status == "CANCELLED" -> "❌ Cancelado"
        order.payment_status == "AWAITING_PAYMENT" -> "💸 Esperando pago"
        order.payment_status == "PENDING_REVIEW" -> "📸 Verificando pago"
        order.payment_status == "CASH_ON_DELIVERY" -> "💵 Efectivo al entregar"
        order.status == "DELIVERING" -> "🛵 En camino"
        order.status == "ACCEPTED" -> "✅ Repartidor asignado"
        else -> "⏳ ${order.status}"
    }

    private fun formatGs(value: Int): String =
        "Gs %,d".format(value.toLong()).replace(',', '.')

    private fun formatDate(iso: String): String = try {
        val inFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val outFmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
        outFmt.format(inFmt.parse(iso.substring(0, 19))!!)
    } catch (_: Exception) {
        iso.take(10)
    }

    private fun performLogout() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.google_web_client_id))
            .requestEmail()
            .build()
        GoogleSignIn.getClient(this, gso).signOut().addOnCompleteListener {
            UserSession.clear()
            TripRepository.logoutUser()
            Toast.makeText(this, "Sesión cerrada", Toast.LENGTH_SHORT).show()
            startActivity(
                Intent(this, LoginActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            finish()
        }
    }
}
