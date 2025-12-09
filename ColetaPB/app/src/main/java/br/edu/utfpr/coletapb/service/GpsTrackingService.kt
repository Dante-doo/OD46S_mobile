package br.edu.utfpr.coletapb.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import br.edu.utfpr.coletapb.R
import br.edu.utfpr.coletapb.StartRoute
import br.edu.utfpr.coletapb.LoginPage
import br.edu.utfpr.coletapb.data.AppDatabase
import br.edu.utfpr.coletapb.data.dao.ExecutionDao
import br.edu.utfpr.coletapb.data.dao.GpsDao
import br.edu.utfpr.coletapb.data.local.SharedPreferencesHelper
import br.edu.utfpr.coletapb.data.model.GpsEventType
import br.edu.utfpr.coletapb.data.model.GpsRecordLocal
import br.edu.utfpr.coletapb.data.repository.GpsRepository
import br.edu.utfpr.coletapb.data.repository.SyncRepository
import br.edu.utfpr.coletapb.utils.NetworkMonitor
import com.google.android.gms.location.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GpsTrackingService : LifecycleService() {
    
    private var isTracking = false
    private var executionLocalId: Long = 0L
    private var backendExecutionId: Long? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback
    
    // Flag para evitar múltiplas tentativas de redirecionamento
    @Volatile
    private var isRedirectingToLogin = false
    
    private lateinit var executionDao: ExecutionDao
    private lateinit var gpsDao: GpsDao
    private lateinit var gpsRepository: GpsRepository
    private lateinit var syncRepository: SyncRepository
    private lateinit var prefsHelper: SharedPreferencesHelper
    private lateinit var networkMonitor: NetworkMonitor
    
    // Throttling: última localização enviada
    private var lastSentLocation: Location? = null
    private var lastSentTime: Long = 0L
    
    // Sincronização automática
    private var syncJob: Job? = null
    private var networkMonitorJob: Job? = null
    private var periodicSyncJob: Job? = null
    
    // Detecção de movimento: flags para distinguir movimento real de ruído GPS
    private var isMoving: Boolean = false
    private var lastMovingLocation: Location? = null // Última localização considerada como movimento real
    
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
        private const val MIN_DISTANCE_METERS = 10.0 // Mínimo 10 metros de movimento
        private const val MIN_TIME_BETWEEN_SENDS_MS = 15000L // Mínimo 15 segundos entre envios (mesmo em movimento)
        private const val MAX_TIME_BETWEEN_SENDS_MS = 60000L // Máximo 1 minuto (força envio mesmo parado)
        private const val STOPPED_SPEED_THRESHOLD_MS = 1.0 // Velocidade em m/s abaixo da qual considera parado (~3.6 km/h)
        private const val MIN_MOVEMENT_DISTANCE_METERS = 10.0 // Distância mínima para considerar movimento real (filtra ruído GPS)
        private const val MIN_MOVEMENT_SPEED_MS = 1.0 // Velocidade mínima em m/s para considerar movimento real
        
        // Sincronização automática
        private const val PERIODIC_SYNC_INTERVAL_MS = 60000L // Sincroniza a cada 1 minuto quando online
        private const val SYNC_RETRY_DELAY_MS = 5000L // Aguarda 5 segundos após internet voltar antes de sincronizar
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
        networkMonitor = NetworkMonitor(this)
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        // Configura LocationRequest para funcionar em background
        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(FASTEST_UPDATE_INTERVAL_MS)
            .setMaxUpdateDelayMillis(UPDATE_INTERVAL_MS * 2) // Permite delay maior em background
            .setWaitForAccurateLocation(false) // Não espera por localização precisa (mais rápido)
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
            // IMPORTANCE_LOW pode fazer o sistema parar o serviço em algumas versões
            // Usando IMPORTANCE_DEFAULT para garantir que o serviço continue rodando
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Rastreamento GPS",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notificação para rastreamento GPS em segundo plano"
                setShowBadge(false)
                // Não vibra nem faz som para não incomodar
                enableVibration(false)
                setSound(null, null)
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
            .setPriority(NotificationCompat.PRIORITY_DEFAULT) // Mudado de LOW para DEFAULT
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSilent(true) // Não faz som
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
        isMoving = false
        lastMovingLocation = null
        
        Log.d(TAG, "=== INICIANDO TRACKING ===")
        Log.d(TAG, "executionLocalId: $executionLocalId")
        Log.d(TAG, "backendExecutionId recebido: $backendExecutionId")
        Log.d(TAG, "Token disponível: ${prefsHelper.getToken() != null}")
        Log.d(TAG, "Online: ${try { syncRepository.isOnline() } catch (e: Exception) { false }}")
        
        // Se não tem backendExecutionId, tenta buscar do banco local
        if (backendExecutionId == null) {
            Log.w(TAG, "backendExecutionId é null, tentando buscar do banco local...")
            lifecycleScope.launch(Dispatchers.IO) {
                val execution = executionDao.getById(execId)
                this@GpsTrackingService.backendExecutionId = execution?.backendId
                Log.d(TAG, "Backend execution ID obtido do banco: ${this@GpsTrackingService.backendExecutionId}")
                
                // Se ainda não tem backendId, tenta buscar novamente após um delay
                // (pode ter sido criado no backend enquanto o serviço iniciava)
                if (this@GpsTrackingService.backendExecutionId == null) {
                    Log.w(TAG, "Ainda não tem backendId, aguardando 2 segundos e tentando novamente...")
                    kotlinx.coroutines.delay(2000) // Aguarda 2 segundos
                    val updatedExecution = executionDao.getById(execId)
                    this@GpsTrackingService.backendExecutionId = updatedExecution?.backendId
                    Log.d(TAG, "Backend execution ID verificado novamente: ${this@GpsTrackingService.backendExecutionId}")
                }
            }
        } else {
            Log.d(TAG, "Backend execution ID já disponível: $backendExecutionId")
        }
        
        isTracking = true
        
        // Inicia monitoramento de rede e sincronização automática
        startNetworkMonitoring()
        startPeriodicSync()
        
        try {
            // Usa Looper.getMainLooper() explicitamente para garantir funcionamento em background
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                android.os.Looper.getMainLooper()
            )
            Log.d(TAG, "✅ Rastreamento GPS iniciado com sucesso!")
            Log.d(TAG, "   - executionLocalId: $executionLocalId")
            Log.d(TAG, "   - backendExecutionId: $backendExecutionId")
            Log.d(TAG, "   - LocationRequest: intervalo=${UPDATE_INTERVAL_MS}ms, min=${FASTEST_UPDATE_INTERVAL_MS}ms")
            Log.d(TAG, "   - Token: ${if (prefsHelper.getToken() != null) "disponível" else "NÃO disponível"}")
            Log.d(TAG, "   - Online: ${try { syncRepository.isOnline() } catch (e: Exception) { "erro ao verificar" }}")
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Erro de permissão ao iniciar rastreamento: ${e.message}")
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erro inesperado ao iniciar rastreamento: ${e.message}", e)
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
        
        // Para monitoramento de rede e sincronização
        stopNetworkMonitoring()
        stopPeriodicSync()
        
        Log.d(TAG, "Rastreamento GPS parado")
        
        // Remove a notificação antes de parar o serviço
        stopForeground(true)
        stopSelf()
    }
    
    /**
     * Inicia o monitoramento de rede para detectar quando a internet volta
     * e sincronizar automaticamente os pontos pendentes
     */
    private fun startNetworkMonitoring() {
        if (networkMonitorJob?.isActive == true) {
            return
        }
        
        networkMonitorJob = lifecycleScope.launch(Dispatchers.IO) {
            var wasOnline = networkMonitor.isOnline()
            
            networkMonitor.connectivityFlow()
                .onEach { isOnline ->
                    if (isOnline && !wasOnline) {
                        // Internet voltou! Aguarda um pouco e sincroniza
                        Log.d(TAG, "🌐 Internet voltou! Aguardando ${SYNC_RETRY_DELAY_MS}ms antes de sincronizar...")
                        delay(SYNC_RETRY_DELAY_MS)
                        
                        // Verifica novamente se ainda está online (usa verificação robusta)
                        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                        val network = connectivityManager.activeNetwork
                        val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }
                        
                        val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                        val hasValidated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                        val hasTransport = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                                          capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true ||
                                          capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
                        
                        val isOnline = hasInternet && (hasValidated || hasTransport)
                        
                        if (isOnline && isTracking) {
                            Log.d(TAG, "🔄 Iniciando sincronização automática após internet voltar...")
                            syncPendingData()
                        } else {
                            Log.d(TAG, "⏸️ Internet ainda não está disponível ou tracking parado (hasInternet=$hasInternet, hasValidated=$hasValidated, hasTransport=$hasTransport, isTracking=$isTracking)")
                        }
                    }
                    wasOnline = isOnline
                }
                .catch { e ->
                    Log.e(TAG, "Erro no monitoramento de rede: ${e.message}", e)
                }
                .collect { } // Coleta o flow para manter o monitoramento ativo
        }
    }
    
    /**
     * Para o monitoramento de rede
     */
    private fun stopNetworkMonitoring() {
        networkMonitorJob?.cancel()
        networkMonitorJob = null
    }
    
    /**
     * Inicia sincronização periódica dos pontos pendentes
     * Sincroniza a cada PERIODIC_SYNC_INTERVAL_MS quando estiver online
     */
    private fun startPeriodicSync() {
        if (periodicSyncJob?.isActive == true) {
            return
        }
        
        periodicSyncJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isTracking) {
                try {
                    delay(PERIODIC_SYNC_INTERVAL_MS)
                    
                    if (!isTracking) {
                        break
                    }
                    
                    // Verifica se está online antes de tentar sincronizar (usa verificação robusta)
                    val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    val network = connectivityManager.activeNetwork
                    val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }
                    
                    val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                    val hasValidated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                    val hasTransport = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                                      capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true ||
                                      capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
                    
                    val isOnline = hasInternet && (hasValidated || hasTransport)
                    
                    if (isOnline) {
                        Log.d(TAG, "🔄 Sincronização periódica iniciada...")
                        syncPendingData()
                    } else {
                        Log.d(TAG, "⏸️ Sincronização periódica pulada (offline: hasInternet=$hasInternet, hasValidated=$hasValidated, hasTransport=$hasTransport)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro na sincronização periódica: ${e.message}", e)
                }
            }
        }
    }
    
    /**
     * Para a sincronização periódica
     */
    private fun stopPeriodicSync() {
        periodicSyncJob?.cancel()
        periodicSyncJob = null
    }
    
    /**
     * Sincroniza os pontos GPS pendentes mantendo ordem cronológica
     */
    private suspend fun syncPendingData() {
        try {
            // Verifica conectividade de forma mais robusta (mesma lógica usada em shouldMarkRecordAsOffline)
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }
            
            val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val hasValidated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            val hasTransport = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                              capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true ||
                              capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            
            val isOnline = hasInternet && (hasValidated || hasTransport)
            
            if (!isOnline) {
                Log.d(TAG, "Não sincronizando: offline (hasInternet=$hasInternet, hasValidated=$hasValidated, hasTransport=$hasTransport)")
                return
            }
            
            if (prefsHelper.getToken() == null) {
                Log.w(TAG, "Não sincronizando: token não disponível")
                return
            }
            
            // Verifica quantos registros pendentes existem antes de sincronizar
            val pendingCount = try {
                gpsDao.getPendingSyncCount()
            } catch (e: Exception) {
                Log.w(TAG, "Erro ao contar registros pendentes, usando método alternativo: ${e.message}")
                gpsDao.getPendingSync().size
            }
            
            if (pendingCount > 0) {
                Log.d(TAG, "🔄 Iniciando sincronização de $pendingCount registros GPS pendentes...")
            } else {
                Log.d(TAG, "ℹ️ Nenhum registro GPS pendente para sincronizar")
                return
            }
            
            val result = syncRepository.syncPendingData()
            
            if (result.syncedGpsRecords > 0) {
                Log.d(TAG, "✅ Sincronização concluída: ${result.syncedGpsRecords} registros GPS sincronizados")
            } else {
                Log.d(TAG, "ℹ️ Nenhum registro GPS foi sincronizado (pode ter havido erros)")
            }
            
            if (result.errorMessage != null) {
                Log.w(TAG, "⚠️ Erros durante sincronização: ${result.errorMessage}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao sincronizar dados pendentes: ${e.message}", e)
        }
    }
    
    /**
     * Para o rastreamento e redireciona para a tela de login quando o token não está disponível
     */
    private fun stopTrackingAndRedirectToLogin() {
        Log.w(TAG, "Token não disponível - parando rastreamento e redirecionando para login")
        
        // Para o rastreamento
        if (isTracking) {
            stopTracking()
        }
        
        // Cria Intent para abrir a tela de login
        val loginIntent = Intent(this, LoginPage::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("reason", "token_expired")
        }
        
        // Inicia a Activity de login
        startActivity(loginIntent)
        
        // Cria uma notificação para informar o usuário
        createTokenExpiredNotification()
    }
    
    /**
     * Cria uma notificação informando que o token expirou e é necessário fazer login novamente
     */
    private fun createTokenExpiredNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        
        val loginIntent = Intent(this, LoginPage::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            loginIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Sessão expirada")
            .setContentText("É necessário fazer login novamente para continuar o rastreamento")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }
    
    private fun onLocationUpdate(location: Location) {
        Log.d(TAG, "📍 Nova localização recebida: lat=${location.latitude}, lng=${location.longitude}, accuracy=${location.accuracy}m")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Verifica se a execução ainda está em andamento antes de processar localização
                val execution = executionDao.getById(executionLocalId)
                if (execution == null) {
                    Log.w(TAG, "⚠️ Execução não encontrada no banco local. Parando tracking.")
                    stopTracking()
                    return@launch
                }
                
                // Verifica se a execução foi finalizada ou cancelada
                if (execution.status == "COMPLETED" || execution.status == "CANCELLED") {
                    Log.w(TAG, "⚠️ Execução já foi finalizada (status: ${execution.status}). Parando tracking.")
                    stopTracking()
                    return@launch
                }
                
                val now = System.currentTimeMillis()
                val timestamp = System.currentTimeMillis()
                
                // Se não tem backendExecutionId ainda, tenta buscar do banco (pode ter sido criado depois)
                if (backendExecutionId == null) {
                    Log.w(TAG, "⚠️ backendExecutionId ainda é null, tentando buscar do banco...")
                    if (execution.backendId != null) {
                        backendExecutionId = execution.backendId
                        Log.d(TAG, "✅ Backend execution ID atualizado durante tracking: $backendExecutionId")
                    } else {
                        Log.w(TAG, "⚠️ Ainda não tem backendExecutionId no banco. Execução local: $executionLocalId")
                    }
                }
                
                // ===== DETECÇÃO DE MOVIMENTO REAL (filtra ruído GPS) =====
                // Calcula distância desde última posição de movimento ANTES de detectar movimento
                // Isso garante que shouldSendToBackend() use a distância correta
                val distanceFromLastMovement = if (lastMovingLocation != null) {
                    lastMovingLocation!!.distanceTo(location)
                } else {
                    // Se lastMovingLocation é null, usa lastSentLocation como referência
                    // Se ambos são null, é a primeira localização (distância = 0, mas será enviada)
                    if (lastSentLocation != null) {
                        lastSentLocation!!.distanceTo(location)
                    } else {
                        // Primeira localização - será enviada independente da distância
                        0.0f
                    }
                }
                
                val isMovingNow = detectRealMovement(location)
                
                Log.d(TAG, "Distância desde último movimento: ${String.format("%.1f", distanceFromLastMovement)}m, isMoving=$isMovingNow, lastMovingLocation=${if (lastMovingLocation != null) "(${lastMovingLocation!!.latitude}, ${lastMovingLocation!!.longitude})" else "null"}")
                
                // Verifica se está offline (sem conectividade OU sem backendExecutionId)
                val isOffline = shouldMarkRecordAsOffline()
                
                // Sempre salva localmente para sincronização offline
                val gpsRecord = GpsRecordLocal(
                    executionLocalId = executionLocalId,
                    timestamp = timestamp,
                    lat = location.latitude,
                    lng = location.longitude,
                    eventType = "NORMAL",
                    isOffline = isOffline
                )
                
                gpsDao.insert(gpsRecord)
                Log.d(TAG, "GPS salvo localmente: ${location.latitude}, ${location.longitude}, isOffline=$isOffline, backendId=$backendExecutionId, isMoving=$isMovingNow, distDesdeUltimoMovimento=${String.format("%.1f", distanceFromLastMovement)}m")
                
                // Verifica novamente o status da execução antes de enviar (pode ter mudado enquanto processava)
                val currentExecution = executionDao.getById(executionLocalId)
                if (currentExecution?.status == "COMPLETED" || currentExecution?.status == "CANCELLED") {
                    Log.w(TAG, "⚠️ Execução foi finalizada durante processamento (status: ${currentExecution.status}). Parando tracking.")
                    stopTracking()
                    return@launch
                }
                
                // Verifica se deve enviar ao backend (throttling inteligente)
                // Passa a distância já calculada para evitar recalcular
                if (shouldSendToBackend(location, now, isMovingNow, distanceFromLastMovement)) {
                    sendLocationToBackend(location)
                    lastSentLocation = location
                    lastSentTime = now
                    
                    // Atualiza lastMovingLocation apenas se for movimento real E enviou ao backend
                    if (isMovingNow) {
                        lastMovingLocation = location
                        Log.d(TAG, "✅ lastMovingLocation atualizado após envio ao backend")
                    }
                } else {
                    Log.d(TAG, "GPS não enviado ao backend (throttling ou condições não atendidas)")
                }
                
                // Verifica se há pontos pendentes e tenta sincronizar (fora do fluxo normal)
                // Isso garante que pontos offline sejam sincronizados mesmo quando não há novos pontos
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val pendingCount = try {
                            gpsDao.getPendingSyncCount()
                        } catch (e: Exception) {
                            gpsDao.getPendingSync().size
                        }
                        if (pendingCount > 0) {
                            // Verifica se está online antes de tentar sincronizar
                            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                            val network = connectivityManager.activeNetwork
                            val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }
                            
                            val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                            val hasValidated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                            val hasTransport = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                                              capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true ||
                                              capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
                            
                            val isOnline = hasInternet && (hasValidated || hasTransport)
                            
                            if (isOnline && prefsHelper.getToken() != null) {
                                Log.d(TAG, "📤 Detectados $pendingCount pontos pendentes, tentando sincronizar...")
                                syncPendingData()
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Erro ao verificar pontos pendentes: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao processar GPS: ${e.message}", e)
            }
        }
    }
    
    /**
     * Detecta se há movimento real (filtra ruído GPS de 1-2m)
     * Considera movimento apenas se:
     * - Distância desde última posição de movimento >= MIN_MOVEMENT_DISTANCE_METERS (10m)
     * - E velocidade >= MIN_MOVEMENT_SPEED_MS (1 m/s) se disponível
     */
    private fun detectRealMovement(newLocation: Location): Boolean {
        // Primeira localização sempre considera como movimento (para inicializar)
        if (lastMovingLocation == null) {
            lastMovingLocation = newLocation
            isMoving = true
            Log.d(TAG, "✅ Primeira localização, inicializando detecção de movimento")
            return true
        }
        
        // Calcula distância desde a última posição de movimento real
        val distance = lastMovingLocation!!.distanceTo(newLocation)
        
        // Verifica velocidade se disponível
        val hasValidSpeed = newLocation.hasSpeed() && newLocation.speed >= MIN_MOVEMENT_SPEED_MS
        
        // Considera movimento real se:
        // 1. Moveu pelo menos MIN_MOVEMENT_DISTANCE_METERS (10m) desde última posição de movimento
        // 2. E (tem velocidade válida >= 1 m/s OU distância é significativamente maior que o ruído)
        val isMovingNow = if (distance >= MIN_MOVEMENT_DISTANCE_METERS) {
            // Se moveu 10m ou mais, verifica velocidade para confirmar
            if (hasValidSpeed) {
                true // Tem velocidade e distância suficiente
            } else {
                // Sem velocidade, mas moveu bastante (pode ser GPS impreciso mas movimento real)
                // Considera movimento se moveu mais que 2x o mínimo (20m)
                distance >= MIN_MOVEMENT_DISTANCE_METERS * 2
            }
        } else {
            // Moveu menos de 10m - provavelmente ruído GPS
            false
        }
        
        // Atualiza flag de movimento
        val wasMoving = isMoving
        isMoving = isMovingNow
        
        if (isMovingNow) {
            if (!wasMoving) {
                Log.d(TAG, "🚗 Movimento detectado: distância=${String.format("%.1f", distance)}m, velocidade=${if (newLocation.hasSpeed()) String.format("%.1f", newLocation.speed * 3.6) + "km/h" else "N/A"}")
            }
            // NÃO atualiza lastMovingLocation aqui - será atualizado apenas quando enviar ao backend
            // Isso permite que shouldSendToBackend() calcule a distância corretamente
        } else {
            if (wasMoving) {
                Log.d(TAG, "🛑 Parado detectado: distância=${String.format("%.1f", distance)}m (ruído GPS, < ${MIN_MOVEMENT_DISTANCE_METERS}m)")
            }
        }
        
        return isMovingNow
    }
    
    /**
     * Define se um registro deve ser marcado como offline.
     * - Se não tiver conectividade OU não existir backendExecutionId OU não tiver token, considera offline.
     */
    private fun shouldMarkRecordAsOffline(): Boolean {
        return try {
            // Verifica conectividade de forma mais robusta
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }
            
            // Verifica se tem internet (pode não ter VALIDATED imediatamente, mas ainda ter internet)
            val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val hasValidated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            val hasTransport = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                              capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true ||
                              capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            
            // Considera online se tem internet E (validated OU tem transporte ativo)
            // Isso é mais tolerante - pode estar online mesmo sem VALIDATED ainda
            val online = hasInternet && (hasValidated || hasTransport)
            
            val hasToken = prefsHelper.getToken() != null
            val isOffline = !online || backendExecutionId == null || !hasToken
            
            if (isOffline) {
                Log.d(TAG, "Marcando como offline: hasInternet=$hasInternet, hasValidated=$hasValidated, hasTransport=$hasTransport, online=$online, hasToken=$hasToken, backendId=$backendExecutionId")
            } else {
                Log.d(TAG, "✅ Online: hasInternet=$hasInternet, hasValidated=$hasValidated, hasTransport=$hasTransport")
            }
            
            isOffline
        } catch (e: Exception) {
            // Se der erro para checar online, assume offline
            Log.w(TAG, "Erro ao verificar conectividade: ${e.message}", e)
            true
        }
    }
    
    /**
     * Verifica se deve enviar a localização ao backend baseado em:
     * - Distância mínima desde o último envio (10m quando em movimento)
     * - Quando em movimento: envia quando moveu 10m (sem limitação de tempo)
     * - Quando parado: envia a cada 1 minuto para manter atualização
     */
    private fun shouldSendToBackend(location: Location, currentTime: Long, isMovingNow: Boolean, distanceFromLastMovement: Float = 0f): Boolean {
        // Verifica se ainda está rastreando (pode ter sido parado por verificação anterior)
        if (!isTracking) {
            Log.d(TAG, "Não enviando ao backend: tracking foi parado")
            return false
        }
        
        // Se não tem backendExecutionId, não envia
        if (backendExecutionId == null) {
            Log.d(TAG, "Não enviando ao backend: backendExecutionId é null")
            return false
        }
        
        // Se não tem token, não envia e para o serviço para forçar login
        val hasToken = prefsHelper.getToken() != null
        if (!hasToken && !isRedirectingToLogin) {
            Log.w(TAG, "⚠️ Token não disponível - parando serviço e redirecionando para login")
            isRedirectingToLogin = true
            lifecycleScope.launch(Dispatchers.Main) {
                stopTrackingAndRedirectToLogin()
            }
            return false
        }
        
        if (!hasToken) {
            // Já está redirecionando, apenas não envia
            return false
        }
        
        // Se não está online, não envia (será sincronizado depois)
        val isOnline = try {
            // Verifica conectividade de forma mais robusta
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }
            
            val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val hasValidated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            val hasTransport = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                              capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true ||
                              capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            
            // Considera online se tem internet E (validated OU tem transporte ativo)
            hasInternet && (hasValidated || hasTransport)
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao verificar conectividade: ${e.message}", e)
            false
        }
        
        if (!isOnline) {
            Log.d(TAG, "Não enviando ao backend: offline")
            return false
        }
        
        // Primeira localização sempre envia
        if (lastSentLocation == null) {
            Log.d(TAG, "Primeira localização, enviando ao backend: execId=$backendExecutionId")
            return true
        }
        
        val timeSinceLastSend = currentTime - lastSentTime
        
        // Usa a flag isMovingNow (já calculada com filtro de ruído) em vez de recalcular
        // Lógica de envio:
        // 1. Se está em movimento: envia quando moveu 10m desde última posição de movimento real (sem limitação de tempo)
        // 2. Se está parado: envia a cada 1 minuto para manter atualização
        val shouldSend = if (!isMovingNow) {
            // Parado: envia a cada 1 minuto
            timeSinceLastSend >= MAX_TIME_BETWEEN_SENDS_MS
        } else {
            // Em movimento: usa a distância já calculada desde última posição de movimento real
            // Isso garante que envia quando realmente moveu 10m, não apenas quando houve ruído GPS
            val distanceToUse = if (distanceFromLastMovement > 0f) {
                // Usa a distância passada como parâmetro (já calculada antes de detectRealMovement)
                distanceFromLastMovement
            } else {
                // Fallback: calcula agora (não deveria acontecer, mas por segurança)
                if (lastMovingLocation != null) {
                    lastMovingLocation!!.distanceTo(location)
                } else {
                    lastSentLocation!!.distanceTo(location)
                }
            }
            
            // Envia quando moveu 10m desde última posição de movimento real
            val shouldSendByDistance = distanceToUse >= MIN_DISTANCE_METERS
            Log.d(TAG, "Verificação de envio (em movimento): distância=${String.format("%.1f", distanceToUse)}m >= ${MIN_DISTANCE_METERS}m? $shouldSendByDistance")
            shouldSendByDistance
        }
        
        // Calcula distância para logs (sempre desde lastSentLocation para consistência)
        val distance = lastSentLocation!!.distanceTo(location)
        
        if (shouldSend) {
            val status = if (!isMovingNow) "parado" else "em movimento"
            if (!isMovingNow) {
                Log.d(TAG, "Condições atendidas para envio ($status): tempo=${timeSinceLastSend/1000}s")
            } else {
                val distanceFromMovement = if (lastMovingLocation != null) {
                    lastMovingLocation!!.distanceTo(location)
                } else {
                    distance
                }
                Log.d(TAG, "Condições atendidas para envio ($status): distância desde último movimento=${String.format("%.1f", distanceFromMovement)}m")
            }
        } else {
            val status = if (!isMovingNow) "parado" else "em movimento"
            if (!isMovingNow) {
                Log.d(TAG, "Condições NÃO atendidas ($status): tempo=${timeSinceLastSend/1000}s (max=${MAX_TIME_BETWEEN_SENDS_MS/1000}s)")
            } else {
                val distanceFromMovement = if (lastMovingLocation != null) {
                    lastMovingLocation!!.distanceTo(location)
                } else {
                    distance
                }
                Log.d(TAG, "Condições NÃO atendidas ($status): distância desde último movimento=${String.format("%.1f", distanceFromMovement)}m (min=${MIN_DISTANCE_METERS}m)")
            }
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
        Log.w(TAG, "onDestroy() chamado - isTracking=$isTracking")
        
        // Para todos os jobs
        stopNetworkMonitoring()
        stopPeriodicSync()
        syncJob?.cancel()
        
        super.onDestroy()
        if (isTracking) {
            Log.w(TAG, "Serviço sendo destruído mas tracking ainda está ativo! Isso não deveria acontecer.")
            stopTracking()
        }
    }
}

