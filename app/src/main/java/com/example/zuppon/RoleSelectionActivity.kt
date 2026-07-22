package com.example.zuppon

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.example.zuppon.databinding.ActivityRoleSelectionBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.example.zuppon.ui.passenger.PassengerActivity
import com.example.zuppon.util.UserSession

class RoleSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRoleSelectionBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRoleSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val userName = UserSession.name(this).ifBlank { "Invitado" }
        binding.tvSelectRole.text = "Hola, $userName · ¿quién eres hoy?"

        binding.btnLogout.setOnClickListener {
            UserSession.clear(this)
            GoogleSignIn.getClient(
                this,
                GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
            ).signOut().addOnCompleteListener {
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
        }

        // Foto hero real
        AssetImageLoader.load(this, "hero.webp", binding.ivWelcomePhoto)

        // Animación de entrada
        binding.tvSelectRole.alpha    = 0f
        binding.cardPassenger.translationY = 80f
        binding.cardPassenger.alpha   = 0f

        binding.tvSelectRole.animate()
            .alpha(1f).setDuration(400).setStartDelay(150)
            .setInterpolator(DecelerateInterpolator()).start()

        binding.cardPassenger.animate()
            .translationY(0f).alpha(1f)
            .setDuration(500).setStartDelay(250)
            .setInterpolator(OvershootInterpolator(0.9f)).start()

        // Cliente
        binding.cardPassenger.setOnClickListener {
            startActivity(Intent(this, PassengerActivity::class.java))
        }

        // Conductor
        binding.cardDriver.setOnClickListener {
            startActivity(Intent(this, DriverActivity::class.java))
        }
    }

    private fun animateTap(view: View, onEnd: () -> Unit) {
        onEnd()
    }
}
