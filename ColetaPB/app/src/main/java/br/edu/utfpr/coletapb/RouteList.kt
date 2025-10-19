package br.edu.utfpr.coletapb

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import br.edu.utfpr.coletapb.adapter.ItemRoute
import br.edu.utfpr.coletapb.data.AppDatabase
import br.edu.utfpr.coletapb.data.RouteDao
import br.edu.utfpr.coletapb.data.RouteEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

class RouteList : AppCompatActivity() {

    private lateinit var routeDao: RouteDao
    private lateinit var listView: ListView
    private var adapter: ItemRoute? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_route_list)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        routeDao = AppDatabase.getDatabase(this).routeDao()
        listView = findViewById(R.id.lvRoutes)
    }

    override fun onStart() {
        super.onStart()

        lifecycleScope.launch {
            // 1) sincroniza (clear + insert) em IO
            try {
                withContext(Dispatchers.IO) { syncRoutesInternal() }
                Log.d("RouteList", "Sincronização concluída.")
            } catch (e: Exception) {
                Log.e("RouteList", "Falha ao sincronizar rotas", e)
            }

            // 2) lê do banco depois que terminou a sync
            val routes = withContext(Dispatchers.IO) { routeDao.getAllRoutes() }
            Log.d("RouteList", "Carregadas ${routes.size} rotas do banco.")

            // primeira carga (ex.: no onStart, depois de ler do Room)
            if (adapter == null) {
                adapter = ItemRoute(this@RouteList, routes.toMutableList())
                listView.adapter = adapter
            } else {
                adapter?.setData(routes)
            }
        }
        // dentro da sua RouteList, depois de carregar as rotas e setar o adapter
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

    private suspend fun syncRoutesInternal() {
        val routesFromApi = listOf(
            RouteEntity(101, "Rota 101", "Centro • Turno manhã", "COMMERCIAL", "0 8 * * 1,3,5", "HIGH", 120, 15.5),
            RouteEntity(202, "Rota 202", "Bairro Norte • Turno tarde", "RESIDENTIAL", "0 14 * * 2,4,6", "MEDIUM", 90, 12.0),
            RouteEntity(303, "Rota 303", "Bairro Sul • Turno noite", "RESIDENTIAL", "0 20 * * 1,3,5", "MEDIUM", 100, 14.2),
            RouteEntity(404, "Rota 404", "Zona Leste • Turno manhã", "HOSPITAL", "0 9 * * *", "URGENT", 60, 8.0),
            RouteEntity(505, "Rota 505", "Zona Oeste • Turno tarde", "SELECTIVE", "0 15 * * 5", "LOW", 110, 22.0)
        )
        routeDao.clearAllRoutes()
        routeDao.insertAllRoutes(routesFromApi)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
