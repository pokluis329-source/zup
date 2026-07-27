package com.example.zuppon.model

data class AuthUser(
    val id: Int = 0,
    val email: String? = null,
    val display_name: String? = null,
    val username: String? = null,
    val needs_username: Boolean = false
) {
    fun displayLabel(): String =
        username?.let { "@$it" } ?: display_name ?: email ?: "Usuario"
}

data class AuthResponse(
    val token: String = "",
    val user: AuthUser = AuthUser()
)

data class UserResponse(
    val user: AuthUser = AuthUser()
)

data class GoogleAuthRequest(
    val id_token: String
)

data class UsernameRequest(
    val username: String
)
