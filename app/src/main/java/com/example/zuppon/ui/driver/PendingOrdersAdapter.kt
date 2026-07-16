package com.example.zuppon.ui.driver

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.zuppon.R
import com.example.zuppon.databinding.ItemPendingOrderBinding
import com.example.zuppon.model.TripRequest

/**
 * Adapter para la lista de pedidos PENDING que ve el repartidor.
 * Cada item tiene su propio server ID para que el botón Aceptar
 * llame al pedido correcto sin depender de un índice compartido.
 */
class PendingOrdersAdapter(
    private val onAccept: (serverId: Int, request: TripRequest) -> Unit,
    private val onReject: (serverId: Int) -> Unit
) : ListAdapter<PendingOrderItem, PendingOrdersAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPendingOrderBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val b: ItemPendingOrderBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(item: PendingOrderItem) {
            b.tvPendingItems.text       = item.request.passengerName
            b.tvPendingDestination.text = item.request.destination
            b.tvPendingFare.text        = formatGs(item.request.fare)
            b.btnAcceptOrder.setOnClickListener {
                onAccept(item.serverId, item.request)
            }
        }

        private fun formatGs(amount: Double) =
            "Gs %,d".format((amount * 7300).toLong()).replace(',', '.')
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<PendingOrderItem>() {
            override fun areItemsTheSame(a: PendingOrderItem, b: PendingOrderItem) =
                a.serverId == b.serverId
            override fun areContentsTheSame(a: PendingOrderItem, b: PendingOrderItem) =
                a == b
        }
    }
}

data class PendingOrderItem(
    val serverId: Int,
    val request: TripRequest
)
