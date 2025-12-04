package br.edu.utfpr.coletapb

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import br.edu.utfpr.coletapb.data.model.CollectionPoint
import br.edu.utfpr.coletapb.data.model.PointStatus

class RoutePointsListActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PointsAdapter
    private var points: List<CollectionPoint> = emptyList()
    private var currentPointIndex: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_route_points_list)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Lista de Pontos da Rota"

        points = intent.getParcelableArrayListExtra<CollectionPoint>("points") ?: emptyList()
        currentPointIndex = intent.getIntExtra("current_point_index", 0)

        recyclerView = findViewById(R.id.rvPoints)
        adapter = PointsAdapter(points, currentPointIndex) { point, position ->
            // Retorna o ponto selecionado para a tela anterior
            val resultIntent = Intent().apply {
                putExtra("selected_point_index", position)
            }
            setResult(RESULT_OK, resultIntent)
            finish()
        }
        
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private class PointsAdapter(
        private val points: List<CollectionPoint>,
        private val currentIndex: Int,
        private val onItemClick: (CollectionPoint, Int) -> Unit
    ) : RecyclerView.Adapter<PointsAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvPointNumber: TextView = view.findViewById(R.id.tvPointNumber)
            val tvPointAddress: TextView = view.findViewById(R.id.tvPointAddress)
            val tvPointType: TextView = view.findViewById(R.id.tvPointType)
            val tvPointStatus: TextView = view.findViewById(R.id.tvPointStatus)
            val ivStatus: android.widget.ImageView = view.findViewById(R.id.ivStatus)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_route_point, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val point = points[position]
            
            holder.tvPointNumber.text = point.sequenceOrder.toString()
            holder.tvPointAddress.text = point.address
            
            val wasteType = when (point.wasteType) {
                "COMMERCIAL" -> "Comercial"
                "RESIDENTIAL" -> "Residencial"
                "ORGANIC" -> "Orgânicos"
                else -> point.wasteType
            }
            holder.tvPointType.text = wasteType

            // Status do ponto
            val statusText = when {
                position == currentIndex -> "Próximo"
                point.status == PointStatus.COLLECTED -> "Concluído"
                point.status == PointStatus.PROBLEM -> "Problema"
                point.status == PointStatus.SKIPPED -> "Pulado"
                else -> "Pendente"
            }
            holder.tvPointStatus.text = statusText

            // Cor do número do ponto
            val backgroundColor = when {
                position == currentIndex -> android.graphics.Color.parseColor("#FF9800") // Laranja
                point.status == PointStatus.COLLECTED -> android.graphics.Color.parseColor("#4CAF50") // Verde
                point.status == PointStatus.PROBLEM -> android.graphics.Color.parseColor("#F44336") // Vermelho
                else -> android.graphics.Color.parseColor("#2148C0") // Azul
            }
            holder.tvPointNumber.setBackgroundColor(backgroundColor)

            // Ícone de status
            val iconRes = when {
                position == currentIndex -> android.R.drawable.ic_menu_more
                point.status == PointStatus.COLLECTED -> android.R.drawable.checkbox_on_background
                point.status == PointStatus.PROBLEM -> android.R.drawable.ic_dialog_alert
                else -> android.R.drawable.ic_menu_info_details
            }
            holder.ivStatus.setImageResource(iconRes)

            val iconColor = when {
                position == currentIndex -> android.graphics.Color.parseColor("#FF9800")
                point.status == PointStatus.COLLECTED -> android.graphics.Color.parseColor("#4CAF50")
                point.status == PointStatus.PROBLEM -> android.graphics.Color.parseColor("#F44336")
                else -> android.graphics.Color.parseColor("#7F8C8D")
            }
            holder.ivStatus.setColorFilter(iconColor)

            // Click listener
            holder.itemView.setOnClickListener {
                onItemClick(point, position)
            }
        }

        override fun getItemCount(): Int = points.size
    }
}

