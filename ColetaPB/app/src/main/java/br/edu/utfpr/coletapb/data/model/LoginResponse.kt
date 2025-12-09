package br.edu.utfpr.coletapb.data.model

// Dados que esperamos receber da API em caso de sucesso
data class LoginResponse(
    val token: String,
    val email: String,
    val name: String,
    val type: String, // ADMIN, DRIVER, USER
    val userId: Long? = null,
    val driverId: Long? = null,
    val adminId: Long? = null
)