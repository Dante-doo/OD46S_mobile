package br.edu.utfpr.coletapb

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_route_list)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Minha Rota"

        routeDao = AppDatabase.getDatabase(this).routeDao()
        listView = findViewById(R.id.lvRoutes)

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
        finish()
        return true
    }
}