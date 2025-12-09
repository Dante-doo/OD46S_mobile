package br.edu.utfpr.coletapb

import android.content.Intent
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import br.edu.utfpr.coletapb.data.local.SharedPreferencesHelper
import br.edu.utfpr.coletapb.data.repository.AssignmentRepository
import br.edu.utfpr.coletapb.data.repository.ExecutionRepository
import br.edu.utfpr.coletapb.data.repository.GpsRepository
import br.edu.utfpr.coletapb.data.model.GpsEventType
import br.edu.utfpr.coletapb.utils.GpsMonitor
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import android.content.pm.PackageManager
import android.Manifest
import androidx.appcompat.app.AlertDialog
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import java.util.concurrent.TimeUnit

/**
 * Tela que exibe os detalhes de um assignment e permite iniciar/continuar a rota
 */
class AssignmentDetailsActivity : AppCompatActivity() {
    
    private lateinit var prefsHelper: SharedPreferencesHelper
    private lateinit var assignmentRepository: AssignmentRepository
    private lateinit var executionRepository: ExecutionRepository
    private lateinit var gpsRepository: GpsRepository
    private lateinit var gpsMonitor: GpsMonitor
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    
    private var assignmentId: Long = 0L
    private var routeId: Long = 0L
    private var routeName: String = ""
    private var driverId: Long = 0L
    private var driverName: String = ""
    private var vehicleId: Long = 0L
    private var vehiclePlate: String = ""
    
