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
import br.edu.utfpr.coletapb.utils.GpsMonitor
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * Tela que exibe os detalhes de um assignment e permite iniciar/continuar a rota
 */
class AssignmentDetailsActivity : AppCompatActivity() {
    
    private lateinit var prefsHelper: SharedPreferencesHelper
    private lateinit var assignmentRepository: AssignmentRepository
    private lateinit var executionRepository: ExecutionRepository
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
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_assignment_details)
        
        // Remove ActionBar padrão
        supportActionBar?.hide()
        
        prefsHelper = SharedPreferencesHelper(this)
        assignmentRepository = AssignmentRepository(prefsHelper)
        executionRepository = ExecutionRepository(prefsHelper)
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
                // Primeiro verifica execução atual (em andamento)
                val currentResult = executionRepository.getMyCurrentExecution()
                
                currentResult.fold(
                    onSuccess = { currentExecution ->
                        if (currentExecution != null && currentExecution.assignmentId == assignmentId) {
                            when (currentExecution.status) {
                                "IN_PROGRESS" -> {
                                    // Execução em andamento
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
     * Inicia uma nova execução
     */
    private fun startRoute() {
        lifecycleScope.launch {
            try {
                // Tenta obter localização atual
                var startLat: Double? = null
                var startLng: Double? = null
                
                try {
                    if (ContextCompat.checkSelfPermission(
                            this@AssignmentDetailsActivity,
                            android.Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(
                            this@AssignmentDetailsActivity,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        val location = fusedLocationClient.lastLocation.result
                        location?.let {
                            startLat = it.latitude
                            startLng = it.longitude
                        }
                    }
                } catch (e: Exception) {
                    Log.w("AssignmentDetails", "Não foi possível obter localização: ${e.message}")
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
                        
                        // Mostra modal de sucesso antes de navegar
                        androidx.appcompat.app.AlertDialog.Builder(this@AssignmentDetailsActivity)
                            .setTitle("Sucesso")
                            .setMessage("Rota iniciada com sucesso!")
                            .setPositiveButton("OK") { _, _ ->
                                // Navega para a tela de execução
                                val intent = Intent(this@AssignmentDetailsActivity, StartRoute::class.java).apply {
                                    putExtra("execution_id", execution.id)
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
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

