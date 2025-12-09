package br.edu.utfpr.coletapb.data.repository

import android.content.Context
import android.util.Log
import br.edu.utfpr.coletapb.data.local.SharedPreferencesHelper
import br.edu.utfpr.coletapb.data.remote.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TokenRepository(private val prefsHelper: SharedPreferencesHelper, private val context: Context? = null) {
    
    /**
     * Tenta renovar o token JWT usando o token atual
     * Retorna true se conseguiu renovar, false caso contrário
     */
    suspend fun refreshToken(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Garante que RetrofitClient está inicializado
            context?.let { RetrofitClient.init(it) }
            
            val currentToken = prefsHelper.getToken()
            if (currentToken == null) {
                Log.w("TokenRepository", "Nenhum token encontrado para renovar")
                return@withContext false
            }
            
            val request = mapOf("token" to currentToken)
            val response = RetrofitClient.apiService.refreshToken(request)
            
            if (response.isSuccessful && response.body() != null) {
                val loginResponse = response.body()!!
                
                // Salva o novo token e informações do usuário
                prefsHelper.saveToken(loginResponse.token)
                prefsHelper.saveUserInfo(
                    email = loginResponse.email,
                    name = loginResponse.name,
                    type = loginResponse.type,
                    userId = loginResponse.userId,
                    driverId = loginResponse.driverId,
                    adminId = loginResponse.adminId
                )
                
                Log.d("TokenRepository", "Token renovado com sucesso")
                return@withContext true
            } else {
                val errorBody = response.errorBody()?.string() ?: "Erro desconhecido"
                Log.e("TokenRepository", "Erro ao renovar token: ${response.code()} - $errorBody")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e("TokenRepository", "Exceção ao renovar token: ${e.message}", e)
            return@withContext false
        }
    }
}

