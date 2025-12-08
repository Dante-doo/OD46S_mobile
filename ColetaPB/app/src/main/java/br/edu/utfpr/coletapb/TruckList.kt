package br.edu.utfpr.coletapb

import android.content.Intent
import android.os.Bundle
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import br.edu.utfpr.coletapb.adapter.ItemTruck
import br.edu.utfpr.coletapb.data.remote.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TruckList : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var adapter: ItemTruck

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_truck_list)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        listView = findViewById(R.id.lvTrucks)

        // Inicializa adapter vazio
        adapter = ItemTruck(this, emptyList())
        listView.adapter = adapter

        // Clique no item
        listView.setOnItemClickListener { _, _, position, _ ->
            val vehicle = adapter.getItem(position)

            val it = Intent(this, RouteList::class.java).apply {
                putExtra("truck_id", vehicle.id)
                putExtra("truck_model", vehicle.model)
                putExtra("truck_plate", vehicle.license_plate)
            }
            startActivity(it)
        }
    }

    override fun onStart() {
        super.onStart()
        loadVehicles()
    }

    private fun loadVehicles() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Chama API (usa getApiService(context) para injetar o Token)
                val response = RetrofitClient.getApiService(applicationContext).getVehicles()

                if (response.isSuccessful) {
                    val vehicles = response.body()?.data?.vehicles ?: emptyList()

                    withContext(Dispatchers.Main) {
                        if (vehicles.isEmpty()) {
                            Toast.makeText(this@TruckList, "Nenhum veículo encontrado", Toast.LENGTH_SHORT).show()
                        }
                        adapter.updateData(vehicles)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@TruckList, "Erro ao carregar veículos: ${response.code()}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TruckList, "Erro de conexão", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}