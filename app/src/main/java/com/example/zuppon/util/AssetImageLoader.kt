package com.example.zuppon.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import com.example.zuppon.network.ApiClient
import okhttp3.Request
import java.io.IOException

/**
 * Imágenes del menú: assets/food/ o fotos subidas en el servidor (/uploads/menu/…).
 */
object AssetImageLoader {

    private val cache = LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    )

    fun load(
        context: Context,
        assetPath: String,
        into: ImageView,
        remoteUrl: String? = null
    ) {
        val url = remoteUrl?.takeIf { it.isNotBlank() }
            ?: serverUrlFor(assetPath)

        if (url != null) {
            loadRemote(url, into)
            return
        }

        if (assetPath.isBlank()) return

        val key = "asset:$assetPath"
        cache.get(key)?.let {
            into.setImageBitmap(it)
            return
        }

        Thread {
            val bitmap = decodeSampled(context, "food/$assetPath", into.width.coerceAtLeast(400), 300)
            bitmap?.let {
                cache.put(key, it)
                into.post { into.setImageBitmap(it) }
            }
        }.start()
    }

    private fun serverUrlFor(assetPath: String): String? {
        if (!assetPath.startsWith("menu/")) return null
        val base = ApiClient.BASE_URL.trimEnd('/')
        return "$base/uploads/$assetPath"
    }

    private fun loadRemote(url: String, into: ImageView) {
        val key = "url:$url"
        cache.get(key)?.let {
            into.setImageBitmap(it)
            return
        }

        Thread {
            val bitmap = fetchBitmap(url)
            bitmap?.let {
                cache.put(key, it)
                into.post { into.setImageBitmap(it) }
            }
        }.start()
    }

    private fun fetchBitmap(url: String): Bitmap? {
        return try {
            val request = Request.Builder().url(url).get().build()
            ApiClient.okHttp.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.bytes() ?: return null
                BitmapFactory.decodeByteArray(body, 0, body.size)
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun decodeSampled(context: Context, path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) }

            opts.inSampleSize = calculateInSampleSize(opts, reqWidth, reqHeight)
            opts.inJustDecodeBounds = false
            context.assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: IOException) {
            null
        }
    }

    private fun calculateInSampleSize(opts: BitmapFactory.Options, reqW: Int, reqH: Int): Int {
        val (h, w) = opts.outHeight to opts.outWidth
        var sample = 1
        if (h > reqH || w > reqW) {
            val hRatio = Math.round(h.toFloat() / reqH.toFloat())
            val wRatio = Math.round(w.toFloat() / reqW.toFloat())
            sample = minOf(hRatio, wRatio)
        }
        return sample
    }
}
