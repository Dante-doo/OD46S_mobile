package br.edu.utfpr.coletapb

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import br.edu.utfpr.coletapb.data.AppDatabase
import br.edu.utfpr.coletapb.data.dao.ExecutionDao
import br.edu.utfpr.coletapb.data.dao.GpsDao
import br.edu.utfpr.coletapb.data.model.ExecutionLocal
import br.edu.utfpr.coletapb.data.model.GpsRecordLocal
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

    // DB
    private lateinit var db: AppDatabase
    private lateinit var executionDao: ExecutionDao
    private lateinit var gpsDao: GpsDao

    // Execução criada ao iniciar
    private var execLocalId: Long = 0L

    // extras
    private var routeId: Long = 0L
    private var routeName: String? = null
    private var routeInfo: String? = null

    private val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_start_route)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Iniciar rota"

        // extras vindos da RouteList
        routeId   = intent.getLongExtra("route_id", 0L)
        routeName = intent.getStringExtra("route_name")
        routeInfo = intent.getStringExtra("route_info")

        findViewById<TextView>(R.id.tvHeader).text = routeName ?: "Rota"
        findViewById<TextView>(R.id.tvSub).text    = routeInfo.orEmpty()

        btStart    = findViewById(R.id.btStart)
        btFinish   = findViewById(R.id.btFinish)
        btIncident = findViewById(R.id.btIncident)

        // DB
        db = AppDatabase.getDatabase(this)
        executionDao = db.executionDao()
        gpsDao = db.gpsDao()

        // restaura estado
        routeStarted = savedInstanceState?.getBoolean("route_started") ?: false
        execLocalId  = savedInstanceState?.getLong("exec_local_id") ?: 0L
        applyUiState()

        btStart.setOnClickListener { onStartRoute() }
        btFinish.setOnClickListener { onFinishRoute() }
        btIncident.setOnClickListener { onIncident() }
    }

    private fun onStartRoute() {
        if (routeStarted) return

        val now = System.currentTimeMillis()
        val lat = 0.0
        val lng = 0.0

        lifecycleScope.launch(Dispatchers.IO) {
            // cria execução com início (0,0)
            execLocalId = executionDao.insert(
                ExecutionLocal(
                    routeId = routeId,
                    startTimestamp = now,
                    startLat = lat,
                    startLng = lng,
                    status = "IN_PROGRESS"
                )
            )
            // registra ponto START (opcional)
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
                Toast.makeText(this@StartRoute, "Rota iniciada!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun onIncident() {
        if (!routeStarted || execLocalId == 0L) {
            Toast.makeText(this, "Inicie a rota primeiro.", Toast.LENGTH_SHORT).show()
            return
        }
        val now = System.currentTimeMillis()
        val lat = 0.0
        val lng = 0.0

        lifecycleScope.launch(Dispatchers.IO) {
            gpsDao.insert(
                GpsRecordLocal(
                    executionLocalId = execLocalId,
                    timestamp = now,
                    lat = lat,
                    lng = lng,
                    eventType = "INCIDENT" // imprevisto
                )
            )
        }
        Toast.makeText(this, "Imprevisto registrado (0,0).", Toast.LENGTH_SHORT).show()
    }

    private fun onFinishRoute() {
        if (!routeStarted || execLocalId == 0L) return

        val now = System.currentTimeMillis()
        val lat = 0.0
        val lng = 0.0

        lifecycleScope.launch(Dispatchers.IO) {
            // atualiza execução com fim (0,0)
            executionDao.getById(execLocalId)?.let { exec ->
                executionDao.update(
                    exec.copy(
                        endTimestamp = now,
                        endLat = lat,
                        endLng = lng,
                        status = "COMPLETED"
                    )
                )
            }
            // registra ponto END (opcional)
            gpsDao.insert(
                GpsRecordLocal(
                    executionLocalId = execLocalId,
                    timestamp = now,
                    lat = lat,
                    lng = lng,
                    eventType = "END"
                )
            )

            // monta resumo
            val (startStr, endStr, incidents) = buildSummary(execLocalId)

            withContext(Dispatchers.Main) {
                routeStarted = false
                applyUiState()

                val msg = """
                    Início: $startStr
                    Fim:    $endStr
                    Lat/Lng início: 0,0
                    Lat/Lng fim:    0,0
                    Imprevistos: ${incidents.count}${if (incidents.count == 0) "" else "\n${incidents.times}"}
                """.trimIndent()

                Toast.makeText(this@StartRoute, msg, Toast.LENGTH_LONG).show()
                finish() // volta para a lista
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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("route_started", routeStarted)
        outState.putLong("exec_local_id", execLocalId)
        super.onSaveInstanceState(outState)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
