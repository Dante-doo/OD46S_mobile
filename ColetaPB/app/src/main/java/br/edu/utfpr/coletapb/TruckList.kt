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
        supportActionBar?.title = "Meu Caminhão"

        listView = findViewById(R.id.lvTrucks)

        adapter = ItemTruck(this, emptyList())
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            val vehicle = adapter.getItem(position)

            // Passamos para a próxima tela
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
        loadAssignedVehicle()
    }

    private fun loadAssignedVehicle() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // BUSCA A ESCALA (ASSIGNMENT)
                val response = RetrofitClient.getApiService(applicationContext).getMyAssignment()

                if (response.isSuccessful) {
                    val assignment = response.body()?.data?.assignment

                    withContext(Dispatchers.Main) {
                        if (assignment != null) {
                            // Mostra APENAS o veículo da escala
                            adapter.updateData(listOf(assignment.vehicle))
                        } else {
                            Toast.makeText(this@TruckList, "Nenhuma escala encontrada hoje.", Toast.LENGTH_LONG).show()
                            adapter.updateData(emptyList())
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        val msg = if (response.code() == 404) "Você não tem escala ativa." else "Erro: ${response.code()}"
                        Toast.makeText(this@TruckList, msg, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TruckList, "Erro de conexão: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}