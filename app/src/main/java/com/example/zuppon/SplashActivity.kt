package com.example.zuppon

import android.content.Intent
import android.os.Bundle
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.example.zuppon.auth.AuthRepository
import com.example.zuppon.auth.LoginActivity
import com.example.zuppon.auth.UsernameActivity
import com.example.zuppon.databinding.ActivitySplashBinding
import com.example.zuppon.util.AssetImageLoader
import com.example.zuppon.util.UserSession

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private var routed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        AssetImageLoader.load(this, "hero.webp", binding.ivHero)

        Thread { com.example.zuppon.network.ApiClient.api }.start()

        binding.cardInfo.translationY = 300f
        binding.cardInfo.alpha = 0f
        binding.cardInfo.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(600)
            .setStartDelay(200)
            .setInterpolator(OvershootInterpolator(0.6f))
            .start()

        binding.tvDescTitle.alpha = 0f
        binding.tvDescTitle.animate()
            .alpha(1f)
            .setDuration(500)
            .setStartDelay(500)
            .setInterpolator(DecelerateInterpolator())
            .start()

        binding.btnGetStarted.setOnClickListener { routeFromSession() }

        binding.root.postDelayed({ routeFromSession() }, 1200)
    }

    private fun routeFromSession() {
        if (routed) return
        routed = true

        binding.btnGetStarted.isEnabled = false
        binding.btnGetStarted.text = "Cargando…"

        val token = UserSession.getToken()
        if (token.isNullOrBlank()) {
            goTo(LoginActivity::class.java)
            return
        }

        AuthRepository.fetchMe(
            onSuccess = { user ->
                if (user.needs_username) {
                    goTo(UsernameActivity::class.java)
                } else {
                    goTo(RoleSelectionActivity::class.java)
                }
            },
            onError = {
                UserSession.clear()
                goTo(LoginActivity::class.java)
            }
        )
    }

    private fun goTo(target: Class<*>) {
        startActivity(Intent(this, target))
        finish()
    }
}
