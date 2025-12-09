package br.edu.utfpr.coletapb.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Monitor de conectividade de rede que detecta quando a internet está disponível ou não.
 * Usa NetworkCallback para detectar mudanças em tempo real.
 */
class NetworkMonitor(private val context: Context) {
    
    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    companion object {
        private const val TAG = "NetworkMonitor"
    }
    
    /**
     * Verifica se há conectividade com internet no momento
     * Usa verificação mais tolerante que permite conexão mesmo sem VALIDATED ainda
     */
    fun isOnline(): Boolean {
        try {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            
            // Verifica se tem internet (pode não ter VALIDATED imediatamente, mas ainda ter internet)
            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val hasValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val hasTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                              capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                              capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            
            // Considera online se tem internet E (validated OU tem transporte ativo)
            // Isso é mais tolerante - pode estar online mesmo sem VALIDATED ainda
            return hasInternet && (hasValidated || hasTransport)
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao verificar conectividade: ${e.message}", e)
            return false
        }
    }
    
    /**
     * Flow que emite true quando a internet está disponível e false quando não está.
     * Emite o estado atual imediatamente ao ser coletado.
     */
    fun connectivityFlow(): Flow<Boolean> = callbackFlow {
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                val hasValidated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                val hasTransport = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                                  capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true ||
                                  capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
                
                val isOnline = hasInternet && (hasValidated || hasTransport)
                
                if (isOnline) {
                    Log.d(TAG, "Internet disponível")
                    trySend(true)
                }
            }
            
            override fun onLost(network: Network) {
                Log.d(TAG, "Internet perdida")
                trySend(false)
            }
            
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val hasValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val hasTransport = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                 networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                                 networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                
                val isOnline = hasInternet && (hasValidated || hasTransport)
                
                if (isOnline) {
                    Log.d(TAG, "Capacidades de rede alteradas - Internet disponível")
                    trySend(true)
                } else {
                    Log.d(TAG, "Capacidades de rede alteradas - Internet não disponível")
                    trySend(false)
                }
            }
        }
        
        // Registra o callback
        // Não exige NET_CAPABILITY_VALIDATED no request para permitir detecção mais cedo
        // A verificação tolerante será feita no callback
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        
        // Emite o estado inicial
        trySend(isOnline())
        
        // Aguarda até que o flow seja cancelado
        awaitClose {
            Log.d(TAG, "Desregistrando NetworkCallback")
            connectivityManager.unregisterNetworkCallback(networkCallback)
        }
    }
}

