package br.edu.utfpr.coletapb.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class GpsMonitor(private val activity: AppCompatActivity) {
    
    private var gpsStateReceiver: BroadcastReceiver? = null
    private var isMonitoring = false
    private var onGpsDisabledCallback: (() -> Unit)? = null
    
    fun isGpsEnabled(): Boolean {
        val locationManager = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        Log.d("GpsMonitor", "GPS está habilitado: $isEnabled")
        return isEnabled
    }
    
    fun checkAndRequestGps(onGpsEnabled: (() -> Unit)? = null, onGpsDisabled: (() -> Unit)? = null) {
        Log.d("GpsMonitor", "Verificando GPS...")
        val isEnabled = isGpsEnabled()
        if (!isEnabled) {
            Log.d("GpsMonitor", "GPS não está habilitado, mostrando diálogo...")
            showGpsRequiredDialog(onGpsEnabled, onGpsDisabled)
        } else {
            Log.d("GpsMonitor", "GPS está habilitado, continuando...")
            onGpsEnabled?.invoke()
        }
    }
    
    private fun showGpsRequiredDialog(onGpsEnabled: (() -> Unit)?, onGpsDisabled: (() -> Unit)?) {
        Log.d("GpsMonitor", "Exibindo diálogo de GPS necessário...")
        try {
            val dialog = AlertDialog.Builder(activity)
                .setTitle("GPS Necessário")
                .setMessage("Este aplicativo requer GPS ativado para funcionar. Por favor, ative o GPS nas configurações.")
                .setPositiveButton("Ativar GPS") { _, _ ->
                    Log.d("GpsMonitor", "Usuário clicou em 'Ativar GPS'")
                    openLocationSettings()
                    // Não chama onGpsEnabled aqui, pois o GPS ainda não está ativo
                    // Será verificado quando o usuário voltar
                }
                .setNegativeButton("Sair") { _, _ ->
                    Log.d("GpsMonitor", "Usuário clicou em 'Sair'")
                    onGpsDisabled?.invoke()
                    activity.finish()
                }
                .setCancelable(false)
                .create()
            
            // Garante que o diálogo seja exibido após a Activity estar totalmente criada
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    dialog.show()
                    Log.d("GpsMonitor", "Diálogo exibido com sucesso")
                } else {
                    Log.w("GpsMonitor", "Activity está finalizando ou destruída, não é possível exibir diálogo")
                }
            }
        } catch (e: Exception) {
            Log.e("GpsMonitor", "Erro ao exibir diálogo de GPS: ${e.message}", e)
        }
    }
    
    fun openLocationSettings() {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        activity.startActivity(intent)
    }
    
    fun startMonitoring(onGpsDisabled: () -> Unit) {
        if (isMonitoring) return
        
        this.onGpsDisabledCallback = onGpsDisabled
        isMonitoring = true
        
        gpsStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
                    // Verifica após um pequeno delay para garantir que o estado foi atualizado
                    activity.runOnUiThread {
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            if (!isGpsEnabled()) {
                                onGpsDisabledCallback?.invoke()
                            }
                        }, 500)
                    }
                }
            }
        }
        
        val filter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(gpsStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            activity.registerReceiver(gpsStateReceiver, filter)
        }
    }
    
    fun stopMonitoring() {
        if (!isMonitoring) return
        
        gpsStateReceiver?.let {
            try {
                activity.unregisterReceiver(it)
            } catch (e: Exception) {
                // Receiver já foi desregistrado
            }
        }
        gpsStateReceiver = null
        isMonitoring = false
        onGpsDisabledCallback = null
    }
    
    fun showGpsDisabledWarning() {
        AlertDialog.Builder(activity)
            .setTitle("GPS Desativado")
            .setMessage("O GPS foi desativado. O aplicativo não pode funcionar sem GPS. Por favor, ative o GPS para continuar usando o aplicativo.")
            .setPositiveButton("Ativar GPS") { _, _ ->
                openLocationSettings()
            }
            .setNegativeButton("Sair do App") { _, _ ->
                activity.finishAffinity() // Fecha todas as activities
            }
            .setCancelable(false)
            .show()
    }
}

