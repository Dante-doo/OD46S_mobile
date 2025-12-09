package br.edu.utfpr.coletapb.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import br.edu.utfpr.coletapb.R
import br.edu.utfpr.coletapb.data.model.Execution
import br.edu.utfpr.coletapb.utils.DateUtils

class ExecutionHistoryAdapter(
    private val context: Context,
    private val executions: MutableList<Execution>,
    private val onItemClick: (Execution) -> Unit
) : BaseAdapter() {
    
    override fun getCount(): Int = executions.size
    
    override fun getItem(position: Int): Execution = executions[position]
    
    override fun getItemId(position: Int): Long = executions[position].id
    
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_execution_history, parent, false)
        
        val execution = getItem(position)
        
        val tvRouteName = view.findViewById<TextView>(R.id.tvRouteName)
        val tvDate = view.findViewById<TextView>(R.id.tvDate)
        val tvStatus = view.findViewById<TextView>(R.id.tvStatus)
        
        tvRouteName.text = execution.routeName ?: "Rota sem nome"
        
        // Formata data - converte de UTC para timezone local
        if (execution.startTime != null) {
            val date = DateUtils.parseUtcToLocal(execution.startTime)
            if (date != null) {
                tvDate.text = DateUtils.formatForDisplay(date)
            } else {
                tvDate.text = execution.startTime
            }
        } else {
            tvDate.text = "Data não disponível"
        }
        
        tvStatus.text = when (execution.status) {
            "COMPLETED" -> "Status: Concluída"
            "CANCELLED" -> "Status: Cancelada"
            else -> "Status: ${execution.status}"
        }
        
        // Clique
        view.setOnClickListener {
            onItemClick(execution)
        }
        
        return view
    }
}

