package com.example.zuppon

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.example.zuppon.databinding.ActivitySplashBinding
import com.example.zuppon.util.AssetImageLoader

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Cargar foto hero real desde assets
        AssetImageLoader.load(this, "hero.webp", binding.ivHero)

        // Precalentar Retrofit + OkHttp en background para que la primera llamada
        // de red no sufra el overhead de inicialización (~150-200ms)
        Thread { com.example.zuppon.network.ApiClient.api }.start()

        // Animaciones de entrada
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

        binding.btnGetStarted.setOnClickListener {
            startActivity(Intent(this, RoleSelectionActivity::class.java))
            finish()
        }
    }
}
