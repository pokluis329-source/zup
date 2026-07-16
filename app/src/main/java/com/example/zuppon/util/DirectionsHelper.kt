package com.example.zuppon.util

import android.util.Log
import com.google.android.gms.maps.model.LatLng
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Llama a la Directions API de Google y devuelve una lista de puntos
 * que forman la ruta real por calles (como GPS navigation).
 *
 * Optimizaciones:
 * - overview_polyline: 1 sola cadena decodificada en vez de N pasos → ~80% menos JSON
 * - Timeout explícito de 8s para no bloquear indefinidamente
 * - Cache en memoria: misma ruta (<50m de diferencia) no re-llama a la API
 */
object DirectionsHelper {

    // ── Cache de última ruta ──────────────────────────────────────────────────
    private data class RouteCache(
        val originLat: Double, val originLng: Double,
        val destLat: Double,   val destLng: Double,
        val points: List<LatLng>,
        val timestamp: Long = System.currentTimeMillis()
    )
    private var lastCache: RouteCache? = null
    private const val CACHE_TTL_MS    = 30_000L  // 30s de validez
    private const val CACHE_DIST_DEG  = 0.0005   // ~55m — si origen/destino no cambiaron tanto, reusar

    fun getRoute(apiKey: String, origin: LatLng, destination: LatLng): List<LatLng> {
        // Devolver cache si la ruta es prácticamente la misma y no expiró
        lastCache?.let { c ->
            val age  = System.currentTimeMillis() - c.timestamp
            val sameOrigin = Math.abs(c.originLat - origin.latitude)    < CACHE_DIST_DEG &&
                             Math.abs(c.originLng - origin.longitude)   < CACHE_DIST_DEG
            val sameDest   = Math.abs(c.destLat   - destination.latitude)  < CACHE_DIST_DEG &&
                             Math.abs(c.destLng   - destination.longitude) < CACHE_DIST_DEG
            if (sameOrigin && sameDest && age < CACHE_TTL_MS) return c.points
        }

        return try {
            val url = "https://maps.googleapis.com/maps/api/directions/json" +
                "?origin=${origin.latitude},${origin.longitude}" +
                "&destination=${destination.latitude},${destination.longitude}" +
                "&mode=driving" +
                "&language=es" +
                "&overview=full" +   // overview_polyline — 1 cadena en lugar de N steps
                "&key=$apiKey"

            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout    = 8_000
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json   = JSONObject(response)
            val status = json.getString("status")
            if (status != "OK") {
                Log.w("DirectionsHelper", "Status: $status")
                return emptyList()
            }

            val routes = json.getJSONArray("routes")
            if (routes.length() == 0) return emptyList()

            // overview_polyline es una sola cadena que representa toda la ruta
            val overviewPolyline = routes.getJSONObject(0)
                .getJSONObject("overview_polyline")
                .getString("points")

            val points = decodePolyline(overviewPolyline)

            // Guardar en cache
            lastCache = RouteCache(
                originLat = origin.latitude,  originLng = origin.longitude,
                destLat   = destination.latitude, destLng = destination.longitude,
                points    = points
            )
            points
        } catch (e: Exception) {
            Log.e("DirectionsHelper", "Error: ${e.message}")
            emptyList()
        }
    }

    /** Limpia la cache (llamar cuando cambia el destino) */
    fun clearCache() { lastCache = null }

    /** Decodifica el formato de polyline encodeada de Google */
    private fun decodePolyline(encoded: String): List<LatLng> {
        val poly = mutableListOf<LatLng>()
        var index = 0
        var lat = 0
        var lng = 0

        while (index < encoded.length) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dLat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dLat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dLng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dLng

            poly.add(LatLng(lat / 1E5, lng / 1E5))
        }
        return poly
    }
}
