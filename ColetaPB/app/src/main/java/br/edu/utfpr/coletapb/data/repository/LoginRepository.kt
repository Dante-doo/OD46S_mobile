package br.edu.utfpr.coletapb.data.repository

import br.edu.utfpr.coletapb.data.model.LoginRequest
import br.edu.utfpr.coletapb.data.model.LoginResponse
import br.edu.utfpr.coletapb.data.remote.ApiService
import retrofit2.Response

class LoginRepository(private val apiService: ApiService) {

    suspend fun doLogin(email: String?, cpf: String?, password: String): Response<LoginResponse> {
        val loginRequest = LoginRequest(
            email = email,
            cpf = cpf,
            password = password
        )
        return apiService.login(loginRequest)
    }
}