package br.edu.utfpr.coletapb.adapter

import android.content.Context
import android.database.Cursor
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import br.edu.utfpr.coletapb.R

class ItemRoute(private val contexto: Context, private val lista: Cursor) : BaseAdapter() {

    override fun getCount(): Int = lista.count

    override fun getItem(position: Int): Any {
        lista.moveToPosition(position)
        // id, titulo (rota), info (bairros/turno)
        return Triple(lista.getInt(0), lista.getString(1), lista.getString(2))
    }

    override fun getItemId(position: Int): Long {
        lista.moveToPosition(position)
        return lista.getInt(0).toLong()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val v = convertView ?: LayoutInflater.from(contexto)
            .inflate(R.layout.item_route, parent, false)

        val tvRoute     = v.findViewById<TextView>(R.id.tvRoute)
        val tvRouteInfo = v.findViewById<TextView>(R.id.tvRouteInfo)
        val icon        = v.findViewById<ImageView>(R.id.IconRoute)

        lista.moveToPosition(position)

        val titulo = lista.getString(1) // "Rota 101"
        val info   = lista.getString(2) // "Bairros/Turno"

        tvRoute.text = titulo
        tvRouteInfo.text = info
        icon.setImageResource(R.drawable.ic_route)

        return v
    }
}
