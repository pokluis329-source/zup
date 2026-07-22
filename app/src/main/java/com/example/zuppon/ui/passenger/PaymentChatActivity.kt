package com.example.zuppon.ui.passenger

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.zuppon.R
import com.example.zuppon.model.PaymentMessage
import com.example.zuppon.network.ApiClient
import com.example.zuppon.network.NetworkRepository
import com.example.zuppon.repository.TripRepository
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

class PaymentChatActivity : AppCompatActivity() {

    private lateinit var adapter: PaymentChatAdapter
    private lateinit var chatList: RecyclerView
    private val main = Handler(Looper.getMainLooper())
    private var orderId: Int = -1
    private var amountGs: Int = 0
    private var alias: String = ""
    private var cedula: String = ""
    private var pollRunnable: Runnable? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { uploadReceipt(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment_chat)

        orderId = intent.getIntExtra(EXTRA_ORDER_ID, -1)
        amountGs = intent.getIntExtra(EXTRA_AMOUNT_GS, 0)
        alias = intent.getStringExtra(EXTRA_ALIAS).orEmpty()
        cedula = intent.getStringExtra(EXTRA_CEDULA).orEmpty()

        if (orderId == -1) {
            finish()
            return
        }

        supportActionBar?.title = "Pago pedido #$orderId"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        findViewById<TextView>(R.id.tv_payment_amount).text =
            "Gs ${formatGs(amountGs)}"
        findViewById<TextView>(R.id.tv_payment_alias).text = "Alias: $alias"
        findViewById<TextView>(R.id.tv_payment_cedula).text = "CI: $cedula"

        val rv = findViewById<RecyclerView>(R.id.rv_payment_chat)
        chatList = rv
        adapter = PaymentChatAdapter()
        rv.apply {
            layoutManager = LinearLayoutManager(this@PaymentChatActivity)
            adapter = this@PaymentChatActivity.adapter
        }

        findViewById<View>(R.id.btn_attach_receipt).setOnClickListener {
            pickImage.launch("image/*")
        }

        findViewById<View>(R.id.btn_send_message).setOnClickListener {
            val et = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_chat_message)
            val text = et.text?.toString()?.trim().orEmpty()
            if (text.isBlank()) return@setOnClickListener
            et.text?.clear()
            NetworkRepository.sendPaymentMessage(orderId, text,
                onSuccess = { loadMessages() },
                onError = { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
            )
        }

        loadMessages()
        startPaymentPolling()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        stopPaymentPolling()
        super.onDestroy()
    }

    private fun loadMessages() {
        NetworkRepository.fetchPaymentMessages(orderId,
            onSuccess = { msgs ->
                main.post {
                    adapter.submit(msgs)
                    chatList.scrollToPosition((msgs.size - 1).coerceAtLeast(0))
                }
            },
            onError = { }
        )
    }

    private fun uploadReceipt(uri: Uri) {
        Toast.makeText(this, "Enviando comprobante…", Toast.LENGTH_SHORT).show()
        NetworkRepository.uploadReceipt(orderId, contentResolver, uri,
            onSuccess = {
                main.post {
                    Toast.makeText(this, "Comprobante enviado ✅", Toast.LENGTH_SHORT).show()
                    loadMessages()
                }
            },
            onError = { msg ->
                main.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
            }
        )
    }

    private fun startPaymentPolling() {
        stopPaymentPolling()
        pollRunnable = object : Runnable {
            override fun run() {
                NetworkRepository.fetchPaymentStatus(orderId,
                    onSuccess = { status ->
                        if (status.paid) {
                            TripRepository.onPaymentApproved()
                            main.post {
                                Toast.makeText(
                                    this@PaymentChatActivity,
                                    "Pago confirmado 🎉",
                                    Toast.LENGTH_LONG
                                ).show()
                                finish()
                            }
                        } else {
                            main.postDelayed(this, 4000)
                        }
                    },
                    onError = { main.postDelayed(this, 5000) }
                )
            }
        }
        main.postDelayed(pollRunnable!!, 4000)
    }

    private fun stopPaymentPolling() {
        pollRunnable?.let { main.removeCallbacks(it) }
        pollRunnable = null
    }

    private fun formatGs(value: Int): String =
        "%,d".format(Locale("es", "PY"), value).replace(',', '.')

    companion object {
        const val EXTRA_ORDER_ID = "order_id"
        const val EXTRA_AMOUNT_GS = "amount_gs"
        const val EXTRA_ALIAS = "alias"
        const val EXTRA_CEDULA = "cedula"
    }
}

private class PaymentChatAdapter : RecyclerView.Adapter<PaymentChatAdapter.VH>() {

    private val items = mutableListOf<PaymentMessage>()

    fun submit(list: List<PaymentMessage>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val v = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_payment_message, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val container = itemView.findViewById<LinearLayout>(R.id.bubble_container)
        private val body = itemView.findViewById<TextView>(R.id.tv_message_body)
        private val image = itemView.findViewById<ImageView>(R.id.iv_receipt)
        private val time = itemView.findViewById<TextView>(R.id.tv_message_time)

        fun bind(msg: PaymentMessage) {
            val isClient = msg.sender == "client"
            val lp = container.layoutParams as FrameLayout.LayoutParams
            lp.gravity = if (isClient) Gravity.END else Gravity.START
            container.layoutParams = lp

            when (msg.type) {
                "image" -> {
                    body.visibility = View.GONE
                    image.visibility = View.VISIBLE
                    image.setImageDrawable(null)
                    val url = msg.imageUrl?.let { resolveUrl(it) }
                    if (url != null) {
                        Thread {
                            try {
                                val bmp = BitmapFactory.decodeStream(URL(url).openStream())
                                image.post { image.setImageBitmap(bmp) }
                            } catch (_: Exception) { }
                        }.start()
                    }
                }
                else -> {
                    image.visibility = View.GONE
                    body.visibility = View.VISIBLE
                    body.text = msg.body
                }
            }

            time.text = msg.createdAt?.let { formatTime(it) } ?: ""
        }

        private fun resolveUrl(path: String): String {
            if (path.startsWith("http")) return path
            return ApiClient.BASE_URL.trimEnd('/') + path
        }

        private fun formatTime(iso: String): String {
            return try {
                val inFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                val outFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                outFmt.format(inFmt.parse(iso.substring(0, 19))!!)
            } catch (_: Exception) {
                ""
            }
        }
    }
}
