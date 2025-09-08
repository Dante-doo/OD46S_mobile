package br.edu.utfpr.mapas

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle

import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import br.edu.utfpr.mapas.databinding.ActivityMaps2Binding

class MapsActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private lateinit var binding: ActivityMaps2Binding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMaps2Binding.inflate(layoutInflater)
        setContentView(binding.root)

        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    /**
     * Manipulates the map once available.
     * This callback is triggered when the map is ready to be used.
     * This is where we can add markers or lines, add listeners or move the camera. In this case,
     * we just add a marker near Sydney, Australia.
     * If Google Play services is not installed on the device, the user will be prompted to install
     * it inside the SupportMapFragment. This method will only be triggered once the user has
     * installed Google Play services and returned to the app.
     */
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        val latitude = intent.getDoubleExtra("latitude", 0.0)
        val longitude = intent.getDoubleExtra("longitude", 0.0)

        // Add a marker in local and move the camera
        val myPosition = LatLng(latitude, longitude)
        //val myPosition = LatLng(-33.86997, 151.2089) //Sydney Tower, em Sydney, na Austrália
        mMap.addMarker(MarkerOptions().position(myPosition).title("Marker in my position"))
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(myPosition, 18f))
        //mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(myPosition, 20f))
        mMap.setMapType( GoogleMap.MAP_TYPE_SATELLITE)

        /*
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(-18.142, 178.431),2f))
        mMap.addPolyline(PolylineOptions()
            .add() //ponto de partida
            .add(LatLng(-33.866, 151.195)) // Ponto de partida, Sidney
            .add(LatLng(-18.142, 178.431)) // Segundo ponto, Fiji
            .add(LatLng(21.291, -157.821))  // Terceiro ponto, Hawaii
            .add(LatLng(37.423, -122.091))  // Quarto ponto, Montain View
            .width(5f)                       // Opcional: define a largura da linha em pixels
        )

         */
    }
}