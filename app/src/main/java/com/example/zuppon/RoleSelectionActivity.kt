package com.example.zuppon

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.example.zuppon.databinding.ActivityRoleSelectionBinding
import com.example.zuppon.ui.driver.DriverActivity
import com.example.zuppon.ui.passenger.PassengerActivity
import com.example.zuppon.ui.profile.ProfileActivity
import com.example.zuppon.util.AssetImageLoader
import com.example.zuppon.util.UserSession

class RoleSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRoleSelectionBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRoleSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        AssetImageLoader.load(this, "hero.webp", binding.ivWelcomePhoto)

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

        binding.cardPassenger.setOnClickListener {
            startActivity(Intent(this, PassengerActivity::class.java))
        }

        binding.cardDriver.setOnClickListener {
            startActivity(Intent(this, DriverActivity::class.java))
        }

        binding.cardProfile.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        bindProfileCard()
    }

    private fun bindProfileCard() {
        val user = UserSession.getUser()
        if (UserSession.isLoggedIn() && user != null) {
            binding.tvProfileLabel.text = user.displayLabel()
            binding.tvProfileHint.text = "Mis pedidos · cerrar sesión"
        } else {
            binding.tvProfileLabel.text = "Invitado"
            binding.tvProfileHint.text = "Iniciar sesión · ver cuenta"
        }
    }

    private fun animateTap(view: View, onEnd: () -> Unit) {
        onEnd()
    }
}
