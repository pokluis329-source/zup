package com.example.zuppon.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.ViewTreeObserver
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
        remoteUrl: String? = null,
        targetWidthPx: Int = 0,
        targetHeightPx: Int = 0
    ) {
        val url = remoteUrl?.takeIf { it.isNotBlank() } ?: serverUrlFor(assetPath)
        val token = url ?: "asset:$assetPath"
        if (url == null && assetPath.isBlank()) return

        into.tag = token

        val dm = context.resources.displayMetrics
        val fallbackW = (360 * dm.density).toInt().coerceAtLeast(320)
        val fallbackH = (260 * dm.density).toInt().coerceAtLeast(200)

        fun start(w: Int, h: Int) {
            if (into.tag != token) return
            if (url != null) {
                loadRemote(url, token, into, w, h)
            } else {
                loadAsset(context, assetPath, token, into, w, h)
            }
        }

        val w = targetWidthPx.takeIf { it > 0 } ?: into.width
        val h = targetHeightPx.takeIf { it > 0 } ?: into.height
        if (w > 0 && h > 0) {
            start(w, h)
            return
        }

        into.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                into.viewTreeObserver.removeOnPreDrawListener(this)
                val measuredW = into.width.takeIf { it > 0 } ?: fallbackW
                val measuredH = into.height.takeIf { it > 0 } ?: fallbackH
                start(measuredW, measuredH)
                return true
            }
        })
    }

    private fun serverUrlFor(assetPath: String): String? {
        if (!assetPath.startsWith("menu/")) return null
        val base = ApiClient.BASE_URL.trimEnd('/')
        return "$base/uploads/$assetPath"
    }

    private fun loadAsset(
        context: Context,
        assetPath: String,
        token: String,
        into: ImageView,
        reqW: Int,
        reqH: Int
    ) {
        val key = "asset:$assetPath:$reqW:$reqH"
        cache.get(key)?.let {
            if (into.tag == token) into.setImageBitmap(it)
            return
        }

        Thread {
            val bitmap = decodeSampled(context, "food/$assetPath", reqW, reqH)
            bitmap?.let {
                cache.put(key, it)
                into.post {
                    if (into.tag == token) into.setImageBitmap(it)
                }
            }
        }.start()
    }

    private fun loadRemote(
        url: String,
        token: String,
        into: ImageView,
        reqW: Int,
        reqH: Int
    ) {
        val key = "url:$url:$reqW:$reqH"
        cache.get(key)?.let {
            if (into.tag == token) into.setImageBitmap(it)
            return
        }

        Thread {
            val bitmap = fetchBitmap(url, reqW, reqH)
            bitmap?.let {
                cache.put(key, it)
                into.post {
                    if (into.tag == token) into.setImageBitmap(it)
                }
            }
        }.start()
    }

    private fun fetchBitmap(url: String, reqW: Int, reqH: Int): Bitmap? {
        return try {
            val request = Request.Builder().url(url).get().build()
            ApiClient.okHttp.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.bytes() ?: return null
                decodeSampledBytes(body, reqW, reqH)
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun decodeSampledBytes(data: ByteArray, reqWidth: Int, reqHeight: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, opts)
        opts.inSampleSize = calculateInSampleSize(opts, reqWidth, reqHeight)
        opts.inJustDecodeBounds = false
        return BitmapFactory.decodeByteArray(data, 0, data.size, opts)
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
            sample = minOf(hRatio, wRatio).coerceAtLeast(1)
        }
        return sample
    }
}
