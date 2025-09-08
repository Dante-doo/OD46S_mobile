package br.edu.utfpr.persistenciaalternativa

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {
    private lateinit var btOnOff: Button
    private lateinit var ivImage: ImageView

    private var ligado = false
    private lateinit var sharedPreference: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btOnOff = findViewById(R.id.btOnOff)
        ivImage = findViewById(R.id.ivImage)

        btOnOff.setOnClickListener {
            btOnOffOnClick()
        }

        sharedPreference = getSharedPreferences("properties", MODE_PRIVATE)

        ligado = sharedPreference.getBoolean("ligado", false)
        when(ligado) {
            true -> {
                ivImage.setImageResource(android.R.drawable.star_big_on)
            }
            false -> {
                ivImage.setImageResource(android.R.drawable.star_big_off)
            }
        }
    }

    private fun btOnOffOnClick() {
        when(ligado) {
            true -> {
                ivImage.setImageResource(android.R.drawable.star_big_off)
                ligado = false
            }
            false -> {
                ivImage.setImageResource(android.R.drawable.star_big_on)
                ligado = true
            }
        }
        val editor = sharedPreference.edit()
        editor.putBoolean("ligado", ligado)
        editor.apply()
    }

}