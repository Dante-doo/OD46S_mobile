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
                // Se não conseguiu renovar, verifica se o token está expirado
                val token = prefsHelper.getToken()
                if (token != null) {
                    val isExpired = br.edu.utfpr.coletapb.utils.TokenUtils.isTokenExpired(token)
                    if (isExpired) {
                        Log.e("AuthInterceptor", "Token expirado e não foi possível renovar. Limpando token.")
                        prefsHelper.clearAll()
                    } else {
                        // Se o backend retornou 401 e a renovação também falhou com 401,
                        // significa que o token foi invalidado no backend (revogado, logout, etc.)
                        // Mesmo que o TokenUtils diga que não está expirado, devemos limpar
                        // para evitar loops infinitos de requisições falhando
                        Log.e("AuthInterceptor", "Backend rejeitou o token (401) e renovação falhou. " +
                                "Token pode ter sido revogado no servidor. Limpando token para forçar novo login.")
                        prefsHelper.clearAll()
                    }
                }
            }
        }
        
        return response
    }
}

