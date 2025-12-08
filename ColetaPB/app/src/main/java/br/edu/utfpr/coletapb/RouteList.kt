package br.edu.utfpr.coletapb

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat.startActivity
import androidx.lifecycle.lifecycleScope
import br.edu.utfpr.coletapb.adapter.ItemRoute
import br.edu.utfpr.coletapb.data.AppDatabase
import br.edu.utfpr.coletapb.data.dao.RouteDao
import br.edu.utfpr.coletapb.data.model.RouteEntity
import br.edu.utfpr.coletapb.data.remote.RetrofitClient
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

        // Listener de clique
        listView.setOnItemClickListener { _, _, position, _ ->
            val route = adapter?.getItem(position) ?: return@setOnItemClickListener

            val it = Intent(this, StartRoute::class.java).apply {
                putExtra("route_id", route.id)
                putExtra("route_name", route.name)
                putExtra("route_info", route.description)
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
            // 1. Tenta sincronizar com a API (Network -> DB Local)
            try {
                withContext(Dispatchers.IO) { syncRoutesFromApi() }
            } catch (e: Exception) {
                Log.e("RouteList", "Erro na sincronização", e)
                Toast.makeText(this@RouteList, "Modo Offline: Usando dados locais", Toast.LENGTH_SHORT).show()
            }

            // 2. Lê do Banco Local para exibir (Single Source of Truth)
            val routes = withContext(Dispatchers.IO) { routeDao.getAllRoutes() }

            if (adapter == null) {
                adapter = ItemRoute(this@RouteList, routes.toMutableList())
                listView.adapter = adapter
            } else {
                adapter?.setData(routes)
            }
        }
    }

    private suspend fun syncRoutesFromApi() {
        // Busca rotas da API
        val response = RetrofitClient.getApiService(applicationContext).getRoutes()

        if (response.isSuccessful) {
            val apiRoutes = response.body()?.data?.routes

            if (apiRoutes != null) {
                // Converte DTO da API para Entidade do Room
                val entities = apiRoutes.map { dto ->
                    RouteEntity(
                        id = dto.id,
                        name = dto.name,
                        description = dto.description,
                        collection_type = dto.collection_type,
                        periodicity = dto.periodicity,
                        priority = dto.priority,
                        estimated_time_minutes = dto.estimated_time_minutes ?: 0,
                        distance_km = dto.distance_km ?: 0.0
                    )
                }

                // Atualiza o cache local (estratégia simples: apaga e insere tudo)
                routeDao.clearAllRoutes()
                routeDao.insertAllRoutes(entities)
            }
        } else {
            throw Exception("API Error: ${response.code()}")
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}