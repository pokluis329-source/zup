package com.example.zuppon.ui.passenger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.zuppon.R
import com.example.zuppon.call.CallUiState
import com.example.zuppon.call.InAppVoiceCall
import com.example.zuppon.call.IncomingCallWatcher
import com.example.zuppon.call.VoiceCallState
import com.example.zuppon.model.PaymentMessage
import com.example.zuppon.network.ApiClient
import com.example.zuppon.network.NetworkRepository
import com.example.zuppon.repository.TripRepository
import java.io.ByteArrayOutputStream
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
    private var isDriver: Boolean = false
    private var contactName: String = ""
    private var contactPhone: String = ""
    private var messageSender: String = "client"
    private var voiceCall: InAppVoiceCall? = null
    private var callState: VoiceCallState = VoiceCallState.IDLE
    private var pendingAcceptAfterMic = false

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            pendingAcceptAfterMic = false
            Toast.makeText(this, "Se necesita el micrófono para llamar en la app", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        if (pendingAcceptAfterMic) {
            pendingAcceptAfterMic = false
            voiceCall?.acceptIncoming()
        } else {
            voiceCall?.startOutgoing()
        }
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (!isFinishing) {
                loadMessages(silent = true)
                main.postDelayed(this, 5000L)
            }
        }
    }

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
        isDriver = intent.getBooleanExtra(EXTRA_IS_DRIVER, false)
        contactName = intent.getStringExtra(EXTRA_CONTACT_NAME).orEmpty()
        contactPhone = intent.getStringExtra(EXTRA_CONTACT_PHONE).orEmpty()
        messageSender = if (isDriver) "driver" else "client"

        if (orderId == -1) {
            finish()
            return
        }

        supportActionBar?.title = if (isDriver) "Chat pedido #$orderId" else "Pago pedido #$orderId"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        findViewById<TextView>(R.id.tv_chat_title).text =
            if (isDriver) "Chat con el cliente" else "Transferencia y chat"

        val paymentInfo = findViewById<View>(R.id.layout_payment_info)
        if (isDriver) {
            paymentInfo.visibility = View.GONE
        } else {
            findViewById<TextView>(R.id.tv_payment_amount).text = "Gs ${formatGs(amountGs)}"
            findViewById<TextView>(R.id.tv_payment_alias).text = "Alias: $alias"
            findViewById<TextView>(R.id.tv_payment_cedula).text = "CI: $cedula"
        }

        setupContactHeader()
        setupVoiceCall()

        val attachBtn = findViewById<View>(R.id.btn_attach_receipt)
        attachBtn.visibility = if (isDriver) View.GONE else View.VISIBLE

        val rv = findViewById<RecyclerView>(R.id.rv_payment_chat)
        chatList = rv
        adapter = PaymentChatAdapter(isDriver)
        rv.apply {
            layoutManager = LinearLayoutManager(this@PaymentChatActivity)
            adapter = this@PaymentChatActivity.adapter
        }

        attachBtn.setOnClickListener { pickImage.launch("image/*") }

        findViewById<View>(R.id.btn_send_message).setOnClickListener {
            val et = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_chat_message)
            val text = et.text?.toString()?.trim().orEmpty()
            if (text.isBlank()) return@setOnClickListener
            et.text?.clear()
            NetworkRepository.sendPaymentMessage(
                orderId, text, messageSender,
                onSuccess = { loadMessages() },
                onError = { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
            )
        }

        loadMessages()
        if (isDriver) TripRepository.ensurePolling() else TripRepository.ensurePassengerPolling()

        if (intent.getBooleanExtra(EXTRA_AUTO_ACCEPT, false)) {
            main.postDelayed({ acceptIncomingCall() }, 350L)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AUTO_ACCEPT, false)) {
            acceptIncomingCall()
        }
    }

    override fun onStart() {
        super.onStart()
        CallUiState.setChatOpen(orderId, true)
        IncomingCallWatcher.dismissNotification(orderId)
    }

    override fun onStop() {
        CallUiState.setChatOpen(orderId, false)
        super.onStop()
    }

    private fun acceptIncomingCall() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingAcceptAfterMic = true
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        voiceCall?.acceptIncoming()
    }

    private fun setupContactHeader() {
        val nameTv = findViewById<TextView>(R.id.tv_contact_name)
        val phoneTv = findViewById<TextView>(R.id.tv_contact_phone)
        val callBtn = findViewById<View>(R.id.btn_call_contact)
        val phoneBtn = findViewById<View>(R.id.btn_call_phone)

        val otherLabel = if (isDriver) contactName.ifBlank { "Cliente" } else "Repartidor / soporte"
        nameTv.text = "👤 $otherLabel"
        nameTv.visibility = View.VISIBLE

        callBtn.setOnClickListener { beginInAppCall() }

        if (isDriver && contactPhone.isNotBlank()) {
            phoneTv.text = "📞 $contactPhone"
            phoneTv.visibility = View.VISIBLE
            phoneBtn.visibility = View.VISIBLE
            phoneBtn.setOnClickListener { dialPhone(contactPhone) }
        } else {
            phoneTv.visibility = View.GONE
            phoneBtn.visibility = View.GONE
        }
    }

    private fun setupVoiceCall() {
        voiceCall = InAppVoiceCall(this, orderId, messageSender) { state, message ->
            callState = state
            updateCallOverlay(state, message)
        }
        voiceCall?.startListening()

        findViewById<View>(R.id.btn_accept_call).setOnClickListener {
            acceptIncomingCall()
        }
        findViewById<View>(R.id.btn_reject_call).setOnClickListener {
            voiceCall?.rejectIncoming()
        }
        findViewById<View>(R.id.btn_end_call).setOnClickListener {
            voiceCall?.hangUp()
        }
    }

    private fun beginInAppCall() {
        pendingAcceptAfterMic = false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        voiceCall?.startOutgoing()
    }

    private fun updateCallOverlay(state: VoiceCallState, message: String) {
        val overlay = findViewById<View>(R.id.layout_call_overlay)
        val statusTv = findViewById<TextView>(R.id.tv_call_status)
        val incoming = findViewById<View>(R.id.layout_incoming_actions)
        val endBtn = findViewById<View>(R.id.btn_end_call)

        statusTv.text = message
        when (state) {
            VoiceCallState.IDLE, VoiceCallState.ENDED -> {
                overlay.visibility = View.GONE
                incoming.visibility = View.GONE
                endBtn.visibility = View.GONE
            }
            VoiceCallState.INCOMING -> {
                overlay.visibility = View.VISIBLE
                incoming.visibility = View.VISIBLE
                endBtn.visibility = View.GONE
            }
            VoiceCallState.CALLING, VoiceCallState.CONNECTING, VoiceCallState.CONNECTED -> {
                overlay.visibility = View.VISIBLE
                incoming.visibility = View.GONE
                endBtn.visibility = View.VISIBLE
            }
            VoiceCallState.FAILED -> {
                overlay.visibility = View.VISIBLE
                incoming.visibility = View.GONE
                endBtn.visibility = View.VISIBLE
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun dialPhone(phone: String) {
        val digits = phone.filter { it.isDigit() || it == '+' }
        if (digits.length < 6) {
            Toast.makeText(this, "Teléfono no válido", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits")))
    }

    override fun onResume() {
        super.onResume()
        main.postDelayed(refreshRunnable, 5000L)
    }

    override fun onPause() {
        main.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        voiceCall?.release()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadMessages(silent: Boolean = false) {
        NetworkRepository.fetchPaymentMessages(orderId,
            onSuccess = { msgs ->
                main.post {
                    adapter.submit(msgs)
                    chatList.scrollToPosition((msgs.size - 1).coerceAtLeast(0))
                }
            },
            onError = {
                if (!silent) main.post {
                    Toast.makeText(this, "No se pudo cargar el chat", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun uploadReceipt(uri: Uri) {
        Toast.makeText(this, "Enviando comprobante…", Toast.LENGTH_SHORT).show()
        Thread {
            val compressed = compressImage(uri)
            if (compressed == null) {
                main.post {
                    Toast.makeText(this, "No se pudo procesar la imagen", Toast.LENGTH_LONG).show()
                }
                return@Thread
            }
            NetworkRepository.uploadReceipt(
                orderId,
                compressed.first,
                compressed.second,
                onSuccess = {
                    main.post {
                        TripRepository.onReceiptUploaded()
                        Toast.makeText(this, "Comprobante enviado ✅", Toast.LENGTH_SHORT).show()
                        loadMessages()
                    }
                },
                onError = { msg ->
                    main.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
                }
            )
        }.start()
    }

    private fun compressImage(uri: Uri): Pair<ByteArray, String>? {
        val original = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        } ?: return null

        val maxSide = 1600
        val scaled = if (original.width > maxSide || original.height > maxSide) {
            val ratio = minOf(
                maxSide.toFloat() / original.width,
                maxSide.toFloat() / original.height
            )
            Bitmap.createScaledBitmap(
                original,
                (original.width * ratio).toInt().coerceAtLeast(1),
                (original.height * ratio).toInt().coerceAtLeast(1),
                true
            )
        } else {
            original
        }

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
        if (scaled !== original) scaled.recycle()
        original.recycle()
        return out.toByteArray() to "image/jpeg"
    }

    private fun formatGs(value: Int): String =
        "%,d".format(Locale("es", "PY"), value).replace(',', '.')

    companion object {
        const val EXTRA_ORDER_ID = "order_id"
        const val EXTRA_AMOUNT_GS = "amount_gs"
        const val EXTRA_ALIAS = "alias"
        const val EXTRA_CEDULA = "cedula"
        const val EXTRA_IS_DRIVER = "is_driver"
        const val EXTRA_CONTACT_NAME = "contact_name"
        const val EXTRA_CONTACT_PHONE = "contact_phone"
        const val EXTRA_AUTO_ACCEPT = "auto_accept"
    }
}

private class PaymentChatAdapter(
    private val isDriverView: Boolean
) : RecyclerView.Adapter<PaymentChatAdapter.VH>() {

    private val items = mutableListOf<PaymentMessage>()

    fun submit(list: List<PaymentMessage>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val v = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_payment_message, parent, false)
        return VH(v, isDriverView)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    class VH(itemView: View, private val isDriverView: Boolean) : RecyclerView.ViewHolder(itemView) {
        private val container = itemView.findViewById<LinearLayout>(R.id.bubble_container)
        private val body = itemView.findViewById<TextView>(R.id.tv_message_body)
        private val image = itemView.findViewById<ImageView>(R.id.iv_receipt)
        private val time = itemView.findViewById<TextView>(R.id.tv_message_time)

        fun bind(msg: PaymentMessage) {
            val isMine = if (isDriverView) msg.sender == "driver" else msg.sender == "client"
            val lp = container.layoutParams as FrameLayout.LayoutParams
            lp.gravity = if (isMine) Gravity.END else Gravity.START
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
