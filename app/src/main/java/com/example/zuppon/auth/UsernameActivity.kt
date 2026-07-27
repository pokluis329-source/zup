package com.example.zuppon.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.zuppon.R
import com.example.zuppon.RoleSelectionActivity
import com.example.zuppon.util.UserSession
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import android.widget.ProgressBar

class UsernameActivity : AppCompatActivity() {

    private lateinit var tilUsername: TextInputLayout
    private lateinit var etUsername: TextInputEditText
    private lateinit var progress: ProgressBar
    private lateinit var btnSave: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!UserSession.isLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_username)

        tilUsername = findViewById(R.id.til_username)
        etUsername = findViewById(R.id.et_username)
        progress = findViewById(R.id.progress_username)
        btnSave = findViewById(R.id.btn_save_username)

        btnSave.setOnClickListener { submitUsername() }
    }

    private fun submitUsername() {
        val raw = etUsername.text?.toString()?.trim()?.lowercase().orEmpty()
        tilUsername.error = null

        if (!raw.matches(Regex("^[a-z0-9_]{3,30}$"))) {
            tilUsername.error = "3–30 caracteres: a-z, 0-9, _"
            return
        }

        setLoading(true)
        AuthRepository.setUsername(
            raw,
            onSuccess = {
                setLoading(false)
                startActivity(Intent(this, RoleSelectionActivity::class.java))
                finish()
            },
            onError = { msg ->
                setLoading(false)
                tilUsername.error = msg
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                if (msg.contains("Token", ignoreCase = true) ||
                    msg.contains("expirad", ignoreCase = true) ||
                    msg.contains("Sesión", ignoreCase = true)
                ) {
                    UserSession.clear()
                    startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                }
            }
        )
    }

    private fun setLoading(loading: Boolean) {
        progress.visibility = if (loading) View.VISIBLE else View.GONE
        btnSave.isEnabled = !loading
        etUsername.isEnabled = !loading
    }
}
