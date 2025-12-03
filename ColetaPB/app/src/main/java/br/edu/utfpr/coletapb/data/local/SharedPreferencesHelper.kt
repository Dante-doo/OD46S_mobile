package br.edu.utfpr.coletapb.data.local

import android.content.Context
import android.content.SharedPreferences

/**
 * Helper para gerenciar dados locais usando SharedPreferences
 * Armazena token JWT, informações do usuário, etc.
 */
class SharedPreferencesHelper(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )
    
    companion object {
        private const val PREFS_NAME = "coletapb_prefs"
        
        // Chaves
        private const val KEY_JWT_TOKEN = "jwt_token"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USER_TYPE = "user_type"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_DRIVER_ID = "driver_id"
        private const val KEY_ADMIN_ID = "admin_id"
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
    }
    
    // Token JWT
    fun saveToken(token: String) {
        prefs.edit().putString(KEY_JWT_TOKEN, token).commit() // Usa commit() para garantir persistência imediata
    }
    
    fun getToken(): String? = prefs.getString(KEY_JWT_TOKEN, null)
    
    fun clearToken() {
        prefs.edit().remove(KEY_JWT_TOKEN).apply()
    }
    
    // Informações do usuário
    fun saveUserInfo(email: String, name: String, type: String, userId: Long?, driverId: Long? = null, adminId: Long? = null) {
        prefs.edit().apply {
            putString(KEY_USER_EMAIL, email)
            putString(KEY_USER_NAME, name)
            putString(KEY_USER_TYPE, type)
            if (userId != null) putLong(KEY_USER_ID, userId)
            if (driverId != null) putLong(KEY_DRIVER_ID, driverId)
            if (adminId != null) putLong(KEY_ADMIN_ID, adminId)
            putBoolean(KEY_IS_LOGGED_IN, true)
            commit() // Usa commit() para garantir persistência imediata
        }
    }
    
    fun getUserEmail(): String? = prefs.getString(KEY_USER_EMAIL, null)
    fun getUserName(): String? = prefs.getString(KEY_USER_NAME, null)
    fun getUserType(): String? = prefs.getString(KEY_USER_TYPE, null)
    fun getUserId(): Long = prefs.getLong(KEY_USER_ID, 0L)
    fun getDriverId(): Long = prefs.getLong(KEY_DRIVER_ID, 0L)
    fun getAdminId(): Long = prefs.getLong(KEY_ADMIN_ID, 0L)
    
    fun isLoggedIn(): Boolean {
        val hasFlag = prefs.getBoolean(KEY_IS_LOGGED_IN, false)
        val hasToken = getToken() != null
        return hasFlag && hasToken
    }
    
    // Limpar todos os dados (logout)
    fun clearAll() {
        prefs.edit().clear().apply()
    }
}

