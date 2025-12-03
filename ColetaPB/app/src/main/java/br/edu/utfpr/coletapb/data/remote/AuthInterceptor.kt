package br.edu.utfpr.coletapb.data.remote

import android.content.Context
import android.util.Log
import br.edu.utfpr.coletapb.data.local.SharedPreferencesHelper
import br.edu.utfpr.coletapb.data.repository.TokenRepository
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Interceptor para adicionar automaticamente o token JWT nas requisições
 * e tratar erros 401 (não autorizado) tentando renovar o token antes de limpar
 * 
 * Nota: O redirecionamento para login deve ser feito pela Activity que recebeu o erro
 */
class AuthInterceptor(private val context: Context) : Interceptor {
    
    private val prefsHelper = SharedPreferencesHelper(context)
    private val tokenRepository = TokenRepository(prefsHelper, context)
    
    @Volatile
    private var isRefreshing = false
    
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        
        // Não adiciona token para endpoints públicos (login, refresh, health)
        if (originalRequest.url.encodedPath.contains("/auth/login") || 
            originalRequest.url.encodedPath.contains("/auth/refresh") ||
            originalRequest.url.encodedPath.contains("/auth/health")) {
            return chain.proceed(originalRequest)
        }
        
        val token = prefsHelper.getToken()
        
        val newRequest = if (token != null) {
            originalRequest.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            originalRequest
        }
        
        var response = chain.proceed(newRequest)
        
        // Se receber 401 (Unauthorized), tenta renovar o token antes de limpar
        if (response.code == 401 && !isRefreshing) {
            Log.w("AuthInterceptor", "Token inválido ou expirado (401). Tentando renovar...")
            
            // Tenta renovar o token
            val refreshSuccess = runBlocking {
                isRefreshing = true
                try {
                    tokenRepository.refreshToken()
                } catch (e: Exception) {
                    Log.e("AuthInterceptor", "Erro ao tentar renovar token: ${e.message}")
                    false
                } finally {
                    isRefreshing = false
                }
            }
            
            if (refreshSuccess) {
                // Se conseguiu renovar, tenta a requisição novamente com o novo token
                val newToken = prefsHelper.getToken()
                if (newToken != null) {
                    Log.d("AuthInterceptor", "Token renovado com sucesso. Reenviando requisição...")
                    response.close() // Fecha a resposta anterior
                    val retryRequest = originalRequest.newBuilder()
                        .header("Authorization", "Bearer $newToken")
                        .build()
                    response = chain.proceed(retryRequest)
                }
            } else {
                // Se não conseguiu renovar, apenas loga o erro
                // NÃO limpa o token automaticamente - o token permanece para tentar novamente
                // A Activity que receber o erro 401 deve tratar o redirecionamento se necessário
                Log.e("AuthInterceptor", "Não foi possível renovar o token. Token mantido para tentar novamente.")
            }
        }
        
        return response
    }
}

