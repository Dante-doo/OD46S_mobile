package br.edu.utfpr.coletapb

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import br.edu.utfpr.coletapb.adapter.ItemRoute
import br.edu.utfpr.coletapb.data.AppDatabase
import br.edu.utfpr.coletapb.data.RouteDao
import br.edu.utfpr.coletapb.data.RouteEntity
import br.edu.utfpr.coletapb.data.local.SharedPreferencesHelper
import br.edu.utfpr.coletapb.data.remote.RetrofitClient
import br.edu.utfpr.coletapb.data.repository.RouteRepository
import br.edu.utfpr.coletapb.ui.route.RouteUiState
import br.edu.utfpr.coletapb.ui.route.RouteViewModel
import br.edu.utfpr.coletapb.ui.route.RouteViewModelFactory
import br.edu.utfpr.coletapb.utils.GpsMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RouteList : AppCompatActivity() {

    private lateinit var routeDao: RouteDao
    private lateinit var listView: ListView
    private var adapter: ItemRoute? = null

    // IDs recebidos da tela anterior
    private var truckId: Long = 0L
    private var driverId: Long = 0L // ID do motorista logado

    // --- INICIALIZAÇÃO DO VIEWMODEL ---
    private lateinit var prefsHelper: SharedPreferencesHelper
    private lateinit var viewModel: RouteViewModel
    private lateinit var gpsMonitor: GpsMonitor
    // ------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_route_list)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Inicializa prefsHelper primeiro
        prefsHelper = SharedPreferencesHelper(this)
        gpsMonitor = GpsMonitor(this)
        
        // Verifica GPS ao abrir a tela - usando post para garantir que a Activity está totalmente criada
        window.decorView.post {
            gpsMonitor.checkAndRequestGps(
                onGpsEnabled = {
                    // GPS está ativo, pode continuar
                    Log.d("RouteList", "GPS está habilitado, continuando...")
                },
                onGpsDisabled = {
                    // GPS não ativado, volta para a tela anterior
                    Log.d("RouteList", "GPS não ativado, voltando para tela anterior")
                    finish()
                }
            )
        }
        
        // Inicializa ViewModel após prefsHelper
        viewModel = androidx.lifecycle.ViewModelProvider(
            this,
            RouteViewModelFactory(RouteRepository(prefsHelper))
        )[RouteViewModel::class.java]

        // Verifica se está logado
        val token = prefsHelper.getToken()
        val isTokenValid = prefsHelper.isTokenValid()
        
        if (token == null || !isTokenValid) {
            Log.w("RouteList", "Token não encontrado ou expirado, redirecionando para login")
            val intent = Intent(this, br.edu.utfpr.coletapb.LoginPage::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
            return
        }

        // 1. Obter os IDs passados pela Intent
        truckId = intent.getLongExtra("truck_id", 0L)
        
        // 2. Obter driverId do SharedPreferences (salvo no login)
        driverId = prefsHelper.getDriverId()
        
        if (driverId == 0L) {
            Log.w("RouteList", "Driver ID não encontrado no login. Verifique se o usuário é um motorista.")
            Toast.makeText(this, "Usuário não é um motorista ou dados inválidos.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Validar se recebemos os IDs necessários
        if (truckId == 0L || driverId == 0L) {
            Toast.makeText(this, "ID do Caminhão ou Motorista inválido.", Toast.LENGTH_LONG).show()
            finish() // Fecha a tela se não tiver os dados
            return
        }

        routeDao = AppDatabase.getDatabase(this).routeDao()
        listView = findViewById(R.id.lvRoutes)

        // 2. Configurar o adapter (vazio) e a lista
        adapter = ItemRoute(this@RouteList, mutableListOf())
        listView.adapter = adapter

        // 3. Configurar botões fixos
        val btnVoltar = findViewById<Button>(R.id.btnVoltar)
        val btnAtualizar = findViewById<Button>(R.id.btnAtualizar)
        
        btnVoltar.setOnClickListener {
            // Usa o comportamento padrão do Android para voltar
            finish()
        }
        
        btnAtualizar.setOnClickListener {
            // Atualiza os dados da tela
            viewModel.loadRoutes(driverId, truckId)
            Toast.makeText(this, "Atualizando rotas...", Toast.LENGTH_SHORT).show()
        }

        // 4. Observar as mudanças de estado do ViewModel
        observeRouteState()

        // 5. Pedir ao ViewModel para carregar os dados
        viewModel.loadRoutes(driverId, truckId)

        // 5. Clique da lista (Lógica antiga está correta)
        // 5. Clique da lista
        listView.setOnItemClickListener { _, _, position, _ ->
            val route = adapter?.getItem(position) ?: return@setOnItemClickListener

            // 1. Crie a Intent com um nome diferente de 'it'
            val intent = Intent(this, StartRoute::class.java)

            // 2. Adicione os extras na variável
            intent.putExtra("route_id", route.id)
            intent.putExtra("route_name", route.name)
            intent.putExtra("route_info", route.description ?: "") // Mantendo a correção anterior

            // 3. Inicie a Activity
            startActivity(intent)
        }
        
        // Inicia monitoramento contínuo do GPS
        gpsMonitor.startMonitoring {
            // GPS foi desativado enquanto o app está aberto
            gpsMonitor.showGpsDisabledWarning()
        }
    }
    
    override fun onResume() {
        super.onResume()
        
        // Verifica GPS ao retornar
        if (!gpsMonitor.isGpsEnabled()) {
            gpsMonitor.showGpsDisabledWarning()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        gpsMonitor.stopMonitoring()
    }

    /**
     * Observa o StateFlow do ViewModel e reage às mudanças
     */
    private fun observeRouteState() {
        lifecycleScope.launch {
            viewModel.routeState.collectLatest { state ->
                when (state) {
                    is RouteUiState.Loading -> {
                        Log.d("RouteList", "Carregando rotas...")
                        // TODO: Mostrar um ProgressBar (loading)
                    }
                    is RouteUiState.Success -> {
                        Log.d("RouteList", "Carregadas ${state.routes.size} rotas da API.")
                        // SUCESSO: Atualiza o adapter com as rotas
                        adapter?.setData(state.routes)
                        // Opcional: Salva no banco local para cache/offline
                        saveRoutesToLocalDb(state.routes)
                    }
                    is RouteUiState.Error -> {
                        Log.e("RouteList", "Erro ao carregar rotas: ${state.message}")
                        Toast.makeText(this@RouteList, state.message, Toast.LENGTH_LONG).show()
                        // Em caso de falha, tenta carregar do banco local (cache)
                        loadRoutesFromLocalDb()
                    }
                    is RouteUiState.Idle -> { }
                }
            }
            startActivity(it)
        }
    }

    override fun onStart() {
        super.onStart()
        refreshRoutes()
    }

    private fun refreshRoutes() {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { syncRouteFromAssignment() }
            } catch (e: Exception) {
                Log.e("RouteList", "Erro ao sincronizar", e)
                // Mostra o erro real na tela para facilitar
                Toast.makeText(this@RouteList, "Erro: ${e.message}", Toast.LENGTH_LONG).show()
            }

            val routes = withContext(Dispatchers.IO) { routeDao.getAllRoutes() }

            if (adapter == null) {
                adapter = ItemRoute(this@RouteList, routes.toMutableList())
                listView.adapter = adapter
            } else {
                adapter?.setData(routes)
            }

            if (routes.isEmpty()) {
                Toast.makeText(this@RouteList, "Nenhuma rota encontrada.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun syncRouteFromAssignment() {
        val response = RetrofitClient.getApiService(applicationContext).getMyAssignment()

        if (response.isSuccessful) {
            val assignment = response.body()?.data?.assignment

            if (assignment != null) {
                val dto = assignment.route

                // CORREÇÃO: Usamos o operador elvis (?:) para definir valores padrão
                // caso a API não envie o campo ou envie nulo.
                val entity = RouteEntity(
                    id = dto.id,
                    name = dto.name,
                    description = dto.description ?: "Sem descrição",

                    // Se collection_type vier nulo, define um padrão
                    collection_type = dto.collection_type ?: "NORMAL",

                    periodicity = dto.periodicity,

                    // Se priority vier nulo, define "MEDIUM"
                    priority = dto.priority ?: "MEDIUM",

                    // Se números vierem nulos, define 0
                    estimated_time_minutes = dto.estimated_time_minutes ?: 0,
                    distance_km = dto.distance_km ?: 0.0
                )

                routeDao.clearAllRoutes()
                routeDao.insertAllRoutes(listOf(entity))
            }
        } else {
            Log.w("RouteList", "Erro API: ${response.code()}")
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        // Usa o comportamento padrão do Android para voltar
        finish()
        return true
    }
    
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Usa o comportamento padrão do Android para voltar
        super.onBackPressed()
    }
}