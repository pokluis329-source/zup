package com.example.zuppon.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import android.widget.ImageView
import java.io.IOException

/**
 * Carga imágenes desde assets/food/ con LruCache en memoria.
 * No requiere dependencias externas.
 */
object AssetImageLoader {

    private val cache = LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    )

    fun load(context: Context, assetPath: String, into: ImageView) {
        if (assetPath.isBlank()) return

        val key = assetPath
        val cached = cache.get(key)
        if (cached != null) {
            into.setImageBitmap(cached)
            return
        }

        // Decodificar en background, aplicar en UI thread
        Thread {
            val bitmap = decodeSampled(context, "food/$assetPath", into.width.coerceAtLeast(400), 300)
            bitmap?.let {
                cache.put(key, it)
                into.post { into.setImageBitmap(it) }
            }
        }.start()
    }

    private fun decodeSampled(context: Context, path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            // Primera pasada: solo dimensiones
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) }

            opts.inSampleSize = calculateInSampleSize(opts, reqWidth, reqHeight)
            opts.inJustDecodeBounds = false
            context.assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: IOException) {
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