    private lateinit var tvRouteName: TextView
    private lateinit var tvDriver: TextView
    private lateinit var tvVehicle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnStartRoute: Button
    private lateinit var btnContinueRoute: Button
    private lateinit var btnViewHistory: Button
    private lateinit var tvExecutorInfo: TextView
    
    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_assignment_details)
        
        // Remove ActionBar padrão
        supportActionBar?.hide()
        
        prefsHelper = SharedPreferencesHelper(this)
        assignmentRepository = AssignmentRepository(prefsHelper)
        executionRepository = ExecutionRepository(prefsHelper)
        gpsRepository = GpsRepository(prefsHelper)
        gpsMonitor = GpsMonitor(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        // Recebe dados do intent
        assignmentId = intent.getLongExtra("assignment_id", 0L)
        routeId = intent.getLongExtra("route_id", 0L)
        routeName = intent.getStringExtra("route_name") ?: "Rota"
        driverId = intent.getLongExtra("driver_id", 0L)
        driverName = intent.getStringExtra("driver_name") ?: "N/A"
        vehicleId = intent.getLongExtra("vehicle_id", 0L)
        vehiclePlate = intent.getStringExtra("vehicle_plate") ?: "N/A"
        
        if (assignmentId == 0L || routeId == 0L) {
            Toast.makeText(this, "Dados inválidos", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        // Configura MaterialToolbar
        val toolbar = findViewById<MaterialToolbar>(R.id.topAppBar)
        toolbar.title = "Rota"
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        
        // Inicializa views
        tvRouteName = findViewById(R.id.tvRouteName)
        tvDriver = findViewById(R.id.tvDriver)
        tvVehicle = findViewById(R.id.tvVehicle)
        tvStatus = findViewById(R.id.tvStatus)
        btnStartRoute = findViewById(R.id.btnStartRoute)
        btnContinueRoute = findViewById(R.id.btnContinueRoute)
        btnViewHistory = findViewById(R.id.btnViewHistory)
        tvExecutorInfo = findViewById(R.id.tvExecutorInfo)
        
        // Preenche dados
        tvRouteName.text = routeName
        tvDriver.text = "Motorista: $driverName"
        tvVehicle.text = "Caminhão: $vehiclePlate"
        
        // Verifica se é ADMIN executando rota de outro motorista
        val userType = prefsHelper.getUserType()
        val currentUserId = prefsHelper.getUserId()
        if (userType == "ADMIN" && currentUserId != driverId) {
            tvExecutorInfo.visibility = TextView.VISIBLE
            tvExecutorInfo.text = "⚠️ Você executará esta rota com seu usuário (ADMIN)"
        } else {
            tvExecutorInfo.visibility = TextView.GONE
        }
        
        // Verifica estado da execução
        checkExecutionStatus()
        
        // Botões
        btnStartRoute.setOnClickListener {
            startRoute()
        }
        
        btnContinueRoute.setOnClickListener {
            continueRoute()
        }
        
        btnViewHistory.setOnClickListener {
            viewHistory()
        }
        
        // Verifica GPS
        window.decorView.post {
            gpsMonitor.checkAndRequestGps(
                onGpsEnabled = {},
                onGpsDisabled = { finish() }
            )
        }
    }
    
    /**
     * Verifica o status da execução para este assignment
     */
    private fun checkExecutionStatus() {
        lifecycleScope.launch {
            try {
                // Primeiro verifica se há execução em andamento para este assignment (independente de quem iniciou)
                val inProgressResult = executionRepository.getExecutionsByAssignment(assignmentId, "IN_PROGRESS")
                
                inProgressResult.fold(
                    onSuccess = { inProgressExecutions ->
                        // Verifica se há execução em andamento hoje
                        val today = java.time.LocalDate.now()
                        val hasInProgressToday = inProgressExecutions.any { exec ->
                            exec.startTime?.let { startTimeStr ->
                                try {
                                    val datePart = startTimeStr.substringBefore("T")
                                    java.time.LocalDate.parse(datePart) == today
                                } catch (e: Exception) {
                                    false
                                }
                            } ?: false
                        }
                        
                        if (hasInProgressToday) {
                            // Há execução em andamento para este assignment (pode ser do admin ou do motorista)
                            // Verifica se alguma das execuções em andamento é do motorista logado
                            val currentUserId = prefsHelper.getUserId()
                            val userType = prefsHelper.getUserType()
                            
                            // Filtra execuções deste assignment que estão em andamento hoje
                            val todayExecutions = inProgressExecutions.filter { exec ->
                                exec.assignmentId == assignmentId && 
                                exec.status == "IN_PROGRESS" &&
                                exec.startTime?.let { startTimeStr ->
                                    try {
                                        val datePart = startTimeStr.substringBefore("T")
                                        java.time.LocalDate.parse(datePart) == today
                                    } catch (e: Exception) {
                                        false
                                    }
                                } ?: false
                            }
                            
                            // Verifica se alguma execução é do motorista logado
                            val isMyExecution = todayExecutions.any { exec ->
                                exec.executorId != null && exec.executorId == currentUserId
                            }
                            
                            Log.d("AssignmentDetails", "Execuções em andamento hoje: ${todayExecutions.size}, executorIds: ${todayExecutions.map { it.executorId }}, currentUserId: $currentUserId, isMyExecution: $isMyExecution")
                            
                            if (isMyExecution) {
                                // É a execução do próprio motorista
                                btnStartRoute.visibility = Button.GONE
                                btnContinueRoute.visibility = Button.VISIBLE
                                tvStatus.text = "Status: Em andamento"
                            } else {
                                // É uma execução iniciada por outro usuário (ex: admin)
                                btnStartRoute.visibility = Button.GONE
                                btnContinueRoute.visibility = Button.GONE
                                tvStatus.text = "Status: Em execução por outro usuário"
                            }
                            return@launch
                        }
                        
                        // Se não há execução em andamento para este assignment, verifica execução atual do motorista
                        // Mas primeiro, verifica novamente se há execução em andamento (pode ter sido iniciada entre as verificações)
                        val currentUserId = prefsHelper.getUserId()
                        
                        // Verifica se alguma das execuções em andamento é do motorista logado
                        val myInProgressExecution = inProgressExecutions.firstOrNull { exec ->
                            exec.assignmentId == assignmentId && 
                            exec.executorId == currentUserId &&
                            exec.status == "IN_PROGRESS"
                        }
                        
                        if (myInProgressExecution != null) {
                            // É a execução do próprio motorista
                            btnStartRoute.visibility = Button.GONE
                            btnContinueRoute.visibility = Button.VISIBLE
                            tvStatus.text = "Status: Em andamento"
                            return@launch
                        }
                        
                        // Se não há execução do motorista, verifica execução atual do motorista (pode ser de outro assignment)
                        val currentResult = executionRepository.getMyCurrentExecution()
                        
                        currentResult.fold(
                            onSuccess = { currentExecution ->
                                // Verifica se a execução é realmente do motorista logado (executorId deve corresponder)
                                if (currentExecution != null && 
                                    currentExecution.assignmentId == assignmentId &&
                                    currentExecution.executorId == currentUserId) {
                                    when (currentExecution.status) {
                                        "IN_PROGRESS" -> {
                                            // Execução em andamento do próprio motorista
                                            btnStartRoute.visibility = Button.GONE
                                            btnContinueRoute.visibility = Button.VISIBLE
                                            tvStatus.text = "Status: Em andamento"
                                            return@launch
                                        }
                                        "COMPLETED" -> {
                                            // Execução concluída
                                            btnStartRoute.visibility = Button.GONE
                                            btnContinueRoute.visibility = Button.GONE
                                            tvStatus.text = "Status: Concluída"
                                            return@launch
                                        }
                                    }
                                }
                                
                                // Se não há execução atual, verifica se há execução concluída para este assignment hoje
                                val completedResult = executionRepository.getExecutionsByAssignment(assignmentId, "COMPLETED")
                                completedResult.fold(
                                    onSuccess = { completedExecutions ->
                                        // Verifica se há execução concluída hoje
                                        val today = java.time.LocalDate.now()
                                        val hasCompletedToday = completedExecutions.any { exec ->
                                            exec.startTime?.let { startTimeStr ->
                                                try {
                                                    // Formato ISO 8601: "2025-12-06T19:00:00" ou "2025-12-06T19:00:00.000"
                                                    val datePart = startTimeStr.substringBefore("T")
                                                    java.time.LocalDate.parse(datePart) == today
                                                } catch (e: Exception) {
                                                    false
                                                }
                                            } ?: false
                                        }
                                        
                                        if (hasCompletedToday) {
                                            // Há execução concluída hoje
                                            btnStartRoute.visibility = Button.GONE
                                            btnContinueRoute.visibility = Button.GONE
                                            tvStatus.text = "Status: Concluída hoje"
                                        } else {
                                            // Não há execução concluída hoje
                                            showNotStartedState()
                                        }
                                    },
                                    onFailure = { error ->
                                        Log.e("AssignmentDetails", "Erro ao verificar execuções concluídas: ${error.message}")
                                        showNotStartedState()
                                    }
                                )
                            },
                            onFailure = { error ->
                                Log.e("AssignmentDetails", "Erro ao verificar execução atual: ${error.message}")
                                // Em caso de erro, verifica execuções concluídas
                                val completedResult = executionRepository.getExecutionsByAssignment(assignmentId, "COMPLETED")
                                completedResult.fold(
                                    onSuccess = { completedExecutions ->
                                        val today = java.time.LocalDate.now()
                                        val hasCompletedToday = completedExecutions.any { exec ->
                                            exec.startTime?.let { startTimeStr ->
                                                try {
                                                    val datePart = startTimeStr.substringBefore("T")
                                                    java.time.LocalDate.parse(datePart) == today
                                                } catch (e: Exception) {
                                                    false
                                                }
                                            } ?: false
                                        }
                                        if (hasCompletedToday) {
                                            btnStartRoute.visibility = Button.GONE
                                            btnContinueRoute.visibility = Button.GONE
                                            tvStatus.text = "Status: Concluída hoje"
                                        } else {
                                            showNotStartedState()
                                        }
                                    },
                                    onFailure = {
                                        showNotStartedState()
                                    }
                                )
                            }
                        )
                    },
                    onFailure = { error ->
                        Log.e("AssignmentDetails", "Erro ao verificar execuções em andamento: ${error.message}")
                        // Em caso de erro, tenta verificar execução atual do motorista
                        val currentResult = executionRepository.getMyCurrentExecution()
                        currentResult.fold(
                            onSuccess = { currentExecution ->
                                if (currentExecution != null && currentExecution.assignmentId == assignmentId) {
                                    when (currentExecution.status) {
                                        "IN_PROGRESS" -> {
                                            btnStartRoute.visibility = Button.GONE
                                            btnContinueRoute.visibility = Button.VISIBLE
                                            tvStatus.text = "Status: Em andamento"
                                        }
                                        "COMPLETED" -> {
                                            btnStartRoute.visibility = Button.GONE
                                            btnContinueRoute.visibility = Button.GONE
                                            tvStatus.text = "Status: Concluída"
                                        }
                                        else -> {
                                            showNotStartedState()
                                        }
                                    }
                                } else {
                                    showNotStartedState()
                                }
                            },
                            onFailure = {
                                showNotStartedState()
                            }
                        )
                    }
                )
            } catch (e: Exception) {
                Log.e("AssignmentDetails", "Exceção ao verificar execução: ${e.message}", e)
                showNotStartedState()
            }
        }
    }
    
    private fun showNotStartedState() {
        btnStartRoute.visibility = Button.VISIBLE
        btnContinueRoute.visibility = Button.GONE
        tvStatus.text = "Status: Não iniciada"
    }
    
    /**
     * Verifica se as permissões de localização foram concedidas
     */
    private fun checkLocationPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Solicita permissões de localização
     */
    private fun requestLocationPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            PERMISSION_REQUEST_CODE
        )
    }
    
    /**
     * Obtém localização atual de forma robusta, tentando múltiplas estratégias
     */
    private suspend fun getCurrentLocationWithRetry(timeoutSeconds: Long = 15): Location? = withContext(Dispatchers.IO) {
        if (!checkLocationPermissions()) {
            Log.w("AssignmentDetails", "Sem permissão de localização")
            // Tenta solicitar permissões na thread principal
            withContext(Dispatchers.Main) {
                if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    // Usuário negou antes, mostra explicação
                    AlertDialog.Builder(this@AssignmentDetailsActivity)
                        .setTitle("Permissão Necessária")
                        .setMessage("Este aplicativo precisa de permissão de localização para registrar coletas e rastrear a rota.")
                        .setPositiveButton("Conceder") { _, _ ->
                            requestLocationPermissions()
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                } else {
                    // Primeira vez, solicita diretamente
                    requestLocationPermissions()
                }
            }
            return@withContext null
        }
        
        try {
            // Estratégia 1: Tenta obter localização em cache (mais rápido)
            val cachedLocation = try {
                fusedLocationClient.lastLocation.result
            } catch (e: Exception) {
                null
            }
            
            if (cachedLocation != null && cachedLocation.accuracy < 100f) {
                // Localização em cache é válida se accuracy < 100m
                Log.d("AssignmentDetails", "Usando localização em cache: lat=${cachedLocation.latitude}, lng=${cachedLocation.longitude}, accuracy=${cachedLocation.accuracy}m")
                return@withContext cachedLocation
            }
            
            // Estratégia 2: Solicita localização atual com timeout
            Log.d("AssignmentDetails", "Solicitando localização atual (timeout: ${timeoutSeconds}s)...")
            val locationTask = fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                null
            )
            
            try {
                val location = withTimeout(timeoutSeconds * 1000) {
                    Tasks.await(locationTask)
                }
                
                if (location != null) {
                    Log.d("AssignmentDetails", "Localização obtida: lat=${location.latitude}, lng=${location.longitude}, accuracy=${location.accuracy}m")
                    return@withContext location
                }
            } catch (e: TimeoutCancellationException) {
                Log.w("AssignmentDetails", "Timeout ao obter localização atual")
            } catch (e: Exception) {
                Log.w("AssignmentDetails", "Erro ao obter localização atual: ${e.message}")
            }
            
            // Estratégia 3: Solicita atualizações temporárias de localização
            Log.d("AssignmentDetails", "Tentando obter localização via atualizações temporárias...")
            var finalLocation: Location? = null
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMaxUpdateDelayMillis(2000L)
                .setWaitForAccurateLocation(false)
                .build()
            
            val locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { location ->
                        if (location.accuracy < 100f || finalLocation == null) {
                            finalLocation = location
                            Log.d("AssignmentDetails", "Localização obtida via callback: lat=${location.latitude}, lng=${location.longitude}, accuracy=${location.accuracy}m")
                        }
                    }
                }
            }
            
            try {
                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    android.os.Looper.getMainLooper()
                )
                
                // Aguarda até obter uma localização ou timeout
                val startTime = System.currentTimeMillis()
                while (finalLocation == null && (System.currentTimeMillis() - startTime) < timeoutSeconds * 1000) {
                    delay(500)
                }
                
                fusedLocationClient.removeLocationUpdates(locationCallback)
                
                if (finalLocation != null) {
                    Log.d("AssignmentDetails", "Localização obtida via atualizações: lat=${finalLocation!!.latitude}, lng=${finalLocation!!.longitude}")
                    return@withContext finalLocation
                }
            } catch (e: Exception) {
                Log.e("AssignmentDetails", "Erro ao solicitar atualizações de localização: ${e.message}", e)
                fusedLocationClient.removeLocationUpdates(locationCallback)
            }
            
            // Se ainda não tem localização, usa a do cache mesmo que seja menos precisa
            if (cachedLocation != null) {
                Log.w("AssignmentDetails", "Usando localização em cache (menos precisa): lat=${cachedLocation.latitude}, lng=${cachedLocation.longitude}, accuracy=${cachedLocation.accuracy}m")
                return@withContext cachedLocation
            }
            
            Log.w("AssignmentDetails", "Não foi possível obter localização após todas as tentativas")
            null
        } catch (e: Exception) {
            Log.e("AssignmentDetails", "Exceção ao obter localização: ${e.message}", e)
            null
        }
    }
    
    /**
     * Inicia uma nova execução
     */
    private fun startRoute() {
        // Verifica permissões antes de iniciar
        if (!checkLocationPermissions()) {
            Log.d("AssignmentDetails", "Permissões de localização não concedidas. Solicitando...")
            if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                // Usuário negou antes, mostra explicação
                AlertDialog.Builder(this)
                    .setTitle("Permissão Necessária")
                    .setMessage("Este aplicativo precisa de permissão de localização para iniciar a rota e registrar coletas.")
                    .setPositiveButton("Conceder") { _, _ ->
                        requestLocationPermissions()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            } else {
                // Primeira vez, solicita diretamente
                requestLocationPermissions()
            }
            return
        }
        
        // Verifica se GPS está habilitado
        if (!gpsMonitor.isGpsEnabled()) {
            gpsMonitor.checkAndRequestGps(
                onGpsEnabled = {
                    // GPS foi habilitado, tenta iniciar a rota novamente
                    startRoute()
                },
                onGpsDisabled = {
                    // Usuário não habilitou GPS ou cancelou
                    Toast.makeText(this, "GPS precisa estar habilitado para iniciar a rota.", Toast.LENGTH_LONG).show()
                }
            )
            return
        }
        
        lifecycleScope.launch {
            try {
                // Tenta obter localização atual de forma robusta
                var startLat: Double? = null
                var startLng: Double? = null
                var startAccuracy: Float? = null
                
                val initialLocation = getCurrentLocationWithRetry(timeoutSeconds = 10)
                initialLocation?.let {
                    startLat = it.latitude
                    startLng = it.longitude
                    startAccuracy = it.accuracy
                    Log.d("AssignmentDetails", "Localização inicial obtida: lat=$startLat, lng=$startLng, accuracy=${startAccuracy}m")
                }
                
                btnStartRoute.isEnabled = false
                btnStartRoute.text = "Iniciando..."
                
                val result = executionRepository.startExecution(
                    assignmentId = assignmentId,
                    startLat = startLat,
                    startLng = startLng
                )
                
                result.fold(
                    onSuccess = { execution ->
                        Log.d("AssignmentDetails", "Execução iniciada: ${execution.id}")
                        
                        // OBRIGATÓRIO: Enviar evento START para /api/v1/executions/:executionId/gps
                        // Se falhar, a rota deve ser cancelada
                        val executionId = execution.id
                        
                        // Obtém localização atual para o evento START
                        // Tenta obter uma localização atualizada, mas usa a inicial como fallback
                        var locationForStart: Location? = null
                        
                        // Tenta obter uma localização mais recente (com timeout menor para não demorar muito)
                        val updatedLocation = getCurrentLocationWithRetry(timeoutSeconds = 5)
                        
                        if (updatedLocation != null) {
                            locationForStart = updatedLocation
                            Log.d("AssignmentDetails", "Usando localização atualizada para evento START")
                        } else if (startLat != null && startLng != null) {
                            // Usa a localização inicial como fallback
                            locationForStart = Location("manual").apply {
                                latitude = startLat!!
                                longitude = startLng!!
                                accuracy = startAccuracy ?: 50f
                                time = System.currentTimeMillis()
                            }
                            Log.d("AssignmentDetails", "Usando localização inicial como fallback para evento START")
                        }
                        
                        if (locationForStart == null) {
                            Log.e("AssignmentDetails", "❌ Não foi possível obter localização para evento START")
                            // Cancela a execução
                            lifecycleScope.launch {
                                executionRepository.cancelExecution(executionId, "Erro: não foi possível obter localização para evento START")
                            }
                            
                            androidx.appcompat.app.AlertDialog.Builder(this@AssignmentDetailsActivity)
                                .setTitle("Erro ao Iniciar Rota")
                                .setMessage("Não foi possível obter sua localização para registrar o evento de início da rota.\n\n" +
                                        "Por favor, verifique se o GPS está habilitado e tente novamente.")
                                .setPositiveButton("OK") { _, _ ->
                                    btnStartRoute.isEnabled = true
                                    btnStartRoute.text = "Iniciar Rota"
                                }
                                .setCancelable(true)
                                .show()
                            return@fold
                        }
                        
                        // Prepara dados do evento START
                        val speedKmh = if (locationForStart.hasSpeed()) {
                            locationForStart.speed * 3.6
                        } else null
                        
                        val headingDegrees = if (locationForStart.hasBearing()) {
                            locationForStart.bearing.toDouble()
                        } else null
                        
                        val accuracyMeters = if (locationForStart.hasAccuracy()) {
                            locationForStart.accuracy.toDouble()
                        } else null
                        
                        Log.d("AssignmentDetails", "=== ENVIANDO EVENTO START ===")
                        Log.d("AssignmentDetails", "executionId: $executionId")
                        Log.d("AssignmentDetails", "latitude: ${locationForStart.latitude}, longitude: ${locationForStart.longitude}")
                        Log.d("AssignmentDetails", "speedKmh: $speedKmh, headingDegrees: $headingDegrees, accuracyMeters: $accuracyMeters")
                        Log.d("AssignmentDetails", "eventType: ${GpsEventType.START.apiValue}")
                        
                        btnStartRoute.text = "Registrando início..."
                        
                        // Envia evento START
                        val startEventResult = withContext(Dispatchers.IO) {
                            try {
                                gpsRepository.registerGpsPosition(
                                    executionId = executionId,
                                    latitude = locationForStart.latitude,
                                    longitude = locationForStart.longitude,
                                    speedKmh = speedKmh,
                                    headingDegrees = headingDegrees,
                                    accuracyMeters = accuracyMeters,
                                    eventType = GpsEventType.START.apiValue,
                                    isAutomatic = false,
                                    isOffline = false,
                                    description = "Início da coleta"
                                )
                            } catch (e: Exception) {
                                Log.e("AssignmentDetails", "Exceção ao enviar evento START: ${e.message}", e)
                                Result.failure(e)
                            }
                        }
                        
                        startEventResult.fold(
                            onSuccess = { record ->
                                Log.d("AssignmentDetails", "✅ Evento START enviado com sucesso: id=${record.id}, eventType=${record.eventType}")
                                
                                // Mostra modal de sucesso antes de navegar
                                androidx.appcompat.app.AlertDialog.Builder(this@AssignmentDetailsActivity)
                                    .setTitle("Sucesso")
                                    .setMessage("Rota iniciada com sucesso!")
                                    .setPositiveButton("OK") { _, _ ->
                                        // Navega para a tela de execução
                                        val intent = Intent(this@AssignmentDetailsActivity, StartRoute::class.java).apply {
                                            putExtra("execution_id", executionId)
                                            putExtra("route_id", execution.routeId)
                                            putExtra("route_name", execution.routeName ?: routeName)
                                        }
                                        startActivity(intent)
                                        finish()
                                    }
                                    .setCancelable(false)
                                    .show()
                            },
                            onFailure = { error ->
                                Log.e("AssignmentDetails", "❌ Erro ao enviar evento START: ${error.message}", error)
                                
                                // OBRIGATÓRIO: Se o START não foi registrado, a rota DEVE ser cancelada
                                var cancelSuccess = false
                                withContext(Dispatchers.IO) {
                                    try {
                                        Log.d("AssignmentDetails", "Cancelando execução $executionId devido a falha no registro do evento START...")
                                        val cancelResult = executionRepository.cancelExecution(
                                            executionId,
                                            "Erro ao registrar evento START: ${error.message}"
                                        )
                                        cancelResult.fold(
                                            onSuccess = {
                                                Log.d("AssignmentDetails", "✅ Execução cancelada no backend devido a falha no START")
                                                cancelSuccess = true
                                            },
                                            onFailure = { cancelError ->
                                                Log.e("AssignmentDetails", "❌ Erro ao cancelar execução: ${cancelError.message}", cancelError)
                                                cancelSuccess = false
                                            }
                                        )
                                    } catch (cancelError: Exception) {
                                        Log.e("AssignmentDetails", "❌ Exceção ao cancelar execução: ${cancelError.message}", cancelError)
                                        cancelSuccess = false
                                    }
                                }
                                
                                // Notifica o usuário sobre o problema
                                val cancelMessage = if (cancelSuccess) {
                                    "A rota foi cancelada automaticamente."
                                } else {
                                    "Atenção: Não foi possível cancelar a rota automaticamente. Entre em contato com o suporte."
                                }
                                
                                androidx.appcompat.app.AlertDialog.Builder(this@AssignmentDetailsActivity)
                                    .setTitle("Erro ao Iniciar Rota")
                                    .setMessage("Não foi possível registrar o evento de início da rota no servidor.\n\n" +
                                            "Possíveis causas:\n" +
                                            "• Problema com a localização GPS\n" +
                                            "• Falha na conexão com o servidor\n" +
                                            "• Erro: ${error.message}\n\n" +
                                            "$cancelMessage\n\n" +
                                            "Por favor, verifique sua conexão e localização GPS, e tente novamente.")
                                    .setPositiveButton("OK") { _, _ ->
                                        btnStartRoute.isEnabled = true
                                        btnStartRoute.text = "Iniciar Rota"
                                    }
                                    .setCancelable(true)
                                    .show()
                            }
                        )
                    },
                    onFailure = { error ->
                        Log.e("AssignmentDetails", "Erro ao iniciar execução: ${error.message}", error)
                        
                        val errorMessage = error.message ?: "Erro desconhecido ao iniciar a rota"
                        
                        // Verifica se é erro de periodicity (dia não permitido)
                        val isPeriodicityError = errorMessage.contains("só pode ser iniciada", ignoreCase = true) ||
                                                errorMessage.contains("não é um dia permitido", ignoreCase = true) ||
                                                errorMessage.contains("dia permitido", ignoreCase = true)
                        
                        // Se o erro for 409 (conflito - rota já executada hoje)
                        val isConflictError = errorMessage.contains("já foi executada hoje", ignoreCase = true) ||
                                             errorMessage.contains("409") == true || 
                                             errorMessage.contains("already exists", ignoreCase = true) ||
                                             errorMessage.contains("EXECUTION_CONFLICT", ignoreCase = true)
                        
                        if (isPeriodicityError) {
                            // Mostra mensagem de erro de periodicity com AlertDialog
                            withContext(Dispatchers.Main) {
                                androidx.appcompat.app.AlertDialog.Builder(this@AssignmentDetailsActivity)
                                    .setTitle("Rota não disponível hoje")
                                    .setMessage(errorMessage)
                                    .setPositiveButton("OK", null)
                                    .setCancelable(true)
                                    .show()
                                
                                btnStartRoute.isEnabled = true
                                btnStartRoute.text = "Iniciar Rota"
                            }
                            return@fold
                        } else if (isConflictError) {
                            // Verifica se há execução concluída para este assignment
                            lifecycleScope.launch {
                                val completedResult = executionRepository.getExecutionsByAssignment(assignmentId, "COMPLETED")
                                completedResult.fold(
                                    onSuccess = { completedExecutions ->
                                        val today = java.time.LocalDate.now().toString()
                                        val hasCompletedToday = completedExecutions.any { exec ->
                                            exec.startTime?.startsWith(today) == true
                                        }
                                        
                                        if (hasCompletedToday) {
                                            // Há execução concluída hoje - atualiza UI
                                            val latestExecution = completedExecutions
                                                .filter { exec ->
                                                    exec.startTime?.let { startTimeStr ->
                                                        try {
                                                            val datePart = startTimeStr.substringBefore("T")
                                                            java.time.LocalDate.parse(datePart) == java.time.LocalDate.now()
                                                        } catch (e: Exception) {
                                                            false
                                                        }
                                                    } ?: false
                                                }
                                                .maxByOrNull { it.startTime ?: "" }
                                            if (latestExecution != null) {
                                                btnStartRoute.visibility = Button.GONE
                                                btnContinueRoute.visibility = Button.GONE
                                                tvStatus.text = "Status: Concluída hoje"
                                                Toast.makeText(
                                                    this@AssignmentDetailsActivity,
                                                    "Esta rota já foi executada hoje. Você pode ver o histórico.",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            } else {
                                                // Mostra mensagem amigável
                                                androidx.appcompat.app.AlertDialog.Builder(this@AssignmentDetailsActivity)
                                                    .setTitle("Rota já executada")
                                                    .setMessage("Esta rota já foi executada hoje. Não é possível iniciar uma nova execução no mesmo dia.\n\nVocê pode visualizar o histórico de execuções.")
                                                    .setPositiveButton("OK", null)
                                                    .show()
                                            }
                                        } else {
                                            // Mostra mensagem amigável mesmo se não encontrar execução concluída
                                            androidx.appcompat.app.AlertDialog.Builder(this@AssignmentDetailsActivity)
                                                .setTitle("Rota já executada")
                                                .setMessage("Esta rota já foi executada hoje. Não é possível iniciar uma nova execução no mesmo dia.")
                                                .setPositiveButton("OK", null)
                                                .show()
                                        }
                                    },
                                    onFailure = { fetchError ->
                                        // Se falhar ao buscar execuções, mostra mensagem amigável
                                        androidx.appcompat.app.AlertDialog.Builder(this@AssignmentDetailsActivity)
                                            .setTitle("Rota já executada")
                                            .setMessage("Esta rota já foi executada hoje. Não é possível iniciar uma nova execução no mesmo dia.")
                                            .setPositiveButton("OK", null)
                                            .show()
                                    }
                                )
                            }
                        } else {
                            // Outro tipo de erro - mostra com AlertDialog para melhor visibilidade
                            withContext(Dispatchers.Main) {
                                androidx.appcompat.app.AlertDialog.Builder(this@AssignmentDetailsActivity)
                                    .setTitle("Erro ao iniciar rota")
                                    .setMessage(errorMessage)
                                    .setPositiveButton("OK", null)
                                    .setCancelable(true)
                                    .show()
                                btnStartRoute.isEnabled = true
                                btnStartRoute.text = "Iniciar Rota"
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e("AssignmentDetails", "Exceção ao iniciar rota: ${e.message}", e)
                Toast.makeText(
                    this@AssignmentDetailsActivity,
                    "Erro: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                btnStartRoute.isEnabled = true
                btnStartRoute.text = "Iniciar Rota"
            }
        }
    }
    
    /**
     * Continua uma execução em andamento
     */
    private fun continueRoute() {
        lifecycleScope.launch {
            try {
                val result = executionRepository.getMyCurrentExecution()
                
                result.fold(
                    onSuccess = { execution ->
                        if (execution != null && execution.status == "IN_PROGRESS") {
                            val intent = Intent(this@AssignmentDetailsActivity, StartRoute::class.java).apply {
                                putExtra("execution_id", execution.id)
                                putExtra("route_id", execution.routeId)
                                putExtra("route_name", execution.routeName ?: routeName)
                            }
                            startActivity(intent)
                            finish()
                        } else {
                            Toast.makeText(
                                this@AssignmentDetailsActivity,
                                "Execução não encontrada ou já finalizada",
                                Toast.LENGTH_SHORT
                            ).show()
                            checkExecutionStatus() // Atualiza UI
                        }
                    },
                    onFailure = { error ->
                        Toast.makeText(
                            this@AssignmentDetailsActivity,
                            "Erro ao buscar execução: ${error.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            } catch (e: Exception) {
                Log.e("AssignmentDetails", "Exceção ao continuar rota: ${e.message}", e)
                Toast.makeText(
                    this@AssignmentDetailsActivity,
                    "Erro: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    /**
     * Navega para a tela de histórico de execuções desta rota
     */
    private fun viewHistory() {
        val intent = Intent(this, AssignmentRouteHistoryActivity::class.java).apply {
            putExtra("assignment_id", assignmentId)
            putExtra("route_id", routeId)
            putExtra("route_name", routeName)
        }
        startActivity(intent)
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permissão concedida
                Log.d("AssignmentDetails", "Permissão de localização concedida")
                Toast.makeText(this, "Permissão de localização concedida!", Toast.LENGTH_SHORT).show()
                
                // Se o usuário estava tentando iniciar a rota, tenta novamente
                // (o botão ainda deve estar desabilitado se estava tentando iniciar)
                if (!btnStartRoute.isEnabled && btnStartRoute.text == "Iniciando...") {
                    // Usuário estava tentando iniciar, tenta novamente
                    startRoute()
                }
            } else {
                // Permissão negada
                Log.w("AssignmentDetails", "Permissão de localização negada")
                Toast.makeText(
                    this,
                    "Permissão de localização é necessária para iniciar a rota.",
                    Toast.LENGTH_LONG
                ).show()
                
                // Reabilita o botão se estava desabilitado
                btnStartRoute.isEnabled = true
                btnStartRoute.text = "Iniciar Rota"
            }
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

