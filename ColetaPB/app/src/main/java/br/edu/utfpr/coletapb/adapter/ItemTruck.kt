package com.example.coletapb.adapter

import android.content.Context
import android.database.Cursor
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import br.edu.utfpr.coletapb.R


class ItemTruck(private val contexto: Context, private val lista: Cursor) : BaseAdapter() {

    override fun getCount(): Int {
        return lista.count
    }

    override fun getItem(position: Int): Any? {
        lista.moveToPosition(position)

        // Retorna um objeto simples com id, nome e placa
        return Triple(
            lista.getInt(0),      // id
            lista.getString(1),   // nome
            lista.getString(2)    // placa
        )
    }

    override fun getItemId(position: Int): Long {
        lista.moveToPosition(position)
        return lista.getInt(0).toLong()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val inflater = contexto.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val view = convertView ?: inflater.inflate(R.layout.item_truck, parent, false)

        // Recuperar os componentes visuais do item_truck.xml
        val tvTruckName = view.findViewById<TextView>(R.id.tvTruckName)
        val tvTruckPlate = view.findViewById<TextView>(R.id.tvTruckPlate)

        // Mover o cursor para a posição correta
        lista.moveToPosition(position)

        // Preencher os componentes
        val id = lista.getInt(0)
        val nome = lista.getString(1)
        val placa = lista.getString(2)

        tvTruckName.text = nome
        tvTruckPlate.text = placa


        return view
    }
}
