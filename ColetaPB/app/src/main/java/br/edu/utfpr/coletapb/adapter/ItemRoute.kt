package br.edu.utfpr.coletapb.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import br.edu.utfpr.coletapb.R
import br.edu.utfpr.coletapb.data.RouteEntity

class ItemRoute(context: Context, routes: List<RouteEntity>) :
    ArrayAdapter<RouteEntity>(context, 0, routes) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        // 1. Obtém o item da rota para esta posição
        val route = getItem(position)

        // 2. Infla o layout do item da lista se não estiver sendo reutilizado
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_route, parent, false)

        // 3. Encontra os TextViews no layout do item
        val tvTitle = view.findViewById<TextView>(R.id.tvRoute)
        val tvSubtitle = view.findViewById<TextView>(R.id.tvRouteInfo)

        // 4. Preenche os dados da rota nos TextViews
        if (route != null) {
            tvTitle.text = route.name
            tvSubtitle.text = route.description
        }

        return view
    }
}