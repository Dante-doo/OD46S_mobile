package br.edu.utfpr.coletapb

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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

    // Estado
    private var routeStarted = false
    private var execLocalId: Long = 0L

    // Dados da Intent
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
            // Permissão concedida, inicia a rota
            startRouteWithLocation()
        } else {
            Toast.makeText(this, "Permissão de GPS necessária!", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_start_route)

        // Configurações Iniciais
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Iniciar rota"

        routeId = intent.getLongExtra("route_id", 0L)
        routeName = intent.getStringExtra("route_name")

        findViewById<TextView>(R.id.tvHeader).text = routeName ?: "Rota"
        tvStatus = findViewById(R.id.tvSub) // Usando tvSub para mostrar status do GPS

        btStart = findViewById(R.id.btStart)
        btFinish = findViewById(R.id.btFinish)
        btIncident = findViewById(R.id.btIncident)

        db = AppDatabase.getDatabase(this)
        executionDao = db.executionDao()
        gpsDao = db.gpsDao()

        // Inicializa cliente de GPS
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        setupLocationCallback()

        // Restaura estado
        if (savedInstanceState != null) {
            routeStarted = savedInstanceState.getBoolean("route_started")
            execLocalId = savedInstanceState.getLong("exec_local_id")
            if (routeStarted) startLocationUpdates() // Retoma GPS se estava rodando
        }
        applyUiState()

        // Listeners
        btStart.setOnClickListener { checkPermissionsAndStart() }
        btFinish.setOnClickListener { finishRoute() }
        btIncident.setOnClickListener { registerIncident() }
    }

    // 1. Verifica permissão antes de iniciar
    private fun checkPermissionsAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startRouteWithLocation()
        } else {
            requestPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    // 2. Inicia recebimento de atualizações de GPS
    private fun startRouteWithLocation() {
        startLocationUpdates() // Começa a ouvir o GPS

        Toast.makeText(this, "Obtendo GPS...", Toast.LENGTH_SHORT).show()

        // Pega a última localização conhecida para iniciar IMEDIATAMENTE (opcional)
        // ou espera o primeiro callback. Aqui vamos tentar pegar a última para agilizar o Start.
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                val lat = location?.latitude ?: 0.0
                val lng = location?.longitude ?: 0.0
                createExecution(lat, lng)
            }
        } catch (e: SecurityException) {
            createExecution(0.0, 0.0) // Fallback se der erro
        }
    }

    // 3. Cria a execução no BD e API
    private fun createExecution(lat: Double, lng: Double) {
        if (routeStarted) return

        lifecycleScope.launch(Dispatchers.IO) {
            // Tenta API
            var serverId: Long? = null
            try {
                // TODO: Pegar ID real do Assignment e KM atual
                val req = StartExecutionRequest(1, 10000, lat, lng)
                val res = RetrofitClient.apiService.startExecution(req)
                if (res.isSuccessful) {
                    serverId = res.body()?.data?.execution?.id
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Grava Local
            val now = System.currentTimeMillis()
            execLocalId = executionDao.insert(
                ExecutionLocal(
                    routeId = routeId,
                    serverExecutionId = serverId,
                    status = "IN_PROGRESS",
                    startLat = lat,
                    startLng = lng,
                    startTimestamp = now
                )
            )

            // Registra ponto START
            gpsDao.insert(GpsRecordLocal(executionLocalId = execLocalId, timestamp = now, lat = lat, lng = lng, eventType = "START"))

            withContext(Dispatchers.Main) {
                routeStarted = true
                applyUiState()
                Toast.makeText(this@StartRoute, "Rota Iniciada! (Server: ${serverId ?: "OFF"})", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 4. Configura o loop de GPS
    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    lastLocation = location
                    tvStatus.text = "GPS: ${location.latitude}, ${location.longitude}"

                    // Se a rota está ativa, salva o ponto automaticamente
                    if (routeStarted && execLocalId != 0L) {
                        saveGpsPoint(location)
                    }
                }
            }
        }
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000) // 10 segundos
            .setMinUpdateIntervalMillis(5000) // Mínimo 5s
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
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

    // 5. Finaliza Rota e Sincroniza
    private fun finishRoute() {
        stopLocationUpdates() // Para o GPS

        val lat = lastLocation?.latitude ?: 0.0
        val lng = lastLocation?.longitude ?: 0.0

        lifecycleScope.launch(Dispatchers.IO) {
            // ... (Lógica de sincronização igual à anterior) ...
            // Sincroniza com API...
            val localExec = executionDao.getById(execLocalId)
            val serverId = localExec?.serverExecutionId

            if (serverId != null) {
                val points = gpsDao.listByExecution(execLocalId)
                val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)

                val batch = points.map {
                    GpsRecordRequest(it.lat, it.lng, isoFormat.format(Date(it.timestamp)), it.eventType)
                }

                try {
                    RetrofitClient.apiService.sendGpsBatch(serverId, batch)
                    RetrofitClient.apiService.completeExecution(serverId, CompleteExecutionRequest(10050, lat, lng))
                    withContext(Dispatchers.Main) { Toast.makeText(applicationContext, "Sincronizado!", Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(applicationContext, "Erro Sync: ${e.message}", Toast.LENGTH_SHORT).show() }
                }
            }

            // Atualiza Local
            executionDao.update(localExec!!.copy(endTimestamp = System.currentTimeMillis(), status = "COMPLETED", endLat = lat, endLng = lng))

            withContext(Dispatchers.Main) {
                finish()
            }
        }
    }

    private fun registerIncident() {
        val lat = lastLocation?.latitude ?: 0.0
        val lng = lastLocation?.longitude ?: 0.0
        lifecycleScope.launch(Dispatchers.IO) {
            gpsDao.insert(GpsRecordLocal(executionLocalId = execLocalId, timestamp = System.currentTimeMillis(), lat = lat, lng = lng, eventType = "INCIDENT"))
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
        super.onSaveInstanceState(outState)
    }
}