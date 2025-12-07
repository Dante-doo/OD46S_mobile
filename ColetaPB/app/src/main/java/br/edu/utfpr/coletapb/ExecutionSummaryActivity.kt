package br.edu.utfpr.coletapb

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import br.edu.utfpr.coletapb.adapter.GpsEventAdapter
import br.edu.utfpr.coletapb.adapter.GpsEventItem
import br.edu.utfpr.coletapb.data.local.SharedPreferencesHelper
import br.edu.utfpr.coletapb.data.remote.RetrofitClient
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tela que exibe o resumo completo de uma execução de rota
 */
class ExecutionSummaryActivity : AppCompatActivity() {
    
    private lateinit var prefsHelper: SharedPreferencesHelper
    private var executionId: Long = 0L
    
    private lateinit var tvRouteName: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvExecutor: TextView
    private lateinit var tvVehicle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvDuration: TextView
    private lateinit var tvProblems: TextView
    private lateinit var rvEvents: RecyclerView
    private lateinit var btnViewMap: Button
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_execution_summary)
        
        // Remove ActionBar padrão
        supportActionBar?.hide()
        
        prefsHelper = SharedPreferencesHelper(this)
        
        executionId = intent.getLongExtra("execution_id", 0L)
        if (executionId == 0L) {
            Toast.makeText(this, "ID de execução inválido", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        // Configura MaterialToolbar
        val toolbar = findViewById<MaterialToolbar>(R.id.topAppBar)
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        
        // Inicializa views
        tvRouteName = findViewById(R.id.tvRouteName)
        tvDate = findViewById(R.id.tvDate)
        tvExecutor = findViewById(R.id.tvExecutor)
        tvVehicle = findViewById(R.id.tvVehicle)
        tvStatus = findViewById(R.id.tvStatus)
        tvDuration = findViewById(R.id.tvDuration)
        tvProblems = findViewById(R.id.tvProblems)
        rvEvents = findViewById(R.id.rvEvents)
        btnViewMap = findViewById(R.id.btnViewMap)
        
        // Configura RecyclerView
        rvEvents.layoutManager = LinearLayoutManager(this)
        
        // Botão ver mapa
        btnViewMap.setOnClickListener {
            val intent = Intent(this, ExecutionMapActivity::class.java).apply {
                putExtra("execution_id", executionId)
            }
            startActivity(intent)
        }
        
        // Carrega dados
        loadExecutionData()
    }
    
    /**
     * Carrega os dados da execução
     */
    private fun loadExecutionData() {
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.getExecutionById(executionId)
                }
                
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    val data = body["data"] as? Map<String, Any>?
                    
                    // Backend retorna { data: { execution: { ... } } }
                    val execution = (data?.get("execution") as? Map<String, Any>) ?: data
                    
                    if (execution != null) {
                        displayExecutionData(execution)
                        loadGpsEvents()
                    } else {
                        Toast.makeText(
                            this@ExecutionSummaryActivity,
                            "Execução não encontrada",
                            Toast.LENGTH_SHORT
                        ).show()
                        finish()
                    }
                } else {
                    val errorMsg = response.errorBody()?.string() ?: "Erro desconhecido"
                    Log.e("ExecutionSummary", "Erro ao carregar execução: ${response.code()} - $errorMsg")
                    Toast.makeText(
                        this@ExecutionSummaryActivity,
                        "Erro ao carregar dados: ${response.code()}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e("ExecutionSummary", "Erro ao carregar execução: ${e.message}", e)
                Toast.makeText(
                    this@ExecutionSummaryActivity,
                    "Erro: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    /**
     * Exibe os dados da execução
     */
    private fun displayExecutionData(execution: Map<String, Any>) {
        // Os dados estão dentro de assignment
        val assignment = execution["assignment"] as? Map<*, *>
        val route = assignment?.get("route") as? Map<*, *>
        val driver = assignment?.get("driver") as? Map<*, *>
        val vehicle = assignment?.get("vehicle") as? Map<*, *>
        
        tvRouteName.text = route?.get("name") as? String ?: "Rota"
        
        val startTime = execution["startTime"] as? String
        if (startTime != null) {
            // Converte de UTC para timezone local e formata
            val date = br.edu.utfpr.coletapb.utils.DateUtils.parseUtcToLocal(startTime)
            tvDate.text = if (date != null) {
                br.edu.utfpr.coletapb.utils.DateUtils.formatDateOnly(date) + " · " + 
                br.edu.utfpr.coletapb.utils.DateUtils.formatTimeOnly(date)
            } else {
                startTime.substring(0, 10) // Fallback
            }
        } else {
            tvDate.text = "Data não disponível"
        }
        
        val executorName = driver?.get("name") as? String ?: "N/A"
        val executorType = execution["executorType"] as? String ?: "DRIVER"
        tvExecutor.text = "Executada por: $executorName ($executorType)"
        
        val vehiclePlate = vehicle?.get("licensePlate") as? String
            ?: vehicle?.get("plate") as? String // Fallback para "plate"
            ?: "N/A"
        tvVehicle.text = "Caminhão: $vehiclePlate"
        
        val status = execution["status"] as? String ?: "UNKNOWN"
        tvStatus.text = when (status) {
            "COMPLETED" -> "Status: Concluída"
            "IN_PROGRESS" -> "Status: Em andamento"
            "CANCELLED" -> "Status: Cancelada"
            else -> "Status: $status"
        }
        
        // Calcula duração baseada na diferença entre startTime e endTime
        val start = execution["startTime"] as? String
        val end = execution["endTime"] as? String
        
        Log.d("ExecutionSummary", "startTime: $start, endTime: $end")
        
        if (start != null && end != null) {
            val startDate = br.edu.utfpr.coletapb.utils.DateUtils.parseUtcToLocal(start)
            val endDate = br.edu.utfpr.coletapb.utils.DateUtils.parseUtcToLocal(end)
            Log.d("ExecutionSummary", "startDate parsed: $startDate, endDate parsed: $endDate")
            if (startDate != null && endDate != null) {
                val duration = br.edu.utfpr.coletapb.utils.DateUtils.calculateDuration(startDate, endDate)
                Log.d("ExecutionSummary", "Duração calculada: $duration")
                tvDuration.text = "Duração: $duration"
            } else {
                Log.w("ExecutionSummary", "Não foi possível fazer parse das datas para calcular duração")
                tvDuration.text = "Duração: -"
            }
        } else if (start != null) {
            // Se só tem startTime, calcula até agora
            val startDate = br.edu.utfpr.coletapb.utils.DateUtils.parseUtcToLocal(start)
            if (startDate != null) {
                val now = java.util.Date()
                val duration = br.edu.utfpr.coletapb.utils.DateUtils.calculateDuration(startDate, now)
                tvDuration.text = "Duração: $duration (em andamento)"
            } else {
                tvDuration.text = "Duração: -"
            }
        } else {
            Log.w("ExecutionSummary", "startTime é null, não é possível calcular duração")
            tvDuration.text = "Duração: -"
        }
        
        // Conta problemas dos eventos GPS (será atualizado quando carregar eventos)
        tvProblems.text = "Problemas: -"
    }
    
    /**
     * Carrega os eventos GPS da execução
     */
    private fun loadGpsEvents() {
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.getGpsTrace(executionId)
                }
                
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    val data = body["data"] as? Map<String, Any>?
                    
                    // Backend retorna { data: { gps_track: [...], statistics: {...} } }
                    val gpsTrack = (data?.get("gps_track") as? List<Map<String, Any>>) ?: emptyList()
                    
                    Log.d("ExecutionSummary", "Carregados ${gpsTrack.size} eventos GPS")
                    
                    // Filtra apenas eventos relevantes (não NORMAL) e ordena por timestamp
                    val relevantEvents = gpsTrack.filter { record ->
                        val eventType = record["eventType"] as? String
                        eventType != null && eventType != "NORMAL"
                    }.sortedBy { record ->
                        val timestamp = record["gpsTimestamp"] as? String
                        timestamp ?: ""
                    }
                    
                    // Conta problemas
                    val problemCount = relevantEvents.count { 
                        (it["eventType"] as? String) == "PROBLEM" 
                    }
                    tvProblems.text = "Problemas: $problemCount"
                    
                    // Cria lista de eventos formatados para exibição
                    val eventsList = relevantEvents.mapNotNull { record ->
                        val eventType = record["eventType"] as? String ?: return@mapNotNull null
                        val timestamp = record["gpsTimestamp"] as? String
                        val description = record["description"] as? String ?: ""
                        val collectedWeight = record["collectedWeightKg"] as? Number
                        val pointCondition = record["pointCondition"] as? String
                        
                        val timeStr = timestamp?.let { ts ->
                            val date = br.edu.utfpr.coletapb.utils.DateUtils.parseUtcToLocal(ts)
                            date?.let { br.edu.utfpr.coletapb.utils.DateUtils.formatTimeOnly(it) } ?: ts.substring(11, 16)
                        } ?: "-"
                        
                        // Monta descrição do tipo de evento com informações adicionais
                        val eventName = when (eventType) {
                            "START" -> "Início da coleta"
                            "POINT_COLLECTED" -> {
                                val weightInfo = collectedWeight?.let { " - ${String.format("%.1f", it.toDouble())} kg" } ?: ""
                                val conditionInfo = pointCondition?.let { 
                                    when (it) {
                                        "NORMAL" -> " (Normal)"
                                        "SATURATED" -> " (Saturado)"
                                        "DAMAGED" -> " (Danificado)"
                                        "INACCESSIBLE" -> " (Inacessível)"
                                        else -> ""
                                    }
                                } ?: ""
                                "Ponto coletado$weightInfo$conditionInfo"
                            }
                            "POINT_SKIPPED" -> {
                                val conditionInfo = pointCondition?.let { 
                                    when (it) {
                                        "INACCESSIBLE" -> " (Inacessível)"
                                        "DAMAGED" -> " (Danificado)"
                                        else -> " (Ponto pulado)"
                                    }
                                } ?: " (Ponto pulado)"
                                "Ponto não coletado$conditionInfo"
                            }
                            "PROBLEM" -> "Problema"
                            "POINT_PROBLEM" -> "Problema no ponto"
                            "STOP" -> "Parada"
                            "BREAK" -> "Intervalo"
                            "LUNCH" -> "Almoço"
                            "FUEL" -> "Abastecimento"
                            "END" -> "Fim da coleta"
                            else -> eventType
                        }
                        
                        // Monta descrição completa
                        val fullDescription = buildString {
                            if (description.isNotEmpty()) {
                                append(description)
                            }
                            if (collectedWeight != null && eventType == "POINT_COLLECTED") {
                                if (isNotEmpty()) append(" | ")
                                append("Peso: ${String.format("%.1f", collectedWeight.toDouble())} kg")
                            }
                        }
                        
                        GpsEventItem(
                            time = timeStr,
                            eventType = eventName,
                            description = fullDescription
                        )
                    }
                    
                    // Exibe eventos no RecyclerView
                    withContext(Dispatchers.Main) {
                        if (eventsList.isNotEmpty()) {
                            rvEvents.adapter = GpsEventAdapter(eventsList)
                            Log.d("ExecutionSummary", "Exibindo ${eventsList.size} eventos na lista (filtrados de ${gpsTrack.size} eventos totais)")
                            eventsList.forEachIndexed { index, event ->
                                Log.d("ExecutionSummary", "Evento $index: ${event.time} - ${event.eventType}")
                            }
                        } else {
                            Log.d("ExecutionSummary", "Nenhum evento relevante para exibir (total de ${gpsTrack.size} eventos, todos são NORMAL)")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ExecutionSummary", "Erro ao carregar eventos: ${e.message}", e)
            }
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

