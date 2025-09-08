package br.edu.utfpr.coletapb.data.remote

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

// Usamos 'object' para criar um Singleton. Isso garante que teremos apenas
// uma instância do Retrofit em todo o app.
object RetrofitClient {

    // SUBSTITUA PELA URL BASE DA SUA API
    private const val BASE_URL = "http://192.168.0.1:8080/api/" // Exemplo: IP local

    // Cria a instância do Retrofit usando um builder
    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL) // 1. Define a URL base para todas as chamadas
            .addConverterFactory(GsonConverterFactory.create()) // 2. Adiciona o conversor Gson
            .build() // 3. Constrói o objeto Retrofit
    }

    // Cria a implementação da nossa interface ApiService de forma "preguiçosa" (lazy)
    // O código dentro do 'by lazy' só será executado na primeira vez que 'apiService' for chamado.
    val apiService: ApiService by lazy {
        retrofit.create(ApiService::class.java)
    }
}