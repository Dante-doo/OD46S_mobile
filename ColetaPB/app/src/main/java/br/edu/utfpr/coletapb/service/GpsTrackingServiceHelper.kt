package br.edu.utfpr.coletapb.service

import android.content.Context
import android.location.Location
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import br.edu.utfpr.coletapb.data.model.GpsEventType
import br.edu.utfpr.coletapb.data.remote.RetrofitClient
import br.edu.utfpr.coletapb.data.repository.GpsRepository
import br.edu.utfpr.coletapb.data.local.SharedPreferencesHelper
import br.edu.utfpr.coletapb.utils.DateUtils
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.Date

/**
 * Helper class para envio de eventos GPS individuais
 * Pode ser usado para enviar eventos específicos como START, STOP, etc.
 */
class GpsTrackingServiceHelper(
    private val context: Context,
    private val locationProvider: FusedLocationProviderClient
) {

    private var executionId: Long? = null
    private var token: String? = null
    private var job: kotlinx.coroutines.Job? = null
    private val gpsRepository: GpsRepository by lazy {
        val prefsHelper = SharedPreferencesHelper(context)
        GpsRepository(prefsHelper)
    }

    fun startTracking(executionId: Long, jwtToken: String) {
        this.executionId = executionId
        this.token = jwtToken

        // 1) Envia START manual
        sendSingleEvent(GpsEventType.START, isAutomatic = false, description = "Início da coleta")

        // 2) Inicia loop de NORMAL a cada 15s
        job?.cancel()
        job = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                sendSingleEvent(GpsEventType.NORMAL, isAutomatic = true)
                delay(15_000L)
            }
        }
    }

    fun stopTracking() {
        job?.cancel()
        job = null
    }

    /**
     * Envia um evento GPS único ao backend
     * - Obtém a última localização via FusedLocationProviderClient
     * - Monta a chamada Retrofit para /executions/{id}/gps
     * - Envia latitude, longitude, event_type, is_automatic, description
     */
    private fun sendSingleEvent(
        type: GpsEventType,
        isAutomatic: Boolean,
        description: String? = null
    ) {
        val execId = executionId ?: run {
            Log.w("GpsTrackingServiceHelper", "executionId é null, não é possível enviar evento")
            return
        }
        
        // Verifica permissões
        if (ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("GpsTrackingServiceHelper", "Permissões de localização não concedidas")
            return
        }

        // Obtém a última localização conhecida
        try {
            val locationTask = locationProvider.lastLocation
            val location = Tasks.await(locationTask)
            
            if (location == null) {
                Log.w("GpsTrackingServiceHelper", "Não foi possível obter localização")
                return
            }

            // Calcula velocidade, heading e accuracy se disponíveis
            val speedKmh = if (location.hasSpeed()) {
                location.speed * 3.6 // m/s para km/h
            } else {
                null
            }
            
            val headingDegrees = if (location.hasBearing()) {
                location.bearing.toDouble()
            } else {
                null
            }
            
            val accuracyMeters = if (location.hasAccuracy()) {
                location.accuracy.toDouble()
            } else {
                null
            }

            Log.d("GpsTrackingServiceHelper", "Enviando evento ${type.apiValue}: lat=${location.latitude}, lng=${location.longitude}")

            // Envia via GpsRepository (que já faz a conversão para UTC)
            CoroutineScope(Dispatchers.IO).launch {
                val result = gpsRepository.registerGpsPosition(
                    executionId = execId,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    speedKmh = speedKmh,
                    headingDegrees = headingDegrees,
                    accuracyMeters = accuracyMeters,
                    eventType = type.apiValue,
                    isAutomatic = isAutomatic,
                    isOffline = false,
                    description = description
                )

                result.fold(
                    onSuccess = { record ->
                        Log.d("GpsTrackingServiceHelper", "Evento ${type.apiValue} enviado com sucesso: id=${record.id}")
                    },
                    onFailure = { error ->
                        Log.e("GpsTrackingServiceHelper", "Erro ao enviar evento ${type.apiValue}: ${error.message}", error)
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("GpsTrackingServiceHelper", "Exceção ao enviar evento ${type.apiValue}: ${e.message}", e)
        }
    }
}

