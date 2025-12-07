package br.edu.utfpr.coletapb

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import br.edu.utfpr.coletapb.adapter.AssignmentAdapter
import br.edu.utfpr.coletapb.data.local.SharedPreferencesHelper
import br.edu.utfpr.coletapb.data.remote.RetrofitClient
import br.edu.utfpr.coletapb.ui.assignment.AssignmentViewModel
import br.edu.utfpr.coletapb.ui.assignment.AssignmentUiState
import br.edu.utfpr.coletapb.utils.GpsMonitor
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TruckList : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyView: TextView
    private lateinit var titleTextView: TextView
    private var adapter: AssignmentAdapter? = null

    private val viewModel: AssignmentViewModel by viewModels {
        AssignmentViewModelFactory(application)
    }

    private lateinit var prefsHelper: SharedPreferencesHelper
    private lateinit var gpsMonitor: GpsMonitor
    private var isInitialized = false
    private var isResumingFromOtherActivity = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_truck_list)

        // Inicializa RetrofitClient se ainda não foi inicializado
        RetrofitClient.init(this)
        
        prefsHelper = SharedPreferencesHelper(this)
        gpsMonitor = GpsMonitor(this)
        
        // Verifica GPS ao abrir a tela - usando post para garantir que a Activity está totalmente criada
        window.decorView.post {
            gpsMonitor.checkAndRequestGps(
                onGpsEnabled = {
                    // GPS está ativo, pode continuar
                    Log.d("TruckList", "GPS está habilitado, continuando...")
                },
                onGpsDisabled = {
                    // GPS não ativado, fecha o app
                    Log.d("TruckList", "GPS não ativado, fechando app")
                    finish()
                }
            )
        }
        
        // Verifica se está logado apenas se não for uma recriação da Activity
        // Se savedInstanceState != null, significa que a Activity foi recriada pelo sistema
        // e não deve verificar login novamente (pode ser falso negativo)
        if (savedInstanceState == null) {
            // Primeira vez criando a Activity
            val token = prefsHelper.getToken()
            val isTokenValid = prefsHelper.isTokenValid()
            
            Log.d("TruckList", "onCreate - primeira vez - token existe: ${token != null}, token válido: $isTokenValid")
            
            if (token == null || !isTokenValid) {
                Log.w("TruckList", "Token não encontrado ou expirado, redirecionando para login")
                navigateToLogin()
                return
            }
        } else {
            // Activity foi recriada - verifica se token existe e é válido
            val token = prefsHelper.getToken()
            val isTokenValid = prefsHelper.isTokenValid()
            Log.d("TruckList", "onCreate - recriada - token existe: ${token != null}, token válido: $isTokenValid")
            
            if (token == null || !isTokenValid) {
                Log.w("TruckList", "Token não encontrado ou expirado na recriação, redirecionando para login")
                navigateToLogin()
                return
            }
        }
        
        isInitialized = true

        supportActionBar?.setDisplayHomeAsUpEnabled(true) // Habilita botão voltar
        supportActionBar?.title = "Minhas Escalas"

        listView = findViewById(R.id.lvTrucks)
        titleTextView = findViewById(R.id.tvTruckRegister)
        titleTextView.text = "Minhas Escalas"
        
        // Configura emptyView programaticamente (não é um atributo XML)
        emptyView = findViewById(R.id.tvEmptyMessage)
        listView.emptyView = emptyView

        // Observa mudanças no estado
        observeAssignmentState()

        // Carrega as escalas
        viewModel.loadAssignments()

        // Configura clique na lista
        listView.setOnItemClickListener { _, _, position, _ ->
            val assignment = adapter?.getItem(position) ?: return@setOnItemClickListener

            // Navega diretamente para a tela de início de rota (StartRoute)
            // para que o motorista veja imediatamente a rota a ser executada.
            val intent = Intent(this, StartRoute::class.java).apply {
                putExtra("assignment_id", assignment.id)
                putExtra("route_id", assignment.routeId)
                putExtra("route_name", assignment.routeName)
                // Não temos uma descrição detalhada da rota aqui,
                // então enviamos uma string padrão amigável.
                putExtra(
                    "route_info",
                    assignment.routeName ?: "Rota atribuída ao veículo ${assignment.vehiclePlate ?: ""}"
                )
            }
            startActivity(intent)
        }
        
        // Configura SwipeRefreshLayout para atualizar arrastando para baixo
        val swipeRefreshLayout = findViewById<SwipeRefreshLayout?>(R.id.swipeRefreshLayout)
        swipeRefreshLayout?.setOnRefreshListener {
            viewModel.loadAssignments()
            // Para o refresh quando terminar (será feito no observeAssignmentState)
        }
        
        // Configura botões fixos
        val btnVoltar = findViewById<Button>(R.id.btnVoltar)
        val btnAtualizar = findViewById<Button>(R.id.btnAtualizar)
        
        btnVoltar.setOnClickListener {
            logout() // Na tela principal, voltar = sair
        }
        
        btnAtualizar.setOnClickListener {
            // Atualiza os dados da tela
            viewModel.loadAssignments()
            Toast.makeText(this, "Atualizando escalas...", Toast.LENGTH_SHORT).show()
        }
        
        // Intercepta o botão voltar para fazer logout
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                logout()
            }
        })
        
        // Inicia monitoramento contínuo do GPS
        gpsMonitor.startMonitoring {
            // GPS foi desativado enquanto o app está aberto
            gpsMonitor.showGpsDisabledWarning()
        }
    }
    
    override fun onPause() {
        super.onPause()
        Log.d("TruckList", "onPause")
    }
    
    override fun onResume() {
        super.onResume()
        
        // Verifica GPS ao retornar
        if (!gpsMonitor.isGpsEnabled()) {
            gpsMonitor.showGpsDisabledWarning()
            return
        }
        
        // Se está voltando de outra Activity (RouteList, etc), não verifica login
        // A flag isResumingFromOtherActivity só é setada quando realmente navega para outra Activity
        if (isResumingFromOtherActivity) {
            Log.d("TruckList", "onResume - voltando de outra Activity, não verifica login")
            isResumingFromOtherActivity = false
            return
        }
        
        // Verifica se está logado quando o app volta do background (home, etc)
        // Só redireciona se o token estiver expirado ou não existir
        if (isInitialized) {
            val token = prefsHelper.getToken()
            val isTokenValid = prefsHelper.isTokenValid()
            
            Log.d("TruckList", "onResume - token existe: ${token != null}, token válido: $isTokenValid (voltando do background)")
            
            // Só redireciona se o token não existir OU estiver expirado
            if (token == null || !isTokenValid) {
                Log.w("TruckList", "Token não encontrado ou expirado no onResume, redirecionando para login")
                navigateToLogin()
                return
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        gpsMonitor.stopMonitoring()
        
        // Não limpa token aqui - apenas quando logout explícito
        // O token será mantido para permitir GPS tracking em background
        Log.d("TruckList", "Activity sendo destruída (não limpa token automaticamente)")
    }

    private fun observeAssignmentState() {
        val swipeRefreshLayout = findViewById<SwipeRefreshLayout?>(R.id.swipeRefreshLayout)
        
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                when (state) {
                    is AssignmentUiState.Idle -> {
                        swipeRefreshLayout?.isRefreshing = false
                    }
                    is AssignmentUiState.Loading -> {
                        swipeRefreshLayout?.isRefreshing = true
                        Log.d("TruckList", "Carregando escalas...")
                    }
                    is AssignmentUiState.Success -> {
                        swipeRefreshLayout?.isRefreshing = false
                        Log.d("TruckList", "Carregadas ${state.assignments.size} escalas")
                        
                        if (state.assignments.isEmpty()) {
                            // Não há escalas - o emptyView será mostrado automaticamente
                            adapter = AssignmentAdapter(this@TruckList, mutableListOf()) { assignment ->
                                navigateToAssignmentDetails(assignment)
                            }
                            listView.adapter = adapter
                            titleTextView.text = "Nenhuma Escala Ativa"
                        } else {
                            adapter = AssignmentAdapter(this@TruckList, state.assignments.toMutableList()) { assignment ->
                                navigateToAssignmentDetails(assignment)
                            }
                            listView.adapter = adapter
                            titleTextView.text = "Minhas Escalas (${state.assignments.size})"
                        }
                    }
                    is AssignmentUiState.Error -> {
                        swipeRefreshLayout?.isRefreshing = false
                        Log.e("TruckList", "Erro: ${state.message}")
                        
                        // Se for erro 401, redireciona para login
                        if (state.message.contains("401") || state.message.contains("Unauthorized")) {
                            Toast.makeText(
                                this@TruckList,
                                "Sessão expirada. Faça login novamente.",
                                Toast.LENGTH_LONG
                            ).show()
                            logout()
                        } else {
                            Toast.makeText(
                                this@TruckList,
                                "Erro ao carregar escalas: ${state.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        // Adiciona botão de atualizar
        menu?.add(0, 998, 0, "Atualizar")
            ?.setIcon(android.R.drawable.ic_menu_revert)
            ?.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        
        // Adiciona item de logout no menu
        menu?.add(0, 999, 0, "Sair")?.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            998 -> {
                // Atualizar dados
                viewModel.loadAssignments()
                Toast.makeText(this, "Atualizando escalas...", Toast.LENGTH_SHORT).show()
                true
            }
            999 -> {
                logout()
                true
            }
            android.R.id.home -> {
                logout()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun logout() {
        AlertDialog.Builder(this)
            .setTitle("Sair")
            .setMessage("Deseja realmente sair?")
            .setPositiveButton("Sim") { _, _ ->
                // Limpa token e dados ao fazer logout
                Log.d("TruckList", "Logout solicitado, limpando token e dados")
                prefsHelper.clearAll()
                
                // Para qualquer renovação de token em background
                (application as? br.edu.utfpr.coletapb.ColetaPBApplication)?.clearTokenOnAppClose()
                
                navigateToLogin()
            }
            .setNegativeButton("Não", null)
            .show()
    }
    
    private fun navigateToLogin() {
        val intent = Intent(this, LoginPage::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
    
    private fun navigateToAssignmentDetails(assignment: br.edu.utfpr.coletapb.data.model.Assignment) {
        // Marca que está navegando para outra Activity
        isResumingFromOtherActivity = true
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
    
}

// Factory para criar o ViewModel
class AssignmentViewModelFactory(private val application: android.app.Application) :
    androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AssignmentViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AssignmentViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

