package br.edu.utfpr.coletapb

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.ListView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat.startActivity
import androidx.lifecycle.lifecycleScope
import br.edu.utfpr.coletapb.adapter.ItemRoute
import br.edu.utfpr.coletapb.data.AppDatabase
import br.edu.utfpr.coletapb.data.RouteDao
import br.edu.utfpr.coletapb.data.RouteEntity
import br.edu.utfpr.coletapb.data.remote.RetrofitClient
import br.edu.utfpr.coletapb.data.repository.RouteRepository
import br.edu.utfpr.coletapb.ui.route.RouteUiState
import br.edu.utfpr.coletapb.ui.route.RouteViewModel
import br.edu.utfpr.coletapb.ui.route.RouteViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
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
    private val viewModel: RouteViewModel by viewModel {
        RouteViewModelFactory(
            RouteRepository(RetrofitClient.apiService)
        )
    }
    // ------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_route_list)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // 1. Obter os IDs passados pela Intent
        truckId = intent.getLongExtra("truck_id", 0L)

        // --- ATENÇÃO ---
        // Precisa add a Intent na TruckList.kt.
        // Exemplo:
        // driverId = intent.getLongExtra("driver_id", 0L)

        if (driverId == 0L) {
            Log.w("RouteList", "Driver ID não recebido, usando '1' como teste.")
            driverId = 1L // << SUBSTITUIR QUANDO TIVER O ID REAL
        }
        // -----------------------------------------------------------------

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

        // 3. Observar as mudanças de estado do ViewModel
        observeRouteState()

        // 4. Pedir ao ViewModel para carregar os dados
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
        }
    }

    /**
     * Salva as rotas buscadas no banco de dados local (Room).
     */
    private fun saveRoutesToLocalDb(routes: List<RouteEntity>) {
        lifecycleScope.launch(Dispatchers.IO) {
            routeDao.clearAllRoutes() // Limpa rotas antigas
            routeDao.insertAllRoutes(routes) // Insere rotas novas
        }
    }

    /**
     * Carrega as rotas do banco de dados local (útil para offline ou falha de API).
     */
    private fun loadRoutesFromLocalDb() {
        lifecycleScope.launch {
            // Busca todas as rotas salvas
            val routes = withContext(Dispatchers.IO) { routeDao.getAllRoutes() }
            Log.d("RouteList", "Carregadas ${routes.size} rotas do banco local (cache).")
            adapter?.setData(routes)
            if (routes.isEmpty()) {
                Toast.makeText(this@RouteList, "Falha na conexão e sem dados em cache.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}