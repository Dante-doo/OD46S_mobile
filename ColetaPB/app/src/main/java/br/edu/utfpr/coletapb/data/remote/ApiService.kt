package br.edu.utfpr.coletapb.data.remote

import br.edu.utfpr.coletapb.data.model.LoginRequest
import br.edu.utfpr.coletapb.data.model.LoginResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface ApiService {

    // Define que é uma requisição POST para o endpoint "login"
    @POST("login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>
}