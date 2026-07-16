package com.example.zuppon.ui.passenger

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.zuppon.R
import com.example.zuppon.model.FoodMenu
import com.example.zuppon.model.MenuItem
import com.example.zuppon.util.AssetImageLoader
import java.util.Locale

class FoodMenuAdapter(
    quantities: Map<Int, Int>,
    private val onAdd: (MenuItem) -> Unit,
    private val onRemove: (MenuItem) -> Unit,
    private val onDetail: (MenuItem) -> Unit = {}
) : ListAdapter<MenuItem, FoodMenuAdapter.ViewHolder>(DIFF) {

    // Mutable para actualizar sin recrear el adapter
    private var quantities: Map<Int, Int> = quantities

    /** Actualiza las cantidades y solo repinta los items que cambiaron */
    fun updateQuantities(newQty: Map<Int, Int>) {
        val changed = mutableListOf<Int>()
        val allIds = (quantities.keys + newQty.keys).toSet()
        for (id in allIds) {
            if (quantities[id] != newQty[id]) changed.add(id)
        }
        quantities = newQty
        // Notificar solo los items modificados — O(k) en vez de rebind completo
        for (i in 0 until itemCount) {
            if (getItem(i).id in changed) notifyItemChanged(i, PAYLOAD_QTY)
        }
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val foodBg: View           = itemView.findViewById(R.id.v_food_bg)
        val foodPhoto: ImageView   = itemView.findViewById(R.id.iv_food_photo)
        val popularBadge: TextView = itemView.findViewById(R.id.tv_popular_badge)
        val name: TextView         = itemView.findViewById(R.id.tv_item_name)
        val description: TextView  = itemView.findViewById(R.id.tv_item_description)
        val price: TextView        = itemView.findViewById(R.id.tv_item_price)
        val counter: LinearLayout  = itemView.findViewById(R.id.layout_counter)
        val btnMinus: TextView     = itemView.findViewById(R.id.btn_minus)
        val tvQuantity: TextView   = itemView.findViewById(R.id.tv_quantity)
        val btnPlus: TextView      = itemView.findViewById(R.id.btn_plus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_food_menu, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val qty  = quantities[item.id] ?: 0

        // Gradiente como placeholder
        holder.foodBg.setBackgroundResource(FoodMenu.backgroundFor(item.category))

        // Foto real desde assets
        if (item.assetImage.isNotEmpty()) {
            holder.foodPhoto.visibility = View.VISIBLE
            holder.foodPhoto.setImageBitmap(null)
            AssetImageLoader.load(holder.foodPhoto.context, item.assetImage, holder.foodPhoto)
        } else {
            holder.foodPhoto.visibility = View.INVISIBLE
        }

        holder.popularBadge.visibility = if (item.isPopular) View.VISIBLE else View.GONE
        holder.name.text        = item.name
        holder.description.text = item.description
        holder.price.text = "Gs %,d".format((item.price * 7300).toLong()).replace(',', '.')

        bindQty(holder, item, qty)

        // Tap en la foto o el nombre → detalle
        holder.foodPhoto.setOnClickListener { onDetail(item) }
        holder.foodBg.setOnClickListener    { onDetail(item) }
        holder.name.setOnClickListener      { onDetail(item) }

        // Tap en +/- → carrito directo sin abrir detalle
        holder.btnPlus.setOnClickListener  { punch(holder.counter); onAdd(item) }
        holder.btnMinus.setOnClickListener { onRemove(item) }
    }

    // Partial bind — solo actualiza el contador sin tocar foto ni texto
    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: List<Any>) {
        if (payloads.contains(PAYLOAD_QTY)) {
            val item = getItem(position)
            bindQty(holder, item, quantities[item.id] ?: 0)
            // Re-asignar listeners por si el item reciclado tiene referencias viejas
            holder.btnPlus.setOnClickListener  { punch(holder.counter); onAdd(item) }
            holder.btnMinus.setOnClickListener { onRemove(item) }
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    private fun bindQty(holder: ViewHolder, item: MenuItem, qty: Int) {
        if (qty > 0) {
            holder.btnMinus.visibility   = View.VISIBLE
            holder.tvQuantity.visibility = View.VISIBLE
            holder.tvQuantity.text       = qty.toString()
        } else {
            holder.btnMinus.visibility   = View.GONE
            holder.tvQuantity.visibility = View.GONE
        }
    }

    private fun punch(view: View) {
        view.animate().scaleX(1.18f).scaleY(1.18f).setDuration(70).withEndAction {
            view.animate().scaleX(1f).scaleY(1f)
                .setDuration(160).setInterpolator(OvershootInterpolator(3f)).start()
        }.start()
    }

    companion object {
        private const val PAYLOAD_QTY = "qty"
        private val DIFF = object : DiffUtil.ItemCallback<MenuItem>() {
            override fun areItemsTheSame(a: MenuItem, b: MenuItem) = a.id == b.id
            override fun areContentsTheSame(a: MenuItem, b: MenuItem) = a == b
        }
    }
}
