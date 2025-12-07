package br.edu.utfpr.coletapb.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import br.edu.utfpr.coletapb.R

data class GpsEventItem(
    val time: String,
    val eventType: String,
    val description: String
)

class GpsEventAdapter(
    private val events: List<GpsEventItem>
) : RecyclerView.Adapter<GpsEventAdapter.EventViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_gps_event, parent, false)
        return EventViewHolder(view)
    }

    override fun onBindViewHolder(holder: EventViewHolder, position: Int) {
        val event = events[position]
        holder.bind(event)
    }

    override fun getItemCount(): Int = events.size

    class EventViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        private val tvEventType: TextView = itemView.findViewById(R.id.tvEventType)
        private val tvDescription: TextView = itemView.findViewById(R.id.tvDescription)

        fun bind(event: GpsEventItem) {
            tvTime.text = event.time
            tvEventType.text = event.eventType
            if (event.description.isNotEmpty()) {
                tvDescription.text = event.description
                tvDescription.visibility = View.VISIBLE
            } else {
                tvDescription.visibility = View.GONE
            }
        }
    }
}

