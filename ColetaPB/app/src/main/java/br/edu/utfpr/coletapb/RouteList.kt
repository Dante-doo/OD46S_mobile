package br.edu.utfpr.coletapb

import android.os.Bundle
import android.util.Log
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import br.edu.utfpr.coletapb.adapter.ItemRoute
import br.edu.utfpr.coletapb.data.AppDatabase
import br.edu.utfpr.coletapb.data.RouteDao
import br.edu.utfpr.coletapb.data.RouteEntity
import kotlinx.coroutines.launch

class RouteList : AppCompatActivity() {

    private lateinit var routeDao: RouteDao
    private lateinit var listView: ListView
    private var adapter: ItemRoute? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_route_list)

        // Inicializa o DAO e a ListView
        routeDao = AppDatabase.getDatabase(this).routeDao()
        listView = findViewById(R.id.lvRoutes)

        // Sincroniza os dados (simula API e salva no banco)
        lifecycleScope.launch {
            syncRoutes()
        }
    }

    override fun onStart() {
        super.onStart()
        // Carrega as rotas do banco de dados sempre que a tela se torna visível
        loadRoutesFromDatabase()
    }

    /**
     * Simula a busca de dados da API e salva no banco de dados local.
     */
    private suspend fun syncRoutes() {
        try {
            // Dados de exemplo que viriam da API
            val routesFromApi = listOf(
                RouteEntity(101, "Rota 101", "Centro • Turno manhã", "COMMERCIAL", "0 8 * * 1,3,5", "HIGH", 120, 15.5),
                RouteEntity(202, "Rota 202", "Bairro Norte • Turno tarde", "RESIDENTIAL", "0 14 * * 2,4,6", "MEDIUM", 90, 12.0),
                RouteEntity(303, "Rota 303", "Bairro Sul • Turno noite", "RESIDENTIAL", "0 20 * * 1,3,5", "MEDIUM", 100, 14.2),
                RouteEntity(404, "Rota 404", "Zona Leste • Turno manhã", "HOSPITAL", "0 9 * * *", "URGENT", 60, 8.0),
                RouteEntity(505, "Rota 505", "Zona Oeste • Turno tarde", "SELECTIVE", "0 15 * * 5", "LOW", 110, 22.0)
            )

            // Limpa os dados antigos e insere os novos
            routeDao.clearAllRoutes()
            routeDao.insertAllRoutes(routesFromApi)

            Log.d("RouteList", "Rotas sincronizadas com sucesso no banco de dados.")

        } catch (e: Exception) {
            Log.e("RouteList", "Falha ao sincronizar rotas", e)
        }
    }

    /**
     * Carrega as rotas do banco de dados local (Room) e atualiza a ListView.
     */
    private fun loadRoutesFromDatabase() {
        lifecycleScope.launch {
            // Busca as rotas do banco
            val routes = routeDao.getAllRoutes()

            // Cria e define o adapter com a lista de rotas
            adapter = ItemRoute(this@RouteList, routes)
            listView.adapter = adapter
        }
    }
}