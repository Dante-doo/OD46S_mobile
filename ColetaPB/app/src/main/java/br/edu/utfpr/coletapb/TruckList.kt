package br.edu.utfpr.coletapb

import android.content.Intent
import android.database.MatrixCursor
import android.os.Bundle
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import com.example.coletapb.adapter.ItemTruck

class TruckList : AppCompatActivity() {

    private lateinit var registers: MatrixCursor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_truck_list)

        // Mostra a seta de voltar (hierarquia já está no manifest)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onStart() {
        super.onStart()

        val columns = arrayOf("_id", "nome", "placa")
        registers = MatrixCursor(columns).apply {
            addRow(arrayOf(1, "Caminhão Azul",    "ABC-1234"))
            addRow(arrayOf(2, "Caminhão Verde",   "DEF-5678"))
            addRow(arrayOf(3, "Caminhão Vermelho","GHI-9012"))
            addRow(arrayOf(4, "Caminhão Branco",  "JKL-3456"))
            addRow(arrayOf(5, "Caminhão Preto",   "MNO-7890"))
        }

        val listView = findViewById<ListView>(R.id.lvTrucks)
        listView.adapter = ItemTruck(this, registers)

        listView.setOnItemClickListener { _, _, position, _ ->
            registers.moveToPosition(position)
            val id    = registers.getInt(0)
            val nome  = registers.getString(1)
            val placa = registers.getString(2)

            val it = Intent(this, RouteList::class.java).apply {
                putExtra("truck_id", id)
                putExtra("truck_name", nome)
                putExtra("truck_plate", placa)
            }
            startActivity(it)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}

