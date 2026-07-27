package com.example.zuppon.auth

import android.os.Handler
import android.os.Looper
import com.example.zuppon.model.AuthResponse
import com.example.zuppon.model.AuthUser
import com.example.zuppon.model.GoogleAuthRequest
import com.example.zuppon.model.UsernameRequest
import com.example.zuppon.network.ApiClient
import com.example.zuppon.util.UserSession

object AuthRepository {

    private val main = Handler(Looper.getMainLooper())

    private fun bg(block: () -> Unit) {
        Thread {
            try { block() }
            catch (_: Exception) { }
        }.start()
    }

    private fun rawToken(): String? =
        UserSession.getToken()?.trim()?.takeIf { it.isNotBlank() }

    private fun bearerHeader(): String {
        val token = rawToken().orEmpty()
        return if (token.startsWith("Bearer ", ignoreCase = true)) token else "Bearer $token"
    }

    fun loginWithGoogle(
        idToken: String,
        onSuccess: (AuthResponse) -> Unit,
        onError: (String) -> Unit
    ) {
        val api = ApiClient.api ?: run {
            onError("Sin conexión al servidor")
            return
        }
        bg {
            val resp = api.authGoogle(GoogleAuthRequest(idToken)).execute()
            if (resp.isSuccessful) {
                val body = resp.body()!!
                val token = body.token.trim()
                if (token.isBlank()) {
                    main.post { onError("El servidor no devolvió un token válido") }
                    return@bg
                }
                UserSession.save(token, body.user)
                main.post { onSuccess(body.copy(token = token)) }
            } else {
                val msg = resp.errorBody()?.string()?.take(200) ?: "HTTP ${resp.code()}"
                main.post { onError(msg) }
            }
        }
    }

    fun fetchMe(
        onSuccess: (AuthUser) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        val token = rawToken()
        if (token == null) {
            onError("Sesión expirada")
            return
        }
        val api = ApiClient.api ?: run {
            onError("Sin conexión al servidor")
            return
        }
        bg {
            val resp = api.authMe(bearerHeader(), token, token).execute()
            if (resp.isSuccessful) {
                val user = resp.body()!!.user
                UserSession.updateUser(user)
                main.post { onSuccess(user) }
            } else {
                main.post { onError(parseError(resp.errorBody()?.string().orEmpty(), resp.code())) }
            }
        }
    }

    fun setUsername(
        username: String,
        onSuccess: (AuthUser) -> Unit,
        onError: (String) -> Unit
    ) {
        val token = rawToken()
        if (token == null) {
            onError("Sesión expirada. Volvé a iniciar sesión con Google.")
            return
        }
        val api = ApiClient.api ?: run {
            onError("Sin conexión al servidor")
            return
        }
        bg {
            val resp = api.setUsername(
                bearerHeader(),
                token,
                UsernameRequest(username = username, access_token = token)
            ).execute()
            if (resp.isSuccessful) {
                val user = resp.body()!!.user
                UserSession.updateUser(user)
                main.post { onSuccess(user) }
            } else {
                val raw = resp.errorBody()?.string().orEmpty()
                main.post { onError(parseError(raw, resp.code())) }
            }
        }
    }

    private fun parseError(raw: String, code: Int): String {
        if (raw.contains("error")) {
            return try {
                val err = org.json.JSONObject(raw).optString("error")
                err.ifBlank { "Error $code" }
            } catch (_: Exception) {
                raw.take(120).ifBlank { "Error $code" }
            }
        }
        return raw.take(120).ifBlank { "Error $code" }
    }
}
