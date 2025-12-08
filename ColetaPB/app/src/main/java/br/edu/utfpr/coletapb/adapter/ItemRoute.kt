package br.edu.utfpr.coletapb.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import br.edu.utfpr.coletapb.R
import br.edu.utfpr.coletapb.data.model.RouteEntity // <--- OBRIGATÓRIO

class ItemRoute(
    private val context: Context,
    private var routes: MutableList<RouteEntity>
) : BaseAdapter() {

    override fun getCount(): Int = routes.size

    override fun getItem(position: Int): RouteEntity = routes[position]

    override fun getItemId(position: Int): Long = routes[position].id

    fun setData(newRoutes: List<RouteEntity>) {
        routes.clear()
        routes.addAll(newRoutes)
        notifyDataSetChanged()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_route, parent, false)

        val route = getItem(position)

        val tvName = view.findViewById<TextView>(R.id.tvRouteName)
        val tvDesc = view.findViewById<TextView>(R.id.tvRouteDesc)

        tvName.text = route.name
        tvDesc.text = route.description ?: "Sem descrição"

        return view
    }
}