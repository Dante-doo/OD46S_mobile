package br.edu.utfpr.coletapb.service

import android.app.Service
import android.content.Intent
import android.location.Location
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import br.edu.utfpr.coletapb.data.AppDatabase
import br.edu.utfpr.coletapb.data.dao.ExecutionDao
import br.edu.utfpr.coletapb.data.dao.GpsDao
import br.edu.utfpr.coletapb.data.model.GpsRecordLocal
import com.google.android.gms.location.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GpsTrackingService : LifecycleService() {
    
    private var isTracking = false
    private var executionLocalId: Long = 0L
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback
    
    private lateinit var executionDao: ExecutionDao
    private lateinit var gpsDao: GpsDao
    
    companion object {
        private const val TAG = "GpsTrackingService"
        const val ACTION_START_TRACKING = "br.edu.utfpr.coletapb.START_TRACKING"
        const val ACTION_STOP_TRACKING = "br.edu.utfpr.coletapb.STOP_TRACKING"
        const val EXTRA_EXECUTION_ID = "execution_local_id"
        
        // Intervalos de atualização
        private const val UPDATE_INTERVAL_MS = 10000L // 10 segundos
        private const val FASTEST_UPDATE_INTERVAL_MS = 5000L // 5 segundos
    }
    
    override fun onCreate() {
        super.onCreate()
        
        val db = AppDatabase.getDatabase(this)
        executionDao = db.executionDao()
        gpsDao = db.gpsDao()
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(FASTEST_UPDATE_INTERVAL_MS)
            .build()
        
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                super.onLocationResult(locationResult)
                locationResult.lastLocation?.let { location ->
                    onLocationUpdate(location)
                }
            }
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        when (intent?.action) {
            ACTION_START_TRACKING -> {
                val execId = intent.getLongExtra(EXTRA_EXECUTION_ID, 0L)
                if (execId > 0) {
                    startTracking(execId)
                }
            }
            ACTION_STOP_TRACKING -> {
                stopTracking()
            }
        }
        
        return START_STICKY
    }
    
    private fun startTracking(execId: Long) {
        if (isTracking) {
            Log.w(TAG, "Tracking já está ativo")
            return
        }
        
        executionLocalId = execId
        isTracking = true
        
        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                mainLooper
            )
            Log.d(TAG, "Rastreamento GPS iniciado para execução $executionLocalId")
        } catch (e: SecurityException) {
            Log.e(TAG, "Erro de permissão ao iniciar rastreamento: ${e.message}")
            stopSelf()
        }
    }
    
    private fun stopTracking() {
        if (!isTracking) {
            return
        }
        
        fusedLocationClient.removeLocationUpdates(locationCallback)
        isTracking = false
        Log.d(TAG, "Rastreamento GPS parado")
        stopSelf()
    }
    
    private fun onLocationUpdate(location: Location) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val gpsRecord = GpsRecordLocal(
                    executionLocalId = executionLocalId,
                    timestamp = System.currentTimeMillis(),
                    lat = location.latitude,
                    lng = location.longitude,
                    eventType = "NORMAL",
                    isOffline = true
                )
                
                gpsDao.insert(gpsRecord)
                Log.d(TAG, "GPS registrado: ${location.latitude}, ${location.longitude}")
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao salvar GPS: ${e.message}", e)
            }
        }
    }
    
    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (isTracking) {
            stopTracking()
        }
    }
}

