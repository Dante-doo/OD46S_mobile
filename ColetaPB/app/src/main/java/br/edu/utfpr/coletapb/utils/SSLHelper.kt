package br.edu.utfpr.coletapb.utils

import android.util.Log
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.*

/**
 * Helper para configurar SSL globalmente para o OSMDroid
 * Configura um TrustManager que aceita todos os certificados
 * ATENÇÃO: Isso reduz a segurança, mas é necessário para o OSMDroid funcionar
 */
object SSLHelper {
    
    fun configureSSLForOSMDroid() {
        try {
            // Cria um TrustManager que aceita TODOS os certificados
            // Isso é necessário porque o OSMDroid não respeita o network_security_config.xml
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            
            // Configura o SSLContext com o TrustManager que aceita todos os certificados
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, SecureRandom())
            
            // Configura o HttpsURLConnection para usar o SSLContext customizado
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
            
            // Configura o HostnameVerifier para aceitar qualquer hostname
            HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
            
            Log.d("SSLHelper", "SSL configurado globalmente para OSMDroid (aceita todos os certificados)")
        } catch (e: Exception) {
            Log.e("SSLHelper", "Erro ao configurar SSL: ${e.message}", e)
        }
    }
}

