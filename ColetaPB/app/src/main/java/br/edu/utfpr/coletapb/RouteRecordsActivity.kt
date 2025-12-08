package br.edu.utfpr.coletapb

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import br.edu.utfpr.coletapb.data.local.SharedPreferencesHelper
import br.edu.utfpr.coletapb.data.remote.RetrofitClient
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
// DateUtils já importado implicitamente através do uso completo do caminho

/**
 * Tela que exibe os registros (eventos GPS) da execução atual em andamento
 * Mostra coletas, problemas e paradas registradas
 */
class RouteRecordsActivity : AppCompatActivity() {
    
    private lateinit var prefsHelper: SharedPreferencesHelper
    private var executionId: Long = 0L
    
    private lateinit var toolbar: MaterialToolbar
    private lateinit var recyclerView: RecyclerView
    private lateinit var llEmptyState: View
    private lateinit var imgEmptyIcon: ImageView
    private lateinit var tvEmptyTitle: TextView
    private lateinit var tvEmptySubtitle: TextView
    private lateinit var btnBackToRoute: Button
    
    private lateinit var adapter: RouteRecordAdapter
    private var records: MutableList<GpsEventItem> = mutableListOf()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_route_records)
        
        // Remove ActionBar padrão
        supportActionBar?.hide()
        
        prefsHelper = SharedPreferencesHelper(this)
        
        executionId = intent.getLongExtra("execution_id", 0L)
        if (executionId == 0L) {
            finish()
            return
        }
        
        // Inicializa views
        toolbar = findViewById(R.id.topAppBar)
        recyclerView = findViewById(R.id.rvRecords)
        llEmptyState = findViewById(R.id.llEmptyState)
        imgEmptyIcon = findViewById(R.id.imgEmptyIcon)
        tvEmptyTitle = findViewById(R.id.tvEmptyTitle)
        tvEmptySubtitle = findViewById(R.id.tvEmptySubtitle)
        btnBackToRoute = findViewById(R.id.btnBackToRoute)
        
        // Configura toolbar
        toolbar.title = "Registros da rota"
        toolbar.setNavigationOnClickListener {
            finish()
        }
        
        // Botão voltar no estado vazio
        btnBackToRoute.setOnClickListener {
            finish()
        }
        
        // Configura RecyclerView
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = RouteRecordAdapter(records)
        recyclerView.adapter = adapter
        
        // Carrega registros
        loadRecords()
    }
    
    /**
     * Carrega os registros GPS da execução
     */
    private fun loadRecords() {
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
                    
                    Log.d("RouteRecords", "Carregados ${gpsTrack.size} eventos GPS")
                    
                    // Filtra apenas eventos relevantes (não NORMAL) e ordena por timestamp
                    val relevantEvents = gpsTrack.filter { record ->
                        val eventType = record["eventType"] as? String ?: record["event_type"] as? String
                        eventType != null && eventType != "NORMAL"
                    }.sortedBy { record ->
                        val timestamp = record["gpsTimestamp"] as? String 
                            ?: record["gps_timestamp"] as? String
                        timestamp ?: ""
                    }
                    
                    // Cria lista de eventos formatados para exibição
                    val eventsList = relevantEvents.mapNotNull { record ->
                        val eventType = record["eventType"] as? String 
                            ?: record["event_type"] as? String ?: return@mapNotNull null
                        
                        val timestamp = record["gpsTimestamp"] as? String 
                            ?: record["gps_timestamp"] as? String ?: ""
                        
                        val description = record["description"] as? String ?: ""
                        val collectedWeight = record["collectedWeightKg"] as? Number
                            ?: record["collected_weight_kg"] as? Number
                        
                        // Formata hora - backend envia em UTC, converte para timezone local
                        val timeStr = try {
                            if (timestamp.isNotEmpty()) {
                                // Backend envia em UTC (formato ISO 8601), converte para timezone local
                                val date = br.edu.utfpr.coletapb.utils.DateUtils.parseUtcToLocal(timestamp)
                                if (date != null) {
                                    br.edu.utfpr.coletapb.utils.DateUtils.formatTimeOnly(date)
                                } else {
                                    ""
                                }
                            } else {
                                ""
                            }
                        } catch (e: Exception) {
                            Log.e("RouteRecords", "Erro ao formatar timestamp: ${e.message}", e)
                            ""
                        }
                        
                        // Nome do evento
                        val eventName = when (eventType) {
                            "START" -> "Início"
                            "END" -> "Fim"
                            "POINT_COLLECTED" -> "Coleta"
                            "PROBLEM" -> "Problema"
                            "STOP", "BREAK" -> "Parada"
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
                    
                    // Atualiza UI
                    withContext(Dispatchers.Main) {
                        records.clear()
                        records.addAll(eventsList)
                        adapter.notifyDataSetChanged()
                        
                        // Mostra estado vazio ou lista
                        if (eventsList.isEmpty()) {
                            recyclerView.visibility = View.GONE
                            llEmptyState.visibility = View.VISIBLE
                        } else {
                            recyclerView.visibility = View.VISIBLE
                            llEmptyState.visibility = View.GONE
                        }
                    }
                } else {
                    val errorMsg = response.errorBody()?.string() ?: "Erro desconhecido"
                    Log.e("RouteRecords", "Erro ao carregar registros: ${response.code()} - $errorMsg")
                    withContext(Dispatchers.Main) {
                        recyclerView.visibility = View.GONE
                        llEmptyState.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                Log.e("RouteRecords", "Exceção ao carregar registros: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    recyclerView.visibility = View.GONE
                    llEmptyState.visibility = View.VISIBLE
                }
            }
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
    
    /**
     * Item de evento GPS para exibição
     */
    data class GpsEventItem(
        val time: String,
        val eventType: String,
        val description: String
    )
    
    /**
     * Adapter para lista de registros
     */
    private class RouteRecordAdapter(
        private val items: List<GpsEventItem>
    ) : RecyclerView.Adapter<RouteRecordAdapter.ViewHolder>() {
        
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvTime: TextView = view.findViewById(R.id.tvTime)
            val tvEventType: TextView = view.findViewById(R.id.tvEventType)
            val tvDescription: TextView = view.findViewById(R.id.tvDescription)
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_route_record, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvTime.text = item.time
            holder.tvEventType.text = item.eventType
            holder.tvDescription.text = item.description
        }
        
        override fun getItemCount(): Int = items.size
    }
}

