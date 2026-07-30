package com.example.zuppon.util

import com.example.zuppon.model.MenuItem

/** Catálogo en memoria (servidor o fallback local) para pantallas que abren por id. */
object MenuItemCache {

    private val byId = linkedMapOf<Int, MenuItem>()

    fun replaceAll(items: Iterable<MenuItem>) {
        byId.clear()
        merge(items)
    }

    fun merge(items: Iterable<MenuItem>) {
        for (item in items) {
            byId[item.id] = item
        }
    }

    fun get(id: Int): MenuItem? = byId[id]
}
