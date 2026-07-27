package com.example.zuppon.util

/** Claves de SharedPreferences separadas por usuario logueado. */
object UserStorageScope {

    fun suffix(): String {
        val id = UserSession.getUser()?.id?.takeIf { it > 0 }
        return if (id != null) "u$id" else "guest"
    }

    fun scopedKey(prefix: String): String = "${prefix}_${suffix()}"
}
