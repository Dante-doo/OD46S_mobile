package br.edu.utfpr.coletapb.data.model

// Dados que esperamos receber da API em caso de sucesso
data class LoginResponse(
    val token: String,
    val userName: String
)