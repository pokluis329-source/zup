package com.example.zuppon.ui.passenger

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.example.zuppon.databinding.ActivityProductDetailBinding
import com.example.zuppon.model.FoodMenu
import com.example.zuppon.model.MenuItem
import com.example.zuppon.util.AssetImageLoader
import java.util.Locale

class ProductDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProductDetailBinding
    private var quantity = 1
    private lateinit var item: MenuItem

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProductDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val itemId = intent.getIntExtra(EXTRA_ITEM_ID, -1)
        item = FoodMenu.items.find { it.id == itemId }
            ?: run { finish(); return }

        bindItem()
        setupListeners()
        animateEntrance()
    }

    private fun bindItem() {
        // Foto + gradiente fallback
        binding.vDetailBg.setBackgroundResource(FoodMenu.backgroundFor(item.category))
        if (item.assetImage.isNotEmpty()) {
            AssetImageLoader.load(this, item.assetImage, binding.ivDetailPhoto)
        }

        binding.tvDetailName.text          = item.name
        binding.tvDetailCategory.text      = item.category
        binding.tvDetailDescription.text   = item.description
        binding.tvDetailPriceOverlay.text  =
            "Gs %,d".format((item.price * 7300).toLong()).replace(',', '.')
        binding.tvDetailBadge.visibility   = if (item.isPopular) View.VISIBLE else View.GONE

        // Calorías aproximadas según precio (demo)
        val kcal = (item.price * 60).toInt()
        binding.tvDetailCalories.text = kcal.toString()

        updateQtyDisplay()
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finishWithAnimation()
        }

        binding.btnDetailMinus.setOnClickListener {
            if (quantity > 1) {
                quantity--
                updateQtyDisplay()
                punch(binding.btnDetailMinus)
            }
        }

        binding.btnDetailPlus.setOnClickListener {
            quantity++
            updateQtyDisplay()
            punch(binding.btnDetailPlus)
        }

        binding.btnAddToCart.setOnClickListener {
            punch(it)
            // Devolver resultado al PassengerActivity
            val result = Intent().apply {
                putExtra(EXTRA_ITEM_ID, item.id)
                putExtra(EXTRA_QUANTITY, quantity)
            }
            setResult(RESULT_OK, result)
            finishWithAnimation()
        }
    }

    private fun updateQtyDisplay() {
        binding.tvDetailQty.text = quantity.toString()
        val total = item.price * quantity
        val gs = (total * 7300).toLong()
        binding.btnAddToCart.text = "Agregar  ·  Gs %,d".format(gs).replace(',', '.')
    }

    private fun animateEntrance() {
        // El panel de info sube desde abajo
        val panel = binding.root.getChildAt(0)?.let {
            (it as? android.view.ViewGroup)?.getChildAt(1)
        } ?: return
        panel.translationY = 200f
        panel.alpha = 0f
        panel.animate()
            .translationY(0f).alpha(1f)
            .setDuration(450)
            .setStartDelay(100)
            .setInterpolator(OvershootInterpolator(0.7f))
            .start()
    }

    private fun finishWithAnimation() {
        @Suppress("DEPRECATION")
        overridePendingTransition(0, android.R.anim.fade_out)
        finish()
    }

    private fun punch(view: View) {
        view.animate().scaleX(0.88f).scaleY(0.88f).setDuration(70).withEndAction {
            view.animate().scaleX(1f).scaleY(1f)
                .setDuration(200).setInterpolator(OvershootInterpolator(3f)).start()
        }.start()
    }

    companion object {
        const val EXTRA_ITEM_ID  = "item_id"
        const val EXTRA_QUANTITY = "quantity"

        fun start(context: Context, itemId: Int) {
            context.startActivity(
                Intent(context, ProductDetailActivity::class.java)
                    .putExtra(EXTRA_ITEM_ID, itemId)
            )
        }

        fun startForResult(activity: androidx.activity.ComponentActivity,
                           itemId: Int, launcher: androidx.activity.result.ActivityResultLauncher<Intent>) {
            launcher.launch(
                Intent(activity, ProductDetailActivity::class.java)
                    .putExtra(EXTRA_ITEM_ID, itemId)
            )
        }
    }
}
