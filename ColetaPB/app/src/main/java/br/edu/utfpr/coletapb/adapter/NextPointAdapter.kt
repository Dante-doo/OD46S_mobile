package br.edu.utfpr.coletapb.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import br.edu.utfpr.coletapb.R
import br.edu.utfpr.coletapb.data.model.CollectionPoint

data class NextPointItem(
    val point: CollectionPoint,
    val distance: String? = null,
    val status: String = "Próximo" // Próximo, Recomendado, Em X m
)

class NextPointAdapter(
    private val items: List<NextPointItem>
) : RecyclerView.Adapter<NextPointAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val viewPointIndicator: View = view.findViewById(R.id.viewPointIndicator)
        val tvPointName: TextView = view.findViewById(R.id.tvPointName)
        val tvPointInfo: TextView = view.findViewById(R.id.tvPointInfo)
        val tvPointDistance: TextView = view.findViewById(R.id.tvPointDistance)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_next_point, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val point = item.point

        // Nome do ponto
        holder.tvPointName.text = point.address ?: "Ponto ${point.sequenceOrder}"

        // Status/Info
        holder.tvPointInfo.text = item.status

        // Distância (se disponível)
        if (item.distance != null) {
            holder.tvPointDistance.text = item.distance
            holder.tvPointDistance.visibility = View.VISIBLE
        } else {
            holder.tvPointDistance.visibility = View.GONE
        }

        // Cor do indicador baseado no status
        val context = holder.viewPointIndicator.context
        when {
            item.status.contains("Recomendado", ignoreCase = true) -> {
                holder.viewPointIndicator.setBackgroundColor(
                    ContextCompat.getColor(context, R.color.primary_blue)
                )
            }
            item.status.contains("Próximo", ignoreCase = true) -> {
                holder.viewPointIndicator.setBackgroundColor(
                    ContextCompat.getColor(context, R.color.primary_blue)
                )
            }
            else -> {
                holder.viewPointIndicator.setBackgroundColor(
                    ContextCompat.getColor(context, android.R.color.darker_gray)
                )
            }
        }
    }

    override fun getItemCount() = items.size
}

