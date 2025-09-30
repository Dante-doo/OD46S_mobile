package br.edu.utfpr.coletapb

import android.content.Intent
import android.database.MatrixCursor
import android.os.Bundle
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import br.edu.utfpr.coletapb.adapter.ItemRoute

class RouteList : AppCompatActivity() {

    private lateinit var routes: MatrixCursor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_route_list)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // (Opcional) use extras p/ exibir no título
        val truckName = intent.getStringExtra("truck_name") ?: ""
        if (truckName.isNotBlank()) {
            supportActionBar?.subtitle = truckName
        }
    }

    override fun onStart() {
        super.onStart()

        val columns = arrayOf("_id", "rota", "info")
        routes = MatrixCursor(columns).apply {
            addRow(arrayOf(1, "Rota 101", "Centro • Turno manhã"))
            addRow(arrayOf(2, "Rota 202", "Bairro Norte • Turno tarde"))
            addRow(arrayOf(3, "Rota 303", "Bairro Sul • Turno noite"))
            addRow(arrayOf(4, "Rota 404", "Zona Leste • Turno manhã"))
            addRow(arrayOf(5, "Rota 505", "Zona Oeste • Turno tarde"))
        }

        val list = findViewById<ListView>(R.id.lvRoutes)
        list.adapter = br.edu.utfpr.coletapb.adapter.ItemRoute(this, routes)

        list.setOnItemClickListener { _, _, position, _ ->
            routes.moveToPosition(position)
            val routeId   = routes.getInt(0)
            val routeName = routes.getString(1)
            val routeInfo = routes.getString(2)

            val it = Intent(this, StartRoute::class.java).apply {
                putExtra("route_id", routeId)
                putExtra("route_name", routeName)
                putExtra("route_info", routeInfo)

                // (opcional) repassar info do caminhão
                putExtra("truck_name", intent.getStringExtra("truck_name"))
                putExtra("truck_plate", intent.getStringExtra("truck_plate"))
            }
            startActivity(it)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}

