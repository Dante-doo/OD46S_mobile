package br.edu.utfpr.coletapb

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import br.edu.utfpr.coletapb.data.AppDatabase
import br.edu.utfpr.coletapb.data.dao.ExecutionDao
import br.edu.utfpr.coletapb.data.dao.GpsDao
import br.edu.utfpr.coletapb.data.model.CompleteExecutionRequest
import br.edu.utfpr.coletapb.data.model.ExecutionLocal
import br.edu.utfpr.coletapb.data.model.GpsRecordLocal
import br.edu.utfpr.coletapb.data.model.GpsRecordRequest
import br.edu.utfpr.coletapb.data.model.StartExecutionRequest
import br.edu.utfpr.coletapb.data.remote.RetrofitClient
import com.google.android.gms.location.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StartRoute : AppCompatActivity() {

    // Controles de UI
    private lateinit var btStart: Button
    private lateinit var btFinish: Button
    private lateinit var btIncident: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvHeader: TextView
    private lateinit var tvSub: TextView

    // Estado
    private var routeStarted = false
    private var execLocalId: Long = 0L
    private var currentAssignmentId: Long? = null // ID da escala vindo da API

    // Dados da Intent (apenas visual, pois o ID real vem da API)
    private var routeId: Long = 0L
    private var routeName: String? = null

    // Banco de Dados
    private lateinit var db: AppDatabase
    private lateinit var executionDao: ExecutionDao
    private lateinit var gpsDao: GpsDao

    // --- GPS ---
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var lastLocation: Location? = null

    // Launcher para pedir permissão
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            // Permissão concedida, mostra o dialog de KM
            showKmDialog(isStart = true)
        } else {
            Toast.makeText(this, "Permissão de GPS necessária para iniciar!", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_start_route)

        // Configurações Iniciais da UI
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Iniciar rota"

        routeId = intent.getLongExtra("route_id", 0L)
        routeName = intent.getStringExtra("route_name")

        tvHeader = findViewById(R.id.tvHeader)
        tvSub = findViewById(R.id.tvSub)
        tvStatus = tvSub // Alias para facilitar leitura

        tvHeader.text = routeName ?: "Carregando rota..."
        tvSub.text = "Aguardando início..."

        btStart = findViewById(R.id.btStart)
        btFinish = findViewById(R.id.btFinish)
        btIncident = findViewById(R.id.btIncident)

        // Inicializa Banco de Dados
        db = AppDatabase.getDatabase(this)
        executionDao = db.executionDao()
        gpsDao = db.gpsDao()

        // Inicializa cliente de GPS
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        setupLocationCallback()

        // Restaura estado se houver rotação de tela
        if (savedInstanceState != null) {
            routeStarted = savedInstanceState.getBoolean("route_started")
            execLocalId = savedInstanceState.getLong("exec_local_id")
            currentAssignmentId = savedInstanceState.getLong("assignment_id").takeIf { it != 0L }

            if (routeStarted) {
                startLocationUpdates() // Retoma GPS se estava rodando
            }
        }

        applyUiState()

        // 1. Tenta buscar a escala automaticamente ao abrir
        fetchAssignment()

        // Listeners dos Botões
        btStart.setOnClickListener {
            if (currentAssignmentId == null) {
                Toast.makeText(this, "Buscando dados da escala...", Toast.LENGTH_SHORT).show()
                fetchAssignment() // Tenta buscar de novo se falhou antes
            } else {
                checkPermissionsAndShowDialog()
            }
        }

        btFinish.setOnClickListener {
            showKmDialog(isStart = false)
        }

        btIncident.setOnClickListener {
            registerIncident()
        }
    }

    // --- Lógica de Negócio ---

    private fun fetchAssignment() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Importante: Usar getApiService(context) para injetar o Token
                val response = RetrofitClient.getApiService(applicationContext).getMyAssignment()

                if (response.isSuccessful) {
                    val assignment = response.body()?.data?.assignment
                    currentAssignmentId = assignment?.id

                    withContext(Dispatchers.Main) {
                        if (assignment != null) {
                            tvHeader.text = assignment.route.name
                            tvSub.text = "Veículo: ${assignment.vehicle.license_plate}"
                            Toast.makeText(this@StartRoute, "Escala #${assignment.id} carregada", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        tvSub.text = "Erro ao carregar escala"
                        // Não mostramos Toast de erro aqui para não incomodar na abertura,
                        // o usuário verá erro se tentar clicar em Iniciar.
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    tvSub.text = "Sem conexão para carregar escala"
                }
            }
        }
    }

    private fun checkPermissionsAndShowDialog() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            showKmDialog(isStart = true)
        } else {
            requestPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    private fun showKmDialog(isStart: Boolean) {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.hint = if (isStart) "KM Inicial (ex: 10500)" else "KM Final"

        AlertDialog.Builder(this)
            .setTitle(if (isStart) "Iniciar Rota" else "Finalizar Rota")
            .setMessage("Informe a quilometragem do painel:")
            .setView(input)
            .setPositiveButton("Confirmar") { _, _ ->
                val kmStr = input.text.toString()
                if (kmStr.isNotEmpty()) {
                    val km = kmStr.toInt()
                    if (isStart) {
                        startRouteWithLocation(km)
                    } else {
                        finishRoute(km)
                    }
                } else {
                    Toast.makeText(this, "Quilometragem é obrigatória!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun startRouteWithLocation(initialKm: Int) {
        startLocationUpdates() // Liga o GPS
        Toast.makeText(this, "Iniciando coleta...", Toast.LENGTH_SHORT).show()

        // Tenta pegar a última localização para já salvar o start com coordenadas
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                val lat = location?.latitude ?: 0.0
                val lng = location?.longitude ?: 0.0
                createExecution(lat, lng, initialKm)
            }.addOnFailureListener {
                createExecution(0.0, 0.0, initialKm)
            }
        } catch (e: SecurityException) {
            createExecution(0.0, 0.0, initialKm)
        }
    }

    private fun createExecution(lat: Double, lng: Double, initialKm: Int) {
        if (routeStarted) return

        lifecycleScope.launch(Dispatchers.IO) {
            var serverId: Long? = null

            // 1. Tenta iniciar na API
            try {
                if (currentAssignmentId != null) {
                    val req = StartExecutionRequest(
                        assignment_id = currentAssignmentId!!,
                        initial_km = initialKm,
                        latitude = lat,
                        longitude = lng
                    )
                    val res = RetrofitClient.getApiService(applicationContext).startExecution(req)
                    if (res.isSuccessful) {
                        serverId = res.body()?.data?.execution?.id
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 2. Grava no Banco Local
            val now = System.currentTimeMillis()
            execLocalId = executionDao.insert(
                ExecutionLocal(
                    routeId = routeId, // Usamos o ID que veio da tela anterior apenas como referência local
                    serverExecutionId = serverId,
                    status = "IN_PROGRESS",
                    startLat = lat,
                    startLng = lng,
                    startTimestamp = now
                )
            )

            // 3. Registra ponto START
            gpsDao.insert(
                GpsRecordLocal(
                    executionLocalId = execLocalId,
                    timestamp = now,
                    lat = lat,
                    lng = lng,
                    eventType = "START"
                )
            )

            withContext(Dispatchers.Main) {
                routeStarted = true
                applyUiState()
                val msg = if (serverId != null) "Rota iniciada! (Sync OK)" else "Rota iniciada Offline!"
                Toast.makeText(this@StartRoute, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun finishRoute(finalKm: Int) {
        stopLocationUpdates() // Desliga GPS

        // Pega última posição conhecida para o evento de fim
        val lat = lastLocation?.latitude ?: 0.0
        val lng = lastLocation?.longitude ?: 0.0

        lifecycleScope.launch(Dispatchers.IO) {
            // 1. Recupera dados locais
            val localExec = executionDao.getById(execLocalId)
            val serverId = localExec?.serverExecutionId

            // 2. Se tem ID do servidor, tenta sincronizar
            if (serverId != null) {
                try {
                    // Prepara batch de GPS
                    val points = gpsDao.listByExecution(execLocalId)
                    if (points.isNotEmpty()) {
                        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                        val batch = points.map {
                            GpsRecordRequest(
                                latitude = it.lat,
                                longitude = it.lng,
                                gps_timestamp = isoFormat.format(Date(it.timestamp)),
                                event_type = it.eventType
                            )
                        }

                        // Envia lote
                        RetrofitClient.getApiService(applicationContext).sendGpsBatch(serverId, batch)
                    }

                    // Envia finalização da rota
                    RetrofitClient.getApiService(applicationContext).completeExecution(
                        serverId,
                        CompleteExecutionRequest(finalKm, lat, lng)
                    )

                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "Dados sincronizados com sucesso!", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "Erro na sincronização (salvo offline)", Toast.LENGTH_LONG).show()
                    }
                }
            }

            // 3. Atualiza localmente
            if (localExec != null) {
                executionDao.update(
                    localExec.copy(
                        endTimestamp = System.currentTimeMillis(),
                        status = "COMPLETED",
                        endLat = lat,
                        endLng = lng
                    )
                )
            }

            // Registra ponto END
            gpsDao.insert(
                GpsRecordLocal(
                    executionLocalId = execLocalId,
                    timestamp = System.currentTimeMillis(),
                    lat = lat,
                    lng = lng,
                    eventType = "END"
                )
            )

            withContext(Dispatchers.Main) {
                finish() // Fecha a tela
            }
        }
    }

    // --- GPS Utils ---

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    lastLocation = location

                    // Atualiza UI
                    tvStatus.text = "GPS: %.4f, %.4f".format(location.latitude, location.longitude)

                    // Salva ponto automático se estiver em rota
                    if (routeStarted && execLocalId != 0L) {
                        saveGpsPoint(location)
                    }
                }
            }
        }
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
            .setMinUpdateIntervalMillis(5000)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun stopLocationUpdates() {
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveGpsPoint(loc: Location) {
        lifecycleScope.launch(Dispatchers.IO) {
            gpsDao.insert(
                GpsRecordLocal(
                    executionLocalId = execLocalId,
                    timestamp = loc.time,
                    lat = loc.latitude,
                    lng = loc.longitude,
                    eventType = "NORMAL"
                )
            )
        }
    }

    private fun registerIncident() {
        if (!routeStarted) return

        val lat = lastLocation?.latitude ?: 0.0
        val lng = lastLocation?.longitude ?: 0.0

        lifecycleScope.launch(Dispatchers.IO) {
            gpsDao.insert(
                GpsRecordLocal(
                    executionLocalId = execLocalId,
                    timestamp = System.currentTimeMillis(),
                    lat = lat,
                    lng = lng,
                    eventType = "INCIDENT"
                )
            )
        }
        Toast.makeText(this, "Imprevisto registrado!", Toast.LENGTH_SHORT).show()
    }

    private fun applyUiState() {
        if (routeStarted) {
            btStart.visibility = android.view.View.GONE
            btFinish.visibility = android.view.View.VISIBLE
            btIncident.isEnabled = true
        } else {
            btStart.visibility = android.view.View.VISIBLE
            btFinish.visibility = android.view.View.GONE
            btIncident.isEnabled = false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("route_started", routeStarted)
        outState.putLong("exec_local_id", execLocalId)
        if (currentAssignmentId != null) {
            outState.putLong("assignment_id", currentAssignmentId!!)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}