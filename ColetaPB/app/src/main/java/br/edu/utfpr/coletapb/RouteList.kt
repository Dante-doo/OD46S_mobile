package br.edu.utfpr.coletapb

import android.database.MatrixCursor
import android.os.Bundle
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import br.edu.utfpr.coletapb.adapter.ItemRoute

class RouteList : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_route_list)
    }

    override fun onStart() {
        super.onStart()

        // Colunas do cursor: id, rota (titulo), info (detalhes)
        val columns = arrayOf("_id", "rota", "info")
        val routes = MatrixCursor(columns).apply {
            addRow(arrayOf(1, "Rota 101", "Centro • Turno manhã"))
            addRow(arrayOf(2, "Rota 202", "Bairro Norte • Turno tarde"))
            addRow(arrayOf(3, "Rota 303", "Bairro Sul • Turno noite"))
            addRow(arrayOf(4, "Rota 404", "Zona Leste • Turno manhã"))
            addRow(arrayOf(5, "Rota 505", "Zona Oeste • Turno tarde"))
        }

        val list = findViewById<ListView>(R.id.lvRoutes)
        list.adapter = ItemRoute(this, routes)
    }
}
