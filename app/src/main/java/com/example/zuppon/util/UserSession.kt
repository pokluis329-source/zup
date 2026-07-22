package com.example.zuppon.util

import android.content.Context

object UserSession {

    private const val PREFS = "zuppon_user"
    private const val KEY_LOGGED_IN = "logged_in"
    private const val KEY_NAME = "name"
    private const val KEY_EMAIL = "email"
    private const val KEY_PHOTO = "photo"
    private const val KEY_GOOGLE_ID = "google_id"

    fun isLoggedIn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOGGED_IN, false)

    fun save(
        context: Context,
        name: String?,
        email: String?,
        photoUrl: String?,
        googleId: String?
    ) {
        prefs(context).edit()
            .putBoolean(KEY_LOGGED_IN, true)
            .putString(KEY_NAME, name.orEmpty())
            .putString(KEY_EMAIL, email.orEmpty())
            .putString(KEY_PHOTO, photoUrl)
            .putString(KEY_GOOGLE_ID, googleId.orEmpty())
            .apply()
    }

    fun name(context: Context): String =
        prefs(context).getString(KEY_NAME, "").orEmpty()

    fun email(context: Context): String =
        prefs(context).getString(KEY_EMAIL, "").orEmpty()

    fun photoUrl(context: Context): String? =
        prefs(context).getString(KEY_PHOTO, null)

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
