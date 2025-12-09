package br.edu.utfpr.coletapb

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import br.edu.utfpr.coletapb.adapter.AssignmentAdapter
import br.edu.utfpr.coletapb.data.local.SharedPreferencesHelper
import br.edu.utfpr.coletapb.data.model.Assignment
import br.edu.utfpr.coletapb.data.repository.AssignmentRepository
import br.edu.utfpr.coletapb.data.repository.ExecutionRepository
import br.edu.utfpr.coletapb.utils.GpsMonitor
import kotlinx.coroutines.launch

/**
 * Tela que lista as escalas/assignments disponíveis para o usuário
 * - DRIVER: vê suas escalas
 * - ADMIN: vê todas as escalas ativas
 */
class AssignmentListActivity : AppCompatActivity() {
    
    private lateinit var prefsHelper: SharedPreferencesHelper
    private lateinit var assignmentRepository: AssignmentRepository
    private lateinit var executionRepository: ExecutionRepository
    private lateinit var gpsMonitor: GpsMonitor
    
    private lateinit var listView: ListView
    private lateinit var tvTitle: TextView
    private lateinit var btnRefresh: Button
    private lateinit var btnLogout: ImageButton
    private lateinit var llEmptyState: LinearLayout
    private var adapter: AssignmentAdapter? = null
    private var assignments: MutableList<Assignment> = mutableListOf()
    private var isLoadingAssignments = false
    private var isFirstLoad = true
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_assignment_list)
        
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Rotas"
        
        prefsHelper = SharedPreferencesHelper(this)
        assignmentRepository = AssignmentRepository(prefsHelper)
        executionRepository = ExecutionRepository(prefsHelper)
        gpsMonitor = GpsMonitor(this)
        
        // Verifica GPS
        window.decorView.post {
            gpsMonitor.checkAndRequestGps(
                onGpsEnabled = {
                    Log.d("AssignmentList", "GPS está habilitado")
                },
                onGpsDisabled = {
                    finish()
                }
            )
        }
        
        // Verifica se está logado e se o token é válido (não expirado)
        val token = prefsHelper.getToken()
        val isTokenValid = prefsHelper.isTokenValid()
        if (token == null || !isTokenValid) {
            Log.w("AssignmentList", "Token não encontrado ou expirado, redirecionando para login")
            val intent = Intent(this, LoginPage::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
            return
        }
        
        // Inicializa views
        listView = findViewById(R.id.lvAssignments)
        tvTitle = findViewById(R.id.tvTitle)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnLogout = findViewById(R.id.btnLogout)
        llEmptyState = findViewById(R.id.llEmptyState)
        
        // Configura título baseado no tipo de usuário
        val userType = prefsHelper.getUserType()
        val userName = prefsHelper.getUserName()
        tvTitle.text = if (userType == "ADMIN") {
            "Todas as Rotas"
        } else {
            "Minhas Rotas"
        }
        
        // Configura adapter
        adapter = AssignmentAdapter(this, assignments) { assignment ->
            onAssignmentClick(assignment)
        }
        listView.adapter = adapter
        
        // Botão refresh
        btnRefresh.setOnClickListener {
            loadAssignments()
        }
        
        // Botão logout
        btnLogout.setOnClickListener {
            logout()
        }
        
        // Carrega assignments
        loadAssignments()
        
        // Inicia monitoramento GPS
        gpsMonitor.startMonitoring {
            gpsMonitor.showGpsDisabledWarning()
        }
    }
    
    override fun onResume() {
        super.onResume()
        if (!gpsMonitor.isGpsEnabled()) {
            gpsMonitor.showGpsDisabledWarning()
        }
        // Recarrega assignments ao retornar (pode ter mudado)
        // Mas não recarrega se acabou de carregar no onCreate (primeira vez)
        if (!isFirstLoad) {
            loadAssignments()
        } else {
            isFirstLoad = false
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        gpsMonitor.stopMonitoring()
    }
    
    /**
     * Carrega a lista de assignments
     */
    private fun loadAssignments() {
        // Evita chamadas simultâneas
        if (isLoadingAssignments) {
            Log.d("AssignmentList", "Já está carregando assignments, ignorando chamada duplicada")
            return
        }
        
        isLoadingAssignments = true
        lifecycleScope.launch {
            try {
                val userType = prefsHelper.getUserType()
                
                val result = if (userType == "ADMIN") {
                    // ADMIN: busca todas as escalas ativas
                    assignmentRepository.getAllAssignments(status = "ACTIVE")
                } else {
                    // DRIVER: busca suas escalas
                    assignmentRepository.getMyAssignments()
                }
                
                result.fold(
                    onSuccess = { assignmentList ->
                        // Verifica qual assignment tem execução em andamento ou concluída hoje
                        val currentExecutionResult = executionRepository.getMyCurrentExecution()
                        val currentExecution = currentExecutionResult.getOrNull()
                        
                        assignments.clear()
                        
                        // Atualiza isCurrent baseado na execução real
                        // Só marca como atual se a execução estiver realmente em andamento (não cancelada ou concluída)
                        val updatedList = assignmentList.map { assignment ->
                            if (currentExecution != null && 
                                currentExecution.status == "IN_PROGRESS" &&
                                currentExecution.assignmentId == assignment.id) {
                                // Esta assignment tem execução em andamento
                                assignment.copy(isCurrent = true)
                            } else if (assignment.isCurrent && (currentExecution == null || currentExecution.status != "IN_PROGRESS")) {
                                // Remove marcação de "atual" se não há execução em andamento
                                assignment.copy(isCurrent = false)
                            } else {
                                // Verifica se há execução concluída hoje para este assignment
                                val completedResult = executionRepository.getExecutionsByAssignment(assignment.id, "COMPLETED")
                                val hasCompletedToday = completedResult.getOrNull()?.any { exec ->
                                    exec.startTime?.let { startTimeStr ->
                                        try {
                                            val datePart = startTimeStr.substringBefore("T")
                                            java.time.LocalDate.parse(datePart) == java.time.LocalDate.now()
                                        } catch (e: Exception) {
                                            false
                                        }
                                    } ?: false
                                } ?: false
                                
                                // Não marca como "current" se for concluída, apenas se estiver em andamento
                                assignment.copy(isCurrent = false)
                            }
                        }
                        
                        assignments.addAll(updatedList)
                        adapter?.notifyDataSetChanged()
                        
                        // Atualiza visibilidade do empty state
                        if (assignments.isEmpty()) {
                            llEmptyState.visibility = android.view.View.VISIBLE
                            listView.visibility = android.view.View.GONE
                        } else {
                            llEmptyState.visibility = android.view.View.GONE
                            listView.visibility = android.view.View.VISIBLE
                            Log.d("AssignmentList", "Carregadas ${assignments.size} rotas")
                            val activeCount = assignments.count { it.isCurrent }
                            if (activeCount > 0) {
                                Log.d("AssignmentList", "$activeCount rota(s) em andamento")
                            }
                        }
                    },
                    onFailure = { error ->
                        Log.e("AssignmentList", "Erro ao carregar assignments: ${error.message}", error)
                        
                        // Se o token foi limpo automaticamente (renovação falhou), não mostra erro ao usuário
                        // Apenas redireciona silenciosamente para o login
                        if (prefsHelper.getToken() == null) {
                            Log.d("AssignmentList", "Token foi limpo automaticamente. Redirecionando para login sem mostrar erro.")
                            val intent = Intent(this@AssignmentListActivity, LoginPage::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            }
                            startActivity(intent)
                            finish()
                        } else {
                            // Token ainda existe - verifica tipo de erro e mostra mensagem apropriada
                            val errorMessage = error.message ?: "Erro desconhecido"
                            val isConnectionError = isConnectionError(error)
                            
                            if (isConnectionError) {
                                // Erro de conexão - mostra mensagem clara ao usuário
                                AlertDialog.Builder(this@AssignmentListActivity)
                                    .setTitle("Erro de Conexão")
                                    .setMessage("Não foi possível conectar ao servidor.\n\n" +
                                            "Por favor, verifique:\n" +
                                            "• Sua conexão com a internet\n" +
                                            "• Se o servidor está em execução\n" +
                                            "• Suas configurações de rede\n\n" +
                                            "Tente novamente quando a conexão estiver disponível.")
                                    .setPositiveButton("OK", null)
                                    .setCancelable(true)
                                    .show()
                            } else {
                                // Outro tipo de erro - mostra mensagem genérica
                                Toast.makeText(
                                    this@AssignmentListActivity,
                                    "Erro ao carregar rotas: $errorMessage",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e("AssignmentList", "Exceção ao carregar assignments: ${e.message}", e)
                
                // Se o token foi limpo automaticamente, não mostra erro ao usuário
                if (prefsHelper.getToken() == null) {
                    Log.d("AssignmentList", "Token foi limpo automaticamente. Redirecionando para login sem mostrar erro.")
                    val intent = Intent(this@AssignmentListActivity, LoginPage::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                    finish()
                } else {
                    // Token ainda existe - verifica tipo de erro e mostra mensagem apropriada
                    val errorMessage = e.message ?: "Erro desconhecido"
                    val isConnectionError = isConnectionError(e)
                    
                    if (isConnectionError) {
                        // Erro de conexão - mostra mensagem clara ao usuário
                        AlertDialog.Builder(this@AssignmentListActivity)
                            .setTitle("Erro de Conexão")
                            .setMessage("Não foi possível conectar ao servidor.\n\n" +
                                    "Por favor, verifique:\n" +
                                    "• Sua conexão com a internet\n" +
                                    "• Se o servidor está em execução\n" +
                                    "• Suas configurações de rede\n\n" +
                                    "Tente novamente quando a conexão estiver disponível.")
                            .setPositiveButton("OK", null)
                            .setCancelable(true)
                            .show()
                    } else {
                        // Outro tipo de erro - mostra mensagem genérica
                        Toast.makeText(
                            this@AssignmentListActivity,
                            "Erro: $errorMessage",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } finally {
                isLoadingAssignments = false
            }
        }
    }
    
    /**
     * Verifica se um erro é relacionado a problemas de conexão com o servidor
     */
    private fun isConnectionError(error: Throwable): Boolean {
        val errorMessage = error.message ?: ""
        val errorClass = error.javaClass.simpleName
        
        // Verifica se é uma exceção de conexão conhecida
        return error is java.net.ConnectException ||
               error is java.net.SocketTimeoutException ||
               error is java.net.UnknownHostException ||
               error is java.io.IOException ||
               errorMessage.contains("Failed to connect", ignoreCase = true) ||
               errorMessage.contains("Connection refused", ignoreCase = true) ||
               errorMessage.contains("timeout", ignoreCase = true) ||
               errorMessage.contains("Unable to resolve host", ignoreCase = true) ||
               errorMessage.contains("Network is unreachable", ignoreCase = true) ||
               errorMessage.contains("No route to host", ignoreCase = true) ||
               errorClass.contains("ConnectException", ignoreCase = true) ||
               errorClass.contains("SocketTimeoutException", ignoreCase = true) ||
               errorClass.contains("UnknownHostException", ignoreCase = true)
    }
    
    /**
     * Trata o clique em um assignment
     */
    private fun onAssignmentClick(assignment: Assignment) {
        lifecycleScope.launch {
            try {
                // Verifica se já existe uma execução em andamento para este assignment
                val executionResult = executionRepository.getMyCurrentExecution()
                
                executionResult.fold(
                    onSuccess = { currentExecution ->
                        if (currentExecution != null && 
                            currentExecution.status == "IN_PROGRESS" &&
                            currentExecution.assignmentId == assignment.id) {
                            // Já existe execução em andamento para este assignment - continua
                            navigateToExecution(currentExecution)
                        } else {
                            // Não há execução ou é diferente - vai para detalhes
                            navigateToAssignmentDetails(assignment)
                        }
                    },
                    onFailure = { error ->
                        Log.e("AssignmentList", "Erro ao verificar execução: ${error.message}")
                        // Em caso de erro, vai para detalhes mesmo assim
                        navigateToAssignmentDetails(assignment)
                    }
                )
            } catch (e: Exception) {
                Log.e("AssignmentList", "Exceção ao verificar execução: ${e.message}", e)
                navigateToAssignmentDetails(assignment)
            }
        }
    }
    
    /**
     * Navega para a tela de detalhes do assignment
     */
    private fun navigateToAssignmentDetails(assignment: Assignment) {
        val intent = Intent(this, AssignmentDetailsActivity::class.java).apply {
            putExtra("assignment_id", assignment.id)
            putExtra("route_id", assignment.routeId)
            putExtra("route_name", assignment.routeName ?: "Rota")
            putExtra("driver_id", assignment.driverId)
            putExtra("driver_name", assignment.driverName)
            putExtra("vehicle_id", assignment.vehicleId)
            putExtra("vehicle_plate", assignment.vehiclePlate)
        }
        startActivity(intent)
    }
    
    /**
     * Navega para a tela de execução (StartRoute)
     */
    private fun navigateToExecution(execution: br.edu.utfpr.coletapb.data.model.Execution) {
        val intent = Intent(this, StartRoute::class.java).apply {
            putExtra("execution_id", execution.id)
            putExtra("route_id", execution.routeId)
            putExtra("route_name", execution.routeName ?: "Rota")
        }
        startActivity(intent)
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
    
    /**
     * Realiza logout do usuário
     */
    private fun logout() {
        AlertDialog.Builder(this)
            .setTitle("Sair")
            .setMessage("Deseja realmente sair?")
            .setPositiveButton("Sim") { _, _ ->
                // Limpa token e dados ao fazer logout
                Log.d("AssignmentList", "Logout solicitado, limpando token e dados")
                prefsHelper.clearAll()
                
                // Para qualquer renovação de token em background
                (application as? ColetaPBApplication)?.clearTokenOnAppClose()
                
                // Navega para tela de login e finaliza esta activity
                val intent = Intent(this, LoginPage::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
            }
            .setNegativeButton("Não", null)
            .show()
    }
}

