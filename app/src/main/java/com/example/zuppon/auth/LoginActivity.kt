package com.example.zuppon.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.zuppon.R
import com.example.zuppon.RoleSelectionActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task
import com.google.android.material.button.MaterialButton
import android.widget.ProgressBar

class LoginActivity : AppCompatActivity() {

    private lateinit var googleClient: GoogleSignInClient
    private lateinit var progress: ProgressBar
    private lateinit var btnGoogle: MaterialButton

    private val googleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        handleGoogleResult(task)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        progress = findViewById(R.id.progress_login)
        btnGoogle = findViewById(R.id.btn_google_sign_in)

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.google_web_client_id))
            .requestEmail()
            .build()
        googleClient = GoogleSignIn.getClient(this, gso)

        btnGoogle.setOnClickListener {
            setLoading(true)
            googleClient.signOut().addOnCompleteListener {
                googleLauncher.launch(googleClient.signInIntent)
            }
        }

        findViewById<MaterialButton>(R.id.btn_explore_guest).setOnClickListener {
            startActivity(Intent(this, RoleSelectionActivity::class.java))
            finish()
        }
    }

    private fun handleGoogleResult(task: Task<GoogleSignInAccount>) {
        try {
            val account = task.getResult(ApiException::class.java)
            val idToken = account.idToken
            if (idToken.isNullOrBlank()) {
                setLoading(false)
                Toast.makeText(this, "No se obtuvo token de Google", Toast.LENGTH_LONG).show()
                return
            }
            AuthRepository.loginWithGoogle(
                idToken,
                onSuccess = { response ->
                    setLoading(false)
                    if (response.user.needs_username) {
                        startActivity(Intent(this, UsernameActivity::class.java))
                    } else {
                        startActivity(Intent(this, RoleSelectionActivity::class.java))
                    }
                    finish()
                },
                onError = { msg ->
                    setLoading(false)
                    Toast.makeText(this, "Error al iniciar sesión: $msg", Toast.LENGTH_LONG).show()
                }
            )
        } catch (e: ApiException) {
            setLoading(false)
            if (e.statusCode != 12501) {
                Toast.makeText(this, "Google Sign-In falló (${e.statusCode})", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        progress.visibility = if (loading) View.VISIBLE else View.GONE
        btnGoogle.isEnabled = !loading
    }
}
