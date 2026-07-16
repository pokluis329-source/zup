package com.example.zuppon.util

import android.content.Context
import com.example.zuppon.model.OrderRecord
import com.example.zuppon.model.OrderStatus
import org.json.JSONArray
import org.json.JSONObject

object OrderStorage {

    private const val PREFS_NAME  = "zuppon_orders"
    private const val KEY_HISTORY = "order_history"

    fun saveHistory(context: Context, orders: List<OrderRecord>) {
        val arr = JSONArray()
        orders.forEach { r ->
            arr.put(JSONObject().apply {
                put("id",          r.id)
                put("items",       r.items)
                put("destination", r.destination)
                put("fare",        r.fare)
                put("status",      r.status.name)
                put("timestamp",   r.timestamp)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    fun loadHistory(context: Context): List<OrderRecord> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_HISTORY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                OrderRecord(
                    id          = o.getLong("id"),
                    items       = o.getString("items"),
                    destination = o.getString("destination"),
                    fare        = o.getDouble("fare"),
                    status      = runCatching { OrderStatus.valueOf(o.getString("status")) }
                                    .getOrDefault(OrderStatus.PENDING),
                    timestamp   = o.getLong("timestamp")
                )
            }
        } catch (_: Exception) { emptyList() }
    }
}
