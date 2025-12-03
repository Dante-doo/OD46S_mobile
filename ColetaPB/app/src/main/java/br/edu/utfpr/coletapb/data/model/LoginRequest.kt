package br.edu.utfpr.coletapb.data.model

// Dados que serão enviados para a API no corpo da requisição
data class LoginRequest(
    val email: String? = null,
    val cpf: String? = null,
    val password: String
)