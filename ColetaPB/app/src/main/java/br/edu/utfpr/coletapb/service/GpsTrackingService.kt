package br.edu.utfpr.coletapb.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import br.edu.utfpr.coletapb.R
import br.edu.utfpr.coletapb.StartRoute
import br.edu.utfpr.coletapb.data.AppDatabase
import br.edu.utfpr.coletapb.data.dao.ExecutionDao
import br.edu.utfpr.coletapb.data.dao.GpsDao
import br.edu.utfpr.coletapb.data.local.SharedPreferencesHelper
import br.edu.utfpr.coletapb.data.model.GpsEventType
import br.edu.utfpr.coletapb.data.model.GpsRecordLocal
import br.edu.utfpr.coletapb.data.repository.GpsRepository
import br.edu.utfpr.coletapb.data.repository.SyncRepository
import com.google.android.gms.location.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GpsTrackingService : LifecycleService() {
    
    private var isTracking = false
    private var executionLocalId: Long = 0L
    private var backendExecutionId: Long? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback
    
    private lateinit var executionDao: ExecutionDao
    private lateinit var gpsDao: GpsDao
    private lateinit var gpsRepository: GpsRepository
    private lateinit var syncRepository: SyncRepository
    private lateinit var prefsHelper: SharedPreferencesHelper
    
    // Throttling: última localização enviada
    private var lastSentLocation: Location? = null
    private var lastSentTime: Long = 0L
    
    companion object {
        private const val TAG = "GpsTrackingService"
        const val ACTION_START_TRACKING = "br.edu.utfpr.coletapb.START_TRACKING"
        const val ACTION_STOP_TRACKING = "br.edu.utfpr.coletapb.STOP_TRACKING"
        const val EXTRA_EXECUTION_ID = "execution_local_id"
        const val EXTRA_BACKEND_EXECUTION_ID = "backend_execution_id"
        
        // ID da notificação (deve ser único e constante)
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "gps_tracking_channel"
        
        // Intervalos de atualização GPS
        private const val UPDATE_INTERVAL_MS = 10000L // 10 segundos
        private const val FASTEST_UPDATE_INTERVAL_MS = 5000L // 5 segundos
        
        // Throttling: condições para enviar ao backend
        private const val MIN_DISTANCE_METERS = 50.0 // Mínimo 50 metros de movimento
        private const val MIN_TIME_BETWEEN_SENDS_MS = 30000L // Mínimo 30 segundos entre envios
        private const val MAX_TIME_BETWEEN_SENDS_MS = 120000L // Máximo 2 minutos (força envio mesmo parado)
    }
    
    override fun onCreate() {
        super.onCreate()
        
        createNotificationChannel()
        
        val db = AppDatabase.getDatabase(this)
        executionDao = db.executionDao()
        gpsDao = db.gpsDao()
        prefsHelper = SharedPreferencesHelper(this)
        gpsRepository = GpsRepository(prefsHelper)
        syncRepository = SyncRepository(this, prefsHelper)
        
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
        
        // IMPORTANTE: Chamar startForeground() ANTES de qualquer operação demorada
        // Isso deve ser feito dentro de 5 segundos após startForegroundService()
        startForeground(NOTIFICATION_ID, createNotification())
        
        when (intent?.action) {
            ACTION_START_TRACKING -> {
                val execId = intent.getLongExtra(EXTRA_EXECUTION_ID, 0L)
                val backendExecId = intent.getLongExtra(EXTRA_BACKEND_EXECUTION_ID, 0L).takeIf { it > 0 }
                if (execId > 0) {
                    startTracking(execId, backendExecId)
                } else {
                    // Se não tem execId válido, para o serviço
                    stopSelf()
                }
            }
            ACTION_STOP_TRACKING -> {
                stopTracking()
            }
        }
        
        return START_STICKY
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Rastreamento GPS",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificação para rastreamento GPS em segundo plano"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val intent = Intent(this, StartRoute::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Rastreamento GPS Ativo")
            .setContentText("Coletando localização em segundo plano")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
    
    private fun startTracking(execId: Long, backendExecId: Long?) {
        if (isTracking) {
            Log.w(TAG, "Tracking já está ativo")
            return
        }
        
        // Verifica permissões antes de iniciar
        if (!checkLocationPermissions()) {
            Log.e(TAG, "Permissões de localização não concedidas. Parando serviço.")
            stopSelf()
            return
        }
        
        executionLocalId = execId
        backendExecutionId = backendExecId
        lastSentLocation = null
        lastSentTime = 0L
        
        // Se não tem backendExecutionId, tenta buscar do banco local
        if (backendExecutionId == null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val execution = executionDao.getById(execId)
                this@GpsTrackingService.backendExecutionId = execution?.backendId
                Log.d(TAG, "Backend execution ID obtido do banco: ${this@GpsTrackingService.backendExecutionId}")
            }
        }
        
        isTracking = true
        
        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                mainLooper
            )
            Log.d(TAG, "Rastreamento GPS iniciado para execução local=$executionLocalId, backend=$backendExecutionId")
        } catch (e: SecurityException) {
            Log.e(TAG, "Erro de permissão ao iniciar rastreamento: ${e.message}")
            stopSelf()
        }
    }
    
    /**
     * Verifica se as permissões de localização foram concedidas
     */
    private fun checkLocationPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    private fun stopTracking() {
        if (!isTracking) {
            return
        }
        
        fusedLocationClient.removeLocationUpdates(locationCallback)
        isTracking = false
        lastSentLocation = null
        lastSentTime = 0L
        Log.d(TAG, "Rastreamento GPS parado")
        
        // Remove a notificação antes de parar o serviço
        stopForeground(true)
        stopSelf()
    }
    
    private fun onLocationUpdate(location: Location) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                val timestamp = System.currentTimeMillis()
                
                // Sempre salva localmente para sincronização offline
                val gpsRecord = GpsRecordLocal(
                    executionLocalId = executionLocalId,
                    timestamp = timestamp,
                    lat = location.latitude,
                    lng = location.longitude,
                    eventType = "NORMAL",
                    isOffline = true
                )
                
                gpsDao.insert(gpsRecord)
                Log.d(TAG, "GPS salvo localmente: ${location.latitude}, ${location.longitude}")
                
                // Verifica se deve enviar ao backend (throttling inteligente)
                if (shouldSendToBackend(location, now)) {
                    sendLocationToBackend(location)
                    lastSentLocation = location
                    lastSentTime = now
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao processar GPS: ${e.message}", e)
            }
        }
    }
    
    /**
     * Verifica se deve enviar a localização ao backend baseado em:
     * - Distância mínima desde o último envio (50m)
     * - Tempo mínimo desde o último envio (30s)
     * - Tempo máximo desde o último envio (2min - força envio mesmo parado)
     */
    private fun shouldSendToBackend(location: Location, currentTime: Long): Boolean {
        // Se não tem backendExecutionId, não envia
        if (backendExecutionId == null) {
            return false
        }
        
        // Se não está online, não envia (será sincronizado depois)
        if (!syncRepository.isOnline()) {
            return false
        }
        
        // Primeira localização sempre envia
        if (lastSentLocation == null) {
            Log.d(TAG, "Primeira localização, enviando ao backend")
            return true
        }
        
        // Calcula distância desde o último envio
        val distance = lastSentLocation!!.distanceTo(location)
        val timeSinceLastSend = currentTime - lastSentTime
        
        // Envia se:
        // 1. Moveu mais de MIN_DISTANCE_METERS E passou mais de MIN_TIME_BETWEEN_SENDS_MS
        // 2. OU passou mais de MAX_TIME_BETWEEN_SENDS_MS (força envio mesmo parado)
        val shouldSend = (distance >= MIN_DISTANCE_METERS && timeSinceLastSend >= MIN_TIME_BETWEEN_SENDS_MS) ||
                        (timeSinceLastSend >= MAX_TIME_BETWEEN_SENDS_MS)
        
        if (shouldSend) {
            Log.d(TAG, "Condições atendidas para envio: distância=${distance}m, tempo=${timeSinceLastSend}ms")
        }
        
        return shouldSend
    }
    
    /**
     * Envia localização ao backend
     */
    private suspend fun sendLocationToBackend(location: Location) {
        if (backendExecutionId == null) {
            Log.w(TAG, "Não é possível enviar: backendExecutionId é null")
            return
        }
        
        try {
            // Calcula velocidade em km/h (se disponível)
            val speedKmh = if (location.hasSpeed()) {
                location.speed * 3.6 // m/s para km/h
            } else {
                null
            }
            
            // Calcula heading (direção) em graus (se disponível)
            val headingDegrees = if (location.hasBearing()) {
                location.bearing.toDouble()
            } else {
                null
            }
            
            // Accuracy em metros (se disponível)
            val accuracyMeters = if (location.hasAccuracy()) {
                location.accuracy.toDouble()
            } else {
                null
            }
            
            Log.d(TAG, "Enviando GPS ao backend: execId=$backendExecutionId, lat=${location.latitude}, lng=${location.longitude}, speed=${speedKmh}km/h")
            
            val result = gpsRepository.registerGpsPosition(
                executionId = backendExecutionId!!,
                latitude = location.latitude,
                longitude = location.longitude,
                speedKmh = speedKmh,
                headingDegrees = headingDegrees,
                accuracyMeters = accuracyMeters,
                eventType = "NORMAL",
                isAutomatic = true,
                isOffline = false
            )
            
            result.fold(
                onSuccess = { record ->
                    Log.d(TAG, "GPS enviado com sucesso ao backend: id=${record.id}")
                    
                    // Tenta atualizar o registro local mais recente para marcar como sincronizado
                    try {
                        val localRecord = gpsDao.getLatestByExecution(executionLocalId)
                        if (localRecord != null && localRecord.isOffline) {
                            val updatedRecord = localRecord.copy(isOffline = false)
                            gpsDao.update(updatedRecord)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Não foi possível atualizar registro local: ${e.message}")
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "Erro ao enviar GPS ao backend: ${error.message}", error)
                    // Não faz nada, o registro já está salvo localmente e será sincronizado depois
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exceção ao enviar GPS ao backend: ${e.message}", e)
        }
    }
    
    /**
     * Envia um evento GPS único ao backend
     * Usado para enviar eventos específicos como START, STOP, BREAK, etc.
     * 
     * @param type Tipo do evento GPS
     * @param isAutomatic Se o evento foi gerado automaticamente ou manualmente
     * @param description Descrição opcional do evento
     */
    private fun sendSingleEvent(
        type: GpsEventType,
        isAutomatic: Boolean,
        description: String? = null
    ) {
        val execId = backendExecutionId ?: run {
            Log.w(TAG, "backendExecutionId é null, não é possível enviar evento")
            return
        }
        
        // Verifica permissões
        if (!checkLocationPermissions()) {
            Log.w(TAG, "Permissões de localização não concedidas")
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Obtém a última localização conhecida
                val location = try {
                    com.google.android.gms.tasks.Tasks.await(fusedLocationClient.lastLocation)
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao obter localização: ${e.message}", e)
                    null
                }
                
                if (location == null) {
                    Log.w(TAG, "Não foi possível obter localização para evento ${type.apiValue}")
                    return@launch
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

                Log.d(TAG, "Enviando evento ${type.apiValue}: lat=${location.latitude}, lng=${location.longitude}")

                // Envia via GpsRepository (que já faz a conversão para UTC)
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
                        Log.d(TAG, "Evento ${type.apiValue} enviado com sucesso: id=${record.id}")
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Erro ao enviar evento ${type.apiValue}: ${error.message}", error)
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Exceção ao enviar evento ${type.apiValue}: ${e.message}", e)
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

