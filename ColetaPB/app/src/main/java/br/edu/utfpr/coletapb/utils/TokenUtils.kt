package br.edu.utfpr.coletapb.utils

import android.util.Base64
import android.util.Log
import org.json.JSONObject

/**
 * Utilitários para trabalhar com tokens JWT
 */
object TokenUtils {
    
    private const val TAG = "TokenUtils"
    
    /**
     * Verifica se um token JWT está expirado
     * @param token Token JWT completo
     * @return true se o token está expirado, false caso contrário
     */
    fun isTokenExpired(token: String?): Boolean {
        if (token == null || token.isEmpty()) {
            return true
        }
        
        try {
            // JWT tem 3 partes separadas por ponto: header.payload.signature
            val parts = token.split(".")
            if (parts.size != 3) {
                Log.w(TAG, "Token JWT inválido: não tem 3 partes")
                return true
            }
            
            // Decodifica o payload (segunda parte)
            val payload = parts[1]
            
            // Adiciona padding se necessário (Base64 pode precisar)
            val paddedPayload = when (payload.length % 4) {
                0 -> payload
                2 -> "$payload=="
                3 -> "$payload="
                else -> payload
            }
            
            val decodedBytes = Base64.decode(paddedPayload, Base64.URL_SAFE or Base64.NO_WRAP)
            val payloadJson = String(decodedBytes, Charsets.UTF_8)
            val jsonObject = JSONObject(payloadJson)
            
            // Verifica se tem campo 'exp' (expiration time em Unix timestamp)
            if (!jsonObject.has("exp")) {
                Log.w(TAG, "Token não tem campo 'exp', assumindo que não expira (retorna false = não expirado)")
                return false // Se não tem campo exp, assume que não expira
            }
            
            val expTimestamp = jsonObject.getLong("exp")
            val currentTimestamp = System.currentTimeMillis() / 1000 // Converte para segundos
            
            // Adiciona margem de segurança de 60 segundos (1 minuto) antes de considerar expirado
            // Isso evita problemas de sincronização de relógio
            val marginSeconds = 60L
            val isExpired = currentTimestamp >= (expTimestamp - marginSeconds)
            
            if (isExpired) {
                Log.w(TAG, "Token expirado: exp=$expTimestamp (${java.util.Date(expTimestamp * 1000)}), atual=$currentTimestamp (${java.util.Date(currentTimestamp * 1000)})")
            } else {
                val remainingSeconds = expTimestamp - currentTimestamp
                val remainingMinutes = remainingSeconds / 60
                Log.d(TAG, "Token válido por mais ${remainingMinutes} minutos (exp=$expTimestamp, atual=$currentTimestamp)")
            }
            
            return isExpired
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao verificar expiração do token: ${e.message}", e)
            // Em caso de erro, assume que está expirado por segurança
            return true
        }
    }
    
    /**
     * Obtém o tempo de expiração do token em milissegundos
     * @param token Token JWT completo
     * @return Timestamp de expiração em milissegundos, ou null se não conseguir obter
     */
    fun getTokenExpirationTime(token: String?): Long? {
        if (token == null || token.isEmpty()) {
            return null
        }
        
        try {
            val parts = token.split(".")
            if (parts.size != 3) {
                return null
            }
            
            val payload = parts[1]
            val paddedPayload = when (payload.length % 4) {
                0 -> payload
                2 -> "$payload=="
                3 -> "$payload="
                else -> payload
            }
            
            val decodedBytes = Base64.decode(paddedPayload, Base64.URL_SAFE or Base64.NO_WRAP)
            val payloadJson = String(decodedBytes, Charsets.UTF_8)
            val jsonObject = JSONObject(payloadJson)
            
            if (!jsonObject.has("exp")) {
                return null
            }
            
            val expTimestamp = jsonObject.getLong("exp")
            return expTimestamp * 1000 // Converte para milissegundos
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao obter expiração do token: ${e.message}", e)
            return null
        }
    }
}

