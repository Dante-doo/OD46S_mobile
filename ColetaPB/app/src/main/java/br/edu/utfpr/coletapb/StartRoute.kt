package br.edu.utfpr.coletapb

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import br.edu.utfpr.coletapb.data.AppDatabase
import br.edu.utfpr.coletapb.data.dao.ExecutionDao
import br.edu.utfpr.coletapb.data.dao.GpsDao
import br.edu.utfpr.coletapb.data.local.SharedPreferencesHelper
import br.edu.utfpr.coletapb.data.model.ExecutionLocal
import br.edu.utfpr.coletapb.data.model.GpsRecordLocal
import br.edu.utfpr.coletapb.data.remote.RetrofitClient
import br.edu.utfpr.coletapb.data.repository.ExecutionRepository
import br.edu.utfpr.coletapb.data.repository.GpsRepository
import br.edu.utfpr.coletapb.data.repository.SyncRepository
import br.edu.utfpr.coletapb.service.GpsTrackingService
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StartRoute : AppCompatActivity() {

    private var routeStarted = false

    private lateinit var btStart: Button
    private lateinit var btFinish: Button
    private lateinit var btIncident: Button
    private lateinit var btSync: Button

    // DB
    private lateinit var db: AppDatabase
    private lateinit var executionDao: ExecutionDao
    private lateinit var gpsDao: GpsDao

    // Repositórios
    private lateinit var prefsHelper: SharedPreferencesHelper
    private lateinit var executionRepository: ExecutionRepository
    private lateinit var gpsRepository: GpsRepository
    private lateinit var syncRepository: SyncRepository

    // Location
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // Execução
    private var execLocalId: Long = 0L
    private var assignmentId: Long = 0L
    private var backendExecutionId: Long? = null

    // extras
    private var routeId: Long = 0L
    private var routeName: String? = null
    private var routeInfo: String? = null

    private val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
    
    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_start_route)
        
        // Inicializa RetrofitClient se necessário
        RetrofitClient.init(this)
        
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Iniciar rota"

        // extras vindos da RouteList
        routeId = intent.getLongExtra("route_id", 0L)
        assignmentId = intent.getLongExtra("assignment_id", 0L)
        routeName = intent.getStringExtra("route_name")
        routeInfo = intent.getStringExtra("route_info")

        findViewById<TextView>(R.id.tvHeader).text = routeName ?: "Rota"
        findViewById<TextView>(R.id.tvSub).text = routeInfo.orEmpty()

        btStart = findViewById(R.id.btStart)
        btFinish = findViewById(R.id.btFinish)
        btIncident = findViewById(R.id.btIncident)
        // btSync pode ser adicionado ao layout se necessário

        // Inicializa componentes
        prefsHelper = SharedPreferencesHelper(this)
        executionRepository = ExecutionRepository(prefsHelper)
        gpsRepository = GpsRepository(prefsHelper)
        syncRepository = SyncRepository(this, prefsHelper)
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // DB
        db = AppDatabase.getDatabase(this)
        executionDao = db.executionDao()
        gpsDao = db.gpsDao()

        // Verifica se há execução em andamento
        checkCurrentExecution()

        // Restaura estado
        routeStarted = savedInstanceState?.getBoolean("route_started") ?: false
        execLocalId = savedInstanceState?.getLong("exec_local_id") ?: 0L
        backendExecutionId = savedInstanceState?.getLong("backend_exec_id")?.takeIf { it > 0 }
        
        applyUiState()

        btStart.setOnClickListener { onStartRoute() }
        btFinish.setOnClickListener { onFinishRoute() }
        btIncident.setOnClickListener { onIncident() }
    }
    
    private fun checkCurrentExecution() {
        lifecycleScope.launch(Dispatchers.IO) {
            val currentExec = executionDao.getCurrentExecution()
            if (currentExec != null) {
                execLocalId = currentExec.localId
                backendExecutionId = currentExec.backendId
                withContext(Dispatchers.Main) {
                    routeStarted = true
                    applyUiState()
                }
            }
        }
    }

    private fun onStartRoute() {
        if (routeStarted) return
        
        // Verifica permissões de localização
        if (!checkLocationPermissions()) {
            requestLocationPermissions()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Obtém localização atual
                val location = getCurrentLocation()
                val lat = location?.latitude ?: 0.0
                val lng = location?.longitude ?: 0.0
                val now = System.currentTimeMillis()

                // Tenta iniciar no backend primeiro (se online)
                val backendResult = if (syncRepository.isOnline() && assignmentId > 0) {
                    executionRepository.startExecution(assignmentId, lat, lng)
                } else {
                    Result.failure(Exception("Offline ou assignmentId inválido"))
                }

                // Cria execução local
                val executionLocal = ExecutionLocal(
                    routeId = routeId,
                    vehicleId = null, // TODO: obter do assignment
                    driverId = prefsHelper.getDriverId().takeIf { it > 0 },
                    startTimestamp = now,
                    startLat = lat,
                    startLng = lng,
                    status = "IN_PROGRESS",
                    backendId = backendResult.getOrNull()?.id
                )

                execLocalId = executionDao.insert(executionLocal)
                backendExecutionId = executionLocal.backendId

                // Registra ponto START
                gpsDao.insert(
                    GpsRecordLocal(
                        executionLocalId = execLocalId,
                        timestamp = now,
                        lat = lat,
                        lng = lng,
                        eventType = "START",
                        isOffline = backendExecutionId == null
                    )
                )

                // Inicia serviço de rastreamento GPS (se tiver permissão)
                if (checkLocationPermissions()) {
                    startGpsTracking()
                }

                withContext(Dispatchers.Main) {
                    routeStarted = true
                    applyUiState()
                    Toast.makeText(
                        this@StartRoute,
                        "Rota iniciada! GPS ativo.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@StartRoute,
                        "Erro ao iniciar rota: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
    
    private fun getCurrentLocation(): Location? {
        return try {
            if (checkLocationPermissions()) {
                val locationResult = fusedLocationClient.lastLocation
                var location: Location? = null
                val latch = java.util.concurrent.CountDownLatch(1)
                
                locationResult.addOnSuccessListener { loc ->
                    location = loc
                    latch.countDown()
                }.addOnFailureListener {
                    latch.countDown()
                }
                
                latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
                location
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun startGpsTracking() {
        val intent = Intent(this, GpsTrackingService::class.java).apply {
            action = GpsTrackingService.ACTION_START_TRACKING
            putExtra(GpsTrackingService.EXTRA_EXECUTION_ID, execLocalId)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
    
    private fun stopGpsTracking() {
        val intent = Intent(this, GpsTrackingService::class.java).apply {
            action = GpsTrackingService.ACTION_STOP_TRACKING
        }
        startService(intent)
    }

    private fun onIncident() {
        if (!routeStarted || execLocalId == 0L) {
            Toast.makeText(this, "Inicie a rota primeiro.", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Mostra diálogo para descrição do problema
        val input = android.widget.EditText(this)
        input.hint = "Descreva o problema"
        
        AlertDialog.Builder(this)
            .setTitle("Registrar Problema")
            .setView(input)
            .setPositiveButton("Registrar") { _, _ ->
                val description = input.text.toString()
                registerIncident(description)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    private fun registerIncident(description: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val location = getCurrentLocation()
                val lat = location?.latitude ?: 0.0
                val lng = location?.longitude ?: 0.0
                val now = System.currentTimeMillis()

                // Salva localmente
                gpsDao.insert(
                    GpsRecordLocal(
                        executionLocalId = execLocalId,
                        timestamp = now,
                        lat = lat,
                        lng = lng,
                        eventType = "INCIDENT",
                        isOffline = backendExecutionId == null
                    )
                )

                // Tenta enviar ao backend se online
                if (backendExecutionId != null && syncRepository.isOnline()) {
                    gpsRepository.registerGpsPosition(
                        executionId = backendExecutionId!!,
                        latitude = lat,
                        longitude = lng,
                        eventType = "INCIDENT",
                        description = description,
                        isAutomatic = false
                    )
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@StartRoute,
                        "Problema registrado!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@StartRoute,
                        "Erro ao registrar problema: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun onFinishRoute() {
        if (!routeStarted || execLocalId == 0L) return

        AlertDialog.Builder(this)
            .setTitle("Finalizar Rota")
            .setMessage("Deseja realmente finalizar esta rota?")
            .setPositiveButton("Sim") { _, _ ->
                finishRoute()
            }
            .setNegativeButton("Não", null)
            .show()
    }
    
    private fun finishRoute() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Para o rastreamento GPS
                stopGpsTracking()

                val location = getCurrentLocation()
                val lat = location?.latitude ?: 0.0
                val lng = location?.longitude ?: 0.0
                val now = System.currentTimeMillis()

                // Registra ponto END
                gpsDao.insert(
                    GpsRecordLocal(
                        executionLocalId = execLocalId,
                        timestamp = now,
                        lat = lat,
                        lng = lng,
                        eventType = "END",
                        isOffline = backendExecutionId == null
                    )
                )

                // Atualiza execução local
                executionDao.getById(execLocalId)?.let { exec ->
                    val updatedExec = exec.copy(
                        endTimestamp = now,
                        endLat = lat,
                        endLng = lng,
                        status = "COMPLETED"
                    )
                    executionDao.update(updatedExec)

                    // Tenta finalizar no backend se online
                    if (backendExecutionId != null && syncRepository.isOnline()) {
                        executionRepository.completeExecution(
                            executionId = backendExecutionId!!,
                            endLat = lat,
                            endLng = lng
                        )
                    }
                }

                // Sincroniza dados pendentes
                val syncResult = syncRepository.syncPendingData()

                // Monta resumo
                val (startStr, endStr, incidents) = buildSummary(execLocalId)

                withContext(Dispatchers.Main) {
                    routeStarted = false
                    applyUiState()

                    val msg = buildString {
                        append("Rota finalizada!\n")
                        append("Início: $startStr\n")
                        append("Fim: $endStr\n")
                        append("Imprevistos: ${incidents.count}")
                        if (syncResult.syncedGpsRecords > 0) {
                            append("\nSincronizados: ${syncResult.syncedGpsRecords} pontos GPS")
                        }
                    }

                    Toast.makeText(this@StartRoute, msg, Toast.LENGTH_LONG).show()
                    finish() // volta para a lista
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@StartRoute,
                        "Erro ao finalizar rota: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // Resumo para o Toast final (sem "paradas")
    private suspend fun buildSummary(id: Long): Triple<String, String, IncidentInfo> {
        val exec = executionDao.getById(id)
        val startStr = exec?.startTimestamp?.let { sdf.format(Date(it)) } ?: "-"
        val endStr   = exec?.endTimestamp?.let { sdf.format(Date(it)) } ?: "-"

        val incidents = gpsDao.listByExecution(id).filter { it.eventType == "INCIDENT" }
        val times = incidents.joinToString("\n") { " - ${sdf.format(Date(it.timestamp))}" }

        return Triple(startStr, endStr, IncidentInfo(incidents.size, times))
    }

    data class IncidentInfo(val count: Int, val times: String)

    private fun applyUiState() {
        if (routeStarted) {
            btStart.visibility = android.view.View.GONE
            btFinish.visibility = android.view.View.VISIBLE
            btIncident.isEnabled = true
            supportActionBar?.title = "Rota em andamento"
        } else {
            btStart.visibility = android.view.View.VISIBLE
            btFinish.visibility = android.view.View.GONE
            btIncident.isEnabled = false
            supportActionBar?.title = "Iniciar rota"
        }
    }

    private fun checkLocationPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
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
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permissão concedida, pode iniciar a rota
                onStartRoute()
            } else {
                Toast.makeText(
                    this,
                    "Permissão de localização necessária para iniciar a rota.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("route_started", routeStarted)
        outState.putLong("exec_local_id", execLocalId)
        backendExecutionId?.let { outState.putLong("backend_exec_id", it) }
        super.onSaveInstanceState(outState)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (routeStarted) {
            // Não para o serviço GPS aqui, apenas quando finalizar a rota
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (routeStarted) {
            AlertDialog.Builder(this)
                .setTitle("Atenção")
                .setMessage("Há uma rota em andamento. Deseja realmente sair?")
                .setPositiveButton("Sim") { _, _ ->
                    onBackPressedDispatcher.onBackPressed()
                }
                .setNegativeButton("Não", null)
                .show()
            return false
        }
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
