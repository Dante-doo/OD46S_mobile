package br.edu.utfpr.coletapb

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class StartRoute : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_start_route)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Iniciar rota"

        val routeName = intent.getStringExtra("route_name") ?: "Rota"
        val routeInfo = intent.getStringExtra("route_info") ?: ""

        findViewById<TextView>(R.id.tvHeader).text = "$routeName"
        findViewById<TextView>(R.id.tvSub).text    = routeInfo

        findViewById<Button>(R.id.btStart).setOnClickListener {
            // aqui você inicia contagem/mapeamento de fato
            Toast.makeText(this, "Rota iniciada!", Toast.LENGTH_SHORT).show()
            // TODO: startForegroundService / registrar localização / etc
        }

        findViewById<Button>(R.id.btIncident).setOnClickListener {
            // abrir diálogo, enviar status, etc
            Toast.makeText(this, "Imprevisto informado!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
