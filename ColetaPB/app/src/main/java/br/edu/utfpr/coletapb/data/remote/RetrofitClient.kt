package br.edu.utfpr.coletapb.data.remote

import android.content.Context
import br.edu.utfpr.coletapb.config.ApiConfig
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

// Usamos 'object' para criar um Singleton. Isso garante que teremos apenas
// uma instância do Retrofit em todo o app.
object RetrofitClient {
    
    private var context: Context? = null
    
    fun init(context: Context) {
        this.context = context.applicationContext
    }

    // Cria a instância do Retrofit usando um builder
    private val retrofit: Retrofit by lazy {
        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
        
        // Adiciona interceptor de autenticação se o contexto estiver disponível
        context?.let {
            clientBuilder.addInterceptor(AuthInterceptor(it))
        }
        
        Retrofit.Builder()
            .baseUrl(ApiConfig.BASE_URL) // 1. Define a URL base para todas as chamadas
            .client(clientBuilder.build())
            .addConverterFactory(GsonConverterFactory.create()) // 2. Adiciona o conversor Gson
            .build() // 3. Constrói o objeto Retrofit
    }

    // Cria a implementação da nossa interface ApiService de forma "preguiçosa" (lazy)
    // O código dentro do 'by lazy' só será executado na primeira vez que 'apiService' for chamado.
    val apiService: ApiService by lazy {
        retrofit.create(ApiService::class.java)
    }
}