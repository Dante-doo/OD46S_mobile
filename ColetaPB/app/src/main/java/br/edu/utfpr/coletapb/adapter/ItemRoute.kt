package br.edu.utfpr.coletapb.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import br.edu.utfpr.coletapb.R
import br.edu.utfpr.coletapb.data.RouteEntity

class ItemRoute(
    context: Context,
    private val data: MutableList<RouteEntity> // backing list mutável
) : ArrayAdapter<RouteEntity>(context, 0, data) {

    // ViewHolder simples (performance)
    private class VH(v: View) {
        val title: TextView = v.findViewById(R.id.tvRoute)
        val subtitle: TextView = v.findViewById(R.id.tvRouteInfo)
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view: View
        val holder: VH
        if (convertView == null) {
            view = LayoutInflater.from(context).inflate(R.layout.item_route, parent, false)
            holder = VH(view)
            view.tag = holder
        } else {
            view = convertView
            holder = view.tag as VH
        }

        val route = getItem(position)
        if (route != null) {
            holder.title.text = route.name
            holder.subtitle.text = route.description
        }

        return view
    }

    /** Atualiza os dados sem recriar o adapter */
    fun setData(newData: List<RouteEntity>) {
        data.clear()
        data.addAll(newData)
        notifyDataSetChanged()
    }
}
