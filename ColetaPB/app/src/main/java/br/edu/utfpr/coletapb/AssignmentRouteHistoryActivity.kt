package br.edu.utfpr.coletapb

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import br.edu.utfpr.coletapb.adapter.ExecutionHistoryAdapter
import br.edu.utfpr.coletapb.data.local.SharedPreferencesHelper
import br.edu.utfpr.coletapb.data.model.Execution
import br.edu.utfpr.coletapb.data.repository.ExecutionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * Tela que exibe o histórico de execuções de uma rota específica (assignment)
 */
class AssignmentRouteHistoryActivity : AppCompatActivity() {
    
    private lateinit var prefsHelper: SharedPreferencesHelper
    private lateinit var executionRepository: ExecutionRepository
    private lateinit var listView: ListView
    
    private var assignmentId: Long = 0L
    private var routeId: Long = 0L
    private var routeName: String = ""
    
    private var adapter: ExecutionHistoryAdapter? = null
    private var executions: MutableList<Execution> = mutableListOf()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_execution_history)
        
        // Remove ActionBar padrão
        supportActionBar?.hide()
        
        // Recebe dados do intent
        assignmentId = intent.getLongExtra("assignment_id", 0L)
        routeId = intent.getLongExtra("route_id", 0L)
        routeName = intent.getStringExtra("route_name") ?: "Rota"
        
        if (assignmentId == 0L) {
            Toast.makeText(this, "Dados inválidos", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        prefsHelper = SharedPreferencesHelper(this)
        executionRepository = ExecutionRepository(prefsHelper)
        
        // Configura MaterialToolbar
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.topAppBar)
        toolbar.title = "Histórico"
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        
        // Inicializa views
        listView = findViewById(R.id.lvExecutions)
        
        // Configura adapter
        adapter = ExecutionHistoryAdapter(this, executions) { execution ->
            onExecutionClick(execution)
        }
        listView.adapter = adapter
        
        // Carrega execuções
        loadExecutions()
    }
    
    /**
     * Carrega a lista de execuções desta rota
     */
    private fun loadExecutions() {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    // Busca todas as execuções deste assignment (sem filtrar por status)
                    executionRepository.getExecutionsByAssignment(assignmentId, null)
                }
                
                result.fold(
                    onSuccess = { execs ->
                        executions.clear()
                        // Ordena por data de início (mais recente primeiro)
                        executions.addAll(execs.sortedByDescending { 
                            it.startTime ?: ""
                        })
                        
                        adapter?.notifyDataSetChanged()
                        
                        if (executions.isEmpty()) {
                            Toast.makeText(
                                this@AssignmentRouteHistoryActivity,
                                "Nenhuma execução encontrada para esta rota",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Log.d("AssignmentRouteHistory", "Carregadas ${executions.size} execuções")
                        }
                    },
                    onFailure = { error ->
                        Log.e("AssignmentRouteHistory", "Erro ao carregar execuções: ${error.message}", error)
                        Toast.makeText(
                            this@AssignmentRouteHistoryActivity,
                            "Erro ao carregar histórico: ${error.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            } catch (e: Exception) {
                Log.e("AssignmentRouteHistory", "Exceção ao carregar execuções: ${e.message}", e)
                Toast.makeText(
                    this@AssignmentRouteHistoryActivity,
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

