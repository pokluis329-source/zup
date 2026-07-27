package com.example.zuppon.util

import android.content.Context
import com.example.zuppon.model.ActiveOrder
import com.example.zuppon.model.ActiveOrderPhase
import org.json.JSONObject

object ActiveOrderStorage {

    private const val PREFS = "zuppon_active_order"

    private fun key(): String = UserStorageScope.scopedKey("active_order")

    fun save(context: Context, order: ActiveOrder) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key(), toJson(order).toString())
            .commit()
    }

    fun load(context: Context): ActiveOrder? {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(), null) ?: return null
        return try {
            fromJson(JSONObject(json))
        } catch (_: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(key())
            .commit()
    }

    private fun toJson(o: ActiveOrder) = JSONObject().apply {
        put("serverOrderId", o.serverOrderId)
        put("items", o.items)
        put("destination", o.destination)
        put("fare", o.fare)
        put("amountGs", o.amountGs)
        put("alias", o.alias)
        put("cedula", o.cedula)
        put("phase", o.phase.name)
        put("driverName", o.driverName)
        put("driverVehicle", o.driverVehicle)
        put("createdAt", o.createdAt)
    }

    private fun fromJson(j: JSONObject) = ActiveOrder(
        serverOrderId = j.getInt("serverOrderId"),
        items = j.getString("items"),
        destination = j.getString("destination"),
        fare = j.getDouble("fare"),
        amountGs = j.getInt("amountGs"),
        alias = j.getString("alias"),
        cedula = j.getString("cedula"),
        phase = runCatching {
            ActiveOrderPhase.valueOf(j.getString("phase"))
        }.getOrDefault(ActiveOrderPhase.AWAITING_PAYMENT),
        driverName = j.optString("driverName").takeIf { it.isNotBlank() },
        driverVehicle = j.optString("driverVehicle").takeIf { it.isNotBlank() },
        createdAt = j.optLong("createdAt", System.currentTimeMillis())
    )
}
