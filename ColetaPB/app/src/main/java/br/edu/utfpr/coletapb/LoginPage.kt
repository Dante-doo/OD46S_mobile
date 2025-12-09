package br.edu.utfpr.coletapb

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import br.edu.utfpr.coletapb.config.ApiConfig
import br.edu.utfpr.coletapb.data.AppDatabase
import br.edu.utfpr.coletapb.data.local.SharedPreferencesHelper
import br.edu.utfpr.coletapb.data.model.LoginRequest
import br.edu.utfpr.coletapb.data.remote.RetrofitClient
import br.edu.utfpr.coletapb.data.repository.ExecutionRepository
import br.edu.utfpr.coletapb.data.repository.SyncRepository
import br.edu.utfpr.coletapb.utils.GpsMonitor
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException

class LoginPage : AppCompatActivity() {
    
    private lateinit var prefsHelper: SharedPreferencesHelper
    private lateinit var gpsMonitor: GpsMonitor
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login_page)
        
        // Inicializa RetrofitClient com contexto
        RetrofitClient.init(this)
        
        prefsHelper = SharedPreferencesHelper(this)
        gpsMonitor = GpsMonitor(this)
        
        // Verifica GPS ao abrir o app - usando post para garantir que a Activity está totalmente criada
        window.decorView.post {
            gpsMonitor.checkAndRequestGps(
                onGpsEnabled = {
                    // GPS está ativo, pode continuar
                    Log.d("LoginPage", "GPS está habilitado, continuando...")
                },
                onGpsDisabled = {
                    // Usuário escolheu sair, app será fechado
                    Log.d("LoginPage", "Usuário escolheu sair, fechando app")
                    finish()
                }
            )
        }
        
        // Verifica se já está logado e redireciona se necessário
        val token = prefsHelper.getToken()
        val isTokenValid = prefsHelper.isTokenValid()
        if (token != null && isTokenValid) {
            // Já está logado e token é válido - redireciona para a tela apropriada
            Log.d("LoginPage", "Token válido encontrado, redirecionando...")
            checkCurrentExecutionAndNavigate()
            return
        }
        
        // Não redireciona automaticamente - sempre mostra a tela de login
        // O usuário pode fazer logout se necessário

        val etEmailOrCpf = findViewById<TextInputEditText>(R.id.etCPF)
        val etPassword = findViewById<TextInputEditText>(R.id.etPassword)
        val btLogin = findViewById<Button>(R.id.btLogin)

        btLogin.setOnClickListener {
            val emailOrCpf = etEmailOrCpf.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (emailOrCpf.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Por favor, preencha todos os campos.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Desabilita o botão durante o login
            btLogin.isEnabled = false
            btLogin.text = "Entrando..."

            lifecycleScope.launch {
                try {
                    val isEmail = Patterns.EMAIL_ADDRESS.matcher(emailOrCpf).matches()
                    val loginRequest = if (isEmail) {
                        LoginRequest(email = emailOrCpf, password = password)
                    } else {
                        LoginRequest(cpf = emailOrCpf, password = password)
                    }

                    val response = RetrofitClient.apiService.login(loginRequest)
                    
                    if (response.isSuccessful && response.body() != null) {
                        val loginResponse = response.body()!!
                        
                        // Salva token e informações do usuário
                        prefsHelper.saveToken(loginResponse.token)
                        prefsHelper.saveUserInfo(
                            email = loginResponse.email,
                            name = loginResponse.name,
                            type = loginResponse.type,
                            userId = loginResponse.userId,
                            driverId = loginResponse.driverId,
                            adminId = loginResponse.adminId
                        )
                        
                        Log.d("LoginPage", "Login bem-sucedido: ${loginResponse.email} (${loginResponse.type})")
                        Log.d("LoginPage", "UserId: ${loginResponse.userId}, DriverId: ${loginResponse.driverId}, AdminId: ${loginResponse.adminId}")
                        
                        // Verifica GPS antes de navegar
                        if (gpsMonitor.isGpsEnabled()) {
                            Toast.makeText(this@LoginPage, "Bem-vindo, ${loginResponse.name}!", Toast.LENGTH_SHORT).show()
                            checkCurrentExecutionAndNavigate()
                        } else {
                            gpsMonitor.checkAndRequestGps(
                                onGpsEnabled = {
                                    Toast.makeText(this@LoginPage, "Bem-vindo, ${loginResponse.name}!", Toast.LENGTH_SHORT).show()
                                    checkCurrentExecutionAndNavigate()
                                },
                                onGpsDisabled = {
                                    // GPS não ativado, não navega
                                }
                            )
                        }
                    } else {
                        val errorBody = response.errorBody()?.string() ?: "Erro desconhecido"
                        Log.e("LoginPage", "Erro no login: ${response.code()} - $errorBody")
                        Toast.makeText(this@LoginPage, "Credenciais inválidas. Verifique e tente novamente.", Toast.LENGTH_LONG).show()
                    }
                } catch (e: HttpException) {
                    Log.e("LoginPage", "Erro HTTP: ${e.code()} - ${e.message()}")
                    Toast.makeText(this@LoginPage, "Erro ao conectar com o servidor. Verifique sua conexão.", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Log.e("LoginPage", "Exceção: ${e.message}", e)
                    Toast.makeText(this@LoginPage, "Erro: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    // Reabilita o botão
                    btLogin.isEnabled = true
                    btLogin.text = "Entrar"
                }
            }
        }

        btLogin.setOnClickListener {
            val emailOrCpf = etEmailOrCpf.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (emailOrCpf.isNotEmpty() && password.isNotEmpty()) {
                viewModel.login(emailOrCpf, password)
            } else {
                Toast.makeText(this, "Preencha todos os campos", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * Verifica se há uma execução em andamento e navega para a tela apropriada
     * Verifica primeiro no banco local, depois no backend
     */
    private fun checkCurrentExecutionAndNavigate() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Verifica primeiro no backend se está online
                val executionRepo = ExecutionRepository(prefsHelper)
                val syncRepo = SyncRepository(this@LoginPage, prefsHelper)
                val isOnline = try {
                    syncRepo.isOnline()
                } catch (e: Exception) {
                    false
                }
                
                // Se está online, verifica no backend primeiro
                if (isOnline) {
                    Log.d("LoginPage", "Online: verificando execução no backend primeiro")
                    val result = executionRepo.getMyCurrentExecution()
                    
                    result.fold(
                        onSuccess = { execution ->
                            if (execution != null && execution.status == "IN_PROGRESS") {
                                // Há uma execução em andamento no backend - vai direto para a tela de execução
                                Log.d("LoginPage", "Execução em andamento encontrada no backend: ${execution.id}")
                                withContext(Dispatchers.Main) {
                                    val intent = Intent(this@LoginPage, StartRoute::class.java).apply {
                                        putExtra("execution_id", execution.id)
                                        putExtra("route_id", execution.routeId)
                                        putExtra("route_name", execution.routeName ?: "Rota")
                                    }
                                    startActivity(intent)
                                    finish()
                                }
                                return@launch
                            } else {
                                // Backend não tem execução - verifica se há execução local "fantasma" para limpar
                                val db = AppDatabase.getDatabase(this@LoginPage)
                                val executionDao = db.executionDao()
                                val localExecution = executionDao.getCurrentExecution()
                                
                                if (localExecution != null && localExecution.status == "IN_PROGRESS") {
                                    // Execução local "fantasma" - marca como COMPLETED
                                    Log.w("LoginPage", "Execução local IN_PROGRESS encontrada mas backend não tem execução. Marcando como COMPLETED.")
                                    val fixedExec = localExecution.copy(
                                        status = "COMPLETED",
                                        endTimestamp = localExecution.endTimestamp ?: System.currentTimeMillis()
                                    )
                                    executionDao.update(fixedExec)
                                }
                                
                                // Não há execução em andamento - vai para lista de assignments
                                withContext(Dispatchers.Main) {
                                    navigateToAssignmentList()
                                }
                                return@launch
                            }
                        },
                        onFailure = { error ->
                            Log.e("LoginPage", "Erro ao verificar execução atual no backend: ${error.message}")
                            
                            // Se o token foi limpo automaticamente (renovação falhou), não navega
                            // Apenas deixa a tela de login visível (já está na tela de login)
                            if (prefsHelper.getToken() == null) {
                                Log.d("LoginPage", "Token foi limpo automaticamente durante verificação. Mantendo tela de login.")
                                // Não faz nada - já está na tela de login
                            } else {
                                // Em caso de outro erro, vai para lista de assignments
                                withContext(Dispatchers.Main) {
                                    navigateToAssignmentList()
                                }
                            }
                            return@launch
                        }
                    )
                }
                
                // Se está offline, verifica no banco local
                Log.d("LoginPage", "Offline: verificando execução local")
                val db = AppDatabase.getDatabase(this@LoginPage)
                val executionDao = db.executionDao()
                val localExecution = executionDao.getCurrentExecution()
                
                if (localExecution != null && localExecution.status == "IN_PROGRESS") {
                    // Há execução local em andamento e estamos offline - navega para StartRoute
                    Log.d("LoginPage", "Execução local em andamento encontrada (offline): routeId=${localExecution.routeId}, execLocalId=${localExecution.localId}")
                    withContext(Dispatchers.Main) {
                        val intent = Intent(this@LoginPage, StartRoute::class.java).apply {
                            putExtra("route_id", localExecution.routeId)
                            putExtra("route_name", "Rota em andamento")
                            // assignmentId pode ser 0L, mas StartRoute vai restaurar a execução local
                            putExtra("assignment_id", 0L)
                        }
                        startActivity(intent)
                        finish()
                    }
                    return@launch
                }
                
                // Não encontrou execução local - vai para lista de assignments
                withContext(Dispatchers.Main) {
                    navigateToAssignmentList()
                }
            } catch (e: Exception) {
                Log.e("LoginPage", "Exceção ao verificar execução: ${e.message}", e)
                
                // Se o token foi limpo automaticamente (renovação falhou), não navega
                // Apenas deixa a tela de login visível (já está na tela de login)
                if (prefsHelper.getToken() == null) {
                    Log.d("LoginPage", "Token foi limpo automaticamente durante verificação. Mantendo tela de login.")
                    // Não faz nada - já está na tela de login
                } else {
                    // Em caso de outro erro, vai para lista de assignments
                    withContext(Dispatchers.Main) {
                        navigateToAssignmentList()
                    }
                }
            }
        }
    }
    
    /**
     * Navega para a lista de assignments (rotas disponíveis)
     */
    private fun navigateToAssignmentList() {
        val intent = Intent(this, AssignmentListActivity::class.java)
        startActivity(intent)
        finish()
    }
}