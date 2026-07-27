package com.example.zuppon.util

import android.content.Context
import com.example.zuppon.model.AuthUser
import com.google.gson.Gson

object UserSession {

    private const val PREFS = "zuppon_auth"
    private const val KEY_TOKEN = "token"
    private const val KEY_USER = "user"

    private var appContext: Context? = null
    private val gson = Gson()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun save(token: String, user: AuthUser) {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_TOKEN, token)
            ?.putString(KEY_USER, gson.toJson(user))
            ?.commit()
    }

    fun updateUser(user: AuthUser) {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_USER, gson.toJson(user))
            ?.commit()
    }

    fun getToken(): String? =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getString(KEY_TOKEN, null)

    fun getUser(): AuthUser? {
        val json = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getString(KEY_USER, null) ?: return null
        return try {
            gson.fromJson(json, AuthUser::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun isLoggedIn(): Boolean = !getToken().isNullOrBlank()

    fun needsUsername(): Boolean = getUser()?.needs_username == true

    fun clear() {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.clear()
            ?.apply()
    }
}
