package br.edu.utfpr.coletapb.data.model

data class LoginRequest(
    val email: String? = null,
    val cpf: String? = null,
    val password: String
)