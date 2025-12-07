package br.edu.utfpr.coletapb

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import br.edu.utfpr.coletapb.adapter.ExecutionHistoryAdapter
import br.edu.utfpr.coletapb.data.local.SharedPreferencesHelper
import br.edu.utfpr.coletapb.data.model.Execution
import br.edu.utfpr.coletapb.data.remote.RetrofitClient
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tela que exibe o histórico de execuções (rotas concluídas)
 */
class ExecutionHistoryActivity : AppCompatActivity() {
    
    private lateinit var prefsHelper: SharedPreferencesHelper
    private lateinit var listView: ListView
    private lateinit var tvTitle: TextView
    private lateinit var btnRefresh: Button
    private var adapter: ExecutionHistoryAdapter? = null
    private var executions: MutableList<Execution> = mutableListOf()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_execution_history)
        
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Histórico de Rotas"
        
        prefsHelper = SharedPreferencesHelper(this)
        
        // Inicializa views
        listView = findViewById(R.id.lvExecutions)
        tvTitle = findViewById(R.id.tvTitle)
        btnRefresh = findViewById(R.id.btnRefresh)
        
        // Configura título
        val userType = prefsHelper.getUserType()
        tvTitle.text = if (userType == "ADMIN") {
            "Histórico de Execuções"
        } else {
            "Minhas Rotas Concluídas"
        }
        
        // Configura adapter
        adapter = ExecutionHistoryAdapter(this, executions) { execution ->
            onExecutionClick(execution)
        }
        listView.adapter = adapter
        
        // Botão refresh
        btnRefresh.setOnClickListener {
            loadExecutions()
        }
        
        // Carrega execuções
        loadExecutions()
    }
    
    /**
     * Carrega a lista de execuções concluídas
     */
    private fun loadExecutions() {
        lifecycleScope.launch {
            try {
                val userType = prefsHelper.getUserType()
                val userId = prefsHelper.getUserId()
                
                val response = withContext(Dispatchers.IO) {
                    // Se for DRIVER, filtra por driver_id
                    // Se for ADMIN, pode buscar todas ou apenas as suas
                    val driverId = if (userType == "DRIVER") {
                        prefsHelper.getDriverId().takeIf { it > 0 }
                    } else {
                        null // ADMIN pode ver todas
                    }
                    
                    RetrofitClient.apiService.getExecutions(
                        assignmentId = null,
                        driverId = driverId,
                        status = "COMPLETED"
                    )
                }
                
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    val data = body["data"] as? Map<String, Any>?
                    val executionsList = (data?.get("executions") as? List<Map<String, Any>>) 
                        ?: emptyList()
                    
                    executions.clear()
                    
                    executionsList.forEach { execMap ->
                        try {
                            val execution = Execution(
                                id = (execMap["id"] as? Number)?.toLong() ?: 0L,
                                assignmentId = (execMap["assignmentId"] as? Number)?.toLong()
                                    ?: ((execMap["assignment"] as? Map<*, *>)?.get("id") as? Number)?.toLong()
                                    ?: 0L,
                                routeId = ((execMap["route"] as? Map<*, *>)?.get("id") as? Number)?.toLong()
                                    ?: (((execMap["assignment"] as? Map<*, *>)?.get("route") as? Map<*, *>)?.get("id") as? Number)?.toLong()
                                    ?: 0L,
                                routeName = (execMap["route"] as? Map<*, *>)?.get("name") as? String
                                    ?: (((execMap["assignment"] as? Map<*, *>)?.get("route") as? Map<*, *>)?.get("name") as? String),
                                driverId = ((execMap["driver"] as? Map<*, *>)?.get("id") as? Number)?.toLong()
                                    ?: (((execMap["assignment"] as? Map<*, *>)?.get("driver") as? Map<*, *>)?.get("id") as? Number)?.toLong()
                                    ?: 0L,
                                vehicleId = ((execMap["vehicle"] as? Map<*, *>)?.get("id") as? Number)?.toLong()
                                    ?: (((execMap["assignment"] as? Map<*, *>)?.get("vehicle") as? Map<*, *>)?.get("id") as? Number)?.toLong()
                                    ?: 0L,
                                status = execMap["status"] as? String ?: "UNKNOWN",
                                startTime = execMap["startTime"] as? String,
                                endTime = execMap["endTime"] as? String,
                                startLat = (execMap["startLat"] as? Number)?.toDouble(),
                                startLng = (execMap["startLng"] as? Number)?.toDouble(),
                                endLat = (execMap["endLat"] as? Number)?.toDouble(),
                                endLng = (execMap["endLng"] as? Number)?.toDouble()
                            )
                            
                            if (execution.id > 0L) {
                                executions.add(execution)
                            }
                        } catch (e: Exception) {
                            Log.e("ExecutionHistory", "Erro ao mapear execução: ${e.message}", e)
                        }
                    }
                    
                    adapter?.notifyDataSetChanged()
                    
                    if (executions.isEmpty()) {
                        Toast.makeText(
                            this@ExecutionHistoryActivity,
                            "Nenhuma rota concluída encontrada",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Log.d("ExecutionHistory", "Carregadas ${executions.size} execuções")
                    }
                } else {
                    val errorMsg = response.errorBody()?.string() ?: "Erro desconhecido"
                    Log.e("ExecutionHistory", "Erro ao carregar execuções: ${response.code()} - $errorMsg")
                    Toast.makeText(
                        this@ExecutionHistoryActivity,
                        "Erro ao carregar histórico: ${response.code()}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Log.e("ExecutionHistory", "Exceção ao carregar execuções: ${e.message}", e)
                Toast.makeText(
                    this@ExecutionHistoryActivity,
                    "Erro: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    /**
     * Trata o clique em uma execução
     */
    private fun onExecutionClick(execution: Execution) {
        val intent = Intent(this, ExecutionSummaryActivity::class.java).apply {
            putExtra("execution_id", execution.id)
        }
        startActivity(intent)
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

