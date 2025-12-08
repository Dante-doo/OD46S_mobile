package br.edu.utfpr.coletapb

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import br.edu.utfpr.coletapb.data.AppDatabase
import br.edu.utfpr.coletapb.data.model.AssignmentDto
import br.edu.utfpr.coletapb.data.model.RouteEntity
import br.edu.utfpr.coletapb.data.remote.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CurrentAssignmentList : AppCompatActivity() {

    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var cardAssignment: CardView
    private lateinit var tvRouteName: TextView
    private lateinit var tvRouteInfo: TextView
    private lateinit var tvTruckPlate: TextView
    private lateinit var tvTruckModel: TextView

    private var currentAssignment: AssignmentDto? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_current_assignment_list)
        supportActionBar?.title = "Minha Escala"

        // Bind Views
        progressBar = findViewById(R.id.progressBar)
        tvEmpty = findViewById(R.id.tvEmpty)
        cardAssignment = findViewById(R.id.cardAssignment)
        tvRouteName = findViewById(R.id.tvRouteName)
        tvRouteInfo = findViewById(R.id.tvRouteInfo)
        tvTruckPlate = findViewById(R.id.tvTruckPlate)
        tvTruckModel = findViewById(R.id.tvTruckModel)

        cardAssignment.setOnClickListener {
            currentAssignment?.let { assign ->
                val it = Intent(this, StartRoute::class.java).apply {
                    putExtra("route_id", assign.route.id)
                    putExtra("route_name", assign.route.name)
                    putExtra("route_info", assign.route.description)
                    putExtra("assignment_id", assign.id)
                }
                startActivity(it)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        loadAssignment()
    }

    private fun loadAssignment() {
        progressBar.visibility = View.VISIBLE
        cardAssignment.visibility = View.GONE
        tvEmpty.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.getApiService(applicationContext).getMyAssignment()
                }

                if (response.isSuccessful && response.body()?.data?.assignment != null) {
                    val assignment = response.body()!!.data.assignment
                    currentAssignment = assignment

                    saveRouteLocally(assignment)

                    tvRouteName.text = assignment.route.name
                    val periodo = assignment.route.periodicity ?: "Diário"
                    tvRouteInfo.text = "${assignment.route.description ?: ""} • $periodo"

                    tvTruckPlate.text = assignment.vehicle.license_plate
                    tvTruckModel.text = assignment.vehicle.model

                    progressBar.visibility = View.GONE
                    cardAssignment.visibility = View.VISIBLE
                } else {
                    progressBar.visibility = View.GONE
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.text = "Você não possui escalas ativas no momento.\nAguarde o administrador definir sua nova rota."
                }

            } catch (e: Exception) {
                e.printStackTrace()
                progressBar.visibility = View.GONE
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.text = "Erro ao carregar escala.\nVerifique sua conexão."
                Toast.makeText(this@CurrentAssignmentList, "Erro: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun saveRouteLocally(assignment: AssignmentDto) {
        withContext(Dispatchers.IO) {
            val db = AppDatabase.Companion.getDatabase(applicationContext)
            val dto = assignment.route

            val entity = RouteEntity(
                id = dto.id,
                name = dto.name,
                description = dto.description ?: "",
                collection_type = dto.collection_type ?: "NORMAL",
                periodicity = dto.periodicity ?: "",
                priority = dto.priority ?: "MEDIUM",
                estimated_time_minutes = dto.estimated_time_minutes ?: 0,
                distance_km = dto.distance_km ?: 0.0
            )

            db.routeDao().clearAllRoutes()
            db.routeDao().insertAllRoutes(listOf(entity))
        }
    }
}