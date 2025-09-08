package br.edu.utfpr.mapas

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import br.edu.utfpr.mapas.databinding.ActivityMainBinding
import java.net.URL
import java.util.Locale

class MainActivity : AppCompatActivity(), LocationListener {
    // Constante para identificar a solicitação de permissão
    private val PERMISSION_REQUEST_CODE = 2

    private lateinit var tvLatitude : TextView
    private lateinit var tvLongitude: TextView
    private lateinit var binding: ActivityMainBinding

    private lateinit var locationManager: LocationManager

    val apiGeoCodingKey = BuildConfig.GOOGLE_GEOCODING_API_KEY
    val apiMapsStaticKey = BuildConfig.GOOGLE_MAPSSTATIC_API_KEY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        //setContentView(R.layout.activity_main)
        setContentView(binding.root)


        binding.btVerMapa.setOnClickListener {
            btVerMapaOnClick()
        }

        binding.btVerEndereco.setOnClickListener{
            btVerEnderecoOnClick()
        }

        binding.btVerImagem.setOnClickListener{
            btVerImagemOnClick()
        }

        //tvLatitude = findViewById(R.id.tvLatitude)
        tvLatitude = binding.tvLatitude
        //tvLongitude = findViewById(R.id.tvLongitude)
        tvLongitude = binding.tvLongitude

        //Inicializando o gerenciador de localização
        locationManager = getSystemService( Context.LOCATION_SERVICE) as LocationManager
    }

    private fun checkLocationPermissions (){
        // Definindo o provedor de localização (GPS_PROVIDER) e a frequência de atualização.
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // As permissões não foram concedidas, adicionado permissão em tempo de execução
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                PERMISSION_REQUEST_CODE
            )
        }else{
            startLocationUpdates()
        }
    }

    override fun onResume(){
        super.onResume()
        // Verificando se os provedores de localização estão ativados.
        // Se não estiverem, direcionar o usuário para as configurações.
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) && !locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Por favor, ative a localização nas configurações do sistema.", Toast.LENGTH_LONG).show()
        } else {
            //Se os provedores estiverem ativados, prosseguir com a verificação de permissões.
            checkLocationPermissions()
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                1000,
                10f,
                this
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Se permissão concedida, iniciar as atualizações de localização
                startLocationUpdates()
            } else {
                Toast.makeText(
                    this,
                    "Permissão de localização negada.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onLocationChanged(location: Location) {
        //tvLatitude.text = location.latitude.toString()
        binding.tvLatitude.text = location.latitude.toString()
        //tvLongitude.text = location.longitude.toString()
        binding.tvLongitude.text = location.longitude.toString()
        Log.e("GPS_alterou", "Pegando a localização")
    }

    override fun onPause() {
        super.onPause()
        // Pausar as atualizações para economizar bateria.
        locationManager.removeUpdates(this)
    }

    private fun btVerMapaOnClick() {
        val intent = Intent(this, MapsActivity::class.java)
        intent.putExtra("latitude", tvLatitude.text.toString().toDouble())
        intent.putExtra("longitude", tvLongitude.text.toString().toDouble())
        startActivity(intent)
    }

    private fun btVerEnderecoOnClick() {
        val latitude = binding.tvLatitude.text.toString().toDouble()
        val longitude = binding.tvLongitude.text.toString().toDouble()
        val geocoder = Geocoder(this, Locale.getDefault())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Chama a função isolada para API 33+
            getAddressUsingGeocodeListener(geocoder, latitude, longitude)
        } else {
            // Mantém a lógica para APIs mais antigas
            val addresses = geocoder.getFromLocation(latitude, longitude, 1)
            addresses?.firstOrNull()?.let { address ->
                exibirAlerta("${address.thoroughfare}, ${address.subThoroughfare} ${address.subAdminArea} ${address.adminArea} ${address.postalCode}")
            }
        }
        /*
        Thread({

            // Fazer um requisição http como esta https://maps.googleapis.com/maps/api/geocode/xml?latlng=-
            //26.0751195,-53.0613228&key=YOUR_KEY_HERE
            //Pegar a resposta e exibir num alterDialog
            val enderecoURL =
                "https://maps.googleapis.com/maps/api/geocode/xml?latlng=${binding.tvLatitude.text.toString()}," +
                        "${binding.tvLongitude.text.toString()}&key=${apiGeoCodingKey}"

            //biblioteca passa acessar a rede no Android
            val url = URL(enderecoURL)

            //Responsável pela conexão como servidor, ele inicia o processo da conexão,
            // porém, para a conexão ocorrer ele precisar saber se é GET...
            val urlConnection = url.openConnection()

            //estabelecer a conexão e ler os dados da url
            // uma string de entrada de dados, recebe a string que vem do servidor, caracter por caracter,
            // por meio de um fluxo binário
            val inputStream = urlConnection.getInputStream() //se ficar dessa forma, vai travar o app, precisamos colocar numa thread paralela

            val entrada = BufferedReader(InputStreamReader(inputStream))

            val saida = StringBuilder()
            var linha = entrada.readLine()
            while(linha!= null){
                saida.append(linha)
                linha = entrada.readLine()
            }

            runOnUiThread{
                val enderecoFormatado = saida.substring(
                    saida.indexOf("<formatted_address>")+19, //indice inicial
                    saida.indexOf("</formatted_address>"))   //indice final
                exibirAlerta(enderecoFormatado)
            }
        }).start()
        */

    }

    /*Obtém o endereço de uma localização geográfica (latitude e longitude) usando a API assíncrona do Android,
     disponível a partir do Android 13 (TIRAMISU).
     GeocodeListener é a abordagem recomendada para geocodificação em versões mais recentes do Android.
     Diferentemente dos métodos síncronos mais antigos, que podem congelar a interface do usuário (UI)
     se executados na thread principal, essa abordagem é assíncrona. Isso significa que a requisição
     é feita em segundo plano, sem bloquear a UI.
    * */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun getAddressUsingGeocodeListener(geocoder: Geocoder, latitude: Double, longitude: Double) {
        // geocodeListener é uma interface para tratar a resposta da requisição.
        // Quando o Geocoder obtém o endereço, ele chama o metodo onGeocode do listener,
        // passando uma lista de objetos Address.
        val geocodeListener = Geocoder.GeocodeListener { address ->
            // Pega o primeiro endereço da lista, se houver algum. O let garante que o código
            // dentro do bloco só será executado se um endereço for encontrado.
            address.firstOrNull()?.let { address ->
               exibirAlerta("${address.thoroughfare}, ${address.subThoroughfare} ${address.subAdminArea} ${address.adminArea} ${address.postalCode}")
            }
        }
        // inicia a requisição para obter o endereço. Ela "chama" o serviço de geocodificação
        // do Android para fazer a busca. O Geocoder vai usar o listener para devolver o resultado da busca.
        geocoder.getFromLocation(latitude, longitude, 1, geocodeListener)
    }

    private fun exibirAlerta(msg : String){
        val dialog = AlertDialog.Builder(this)
        dialog.setTitle("Endereço")
        dialog.setMessage(msg)
        dialog.setNeutralButton("Ok", null)
        dialog.setCancelable(false) //usuário tem que clicar no botão ok para fechar a janela, não pode clicar fora
        dialog.show()
    }

    private fun btVerImagemOnClick() {
        //https://maps.googleapis.com/maps/api/staticmap?center=-26.081185,-53.091238&zoom=15&size=400x400&key=YOUR_KEY_HERE

        Thread({

            val enderecoURL =
                "https://maps.googleapis.com/maps/api/staticmap?center=${binding.tvLatitude.text.toString()}," +
                        "${binding.tvLongitude.text.toString()}&zoom=15&size=400x400" +
                        "&markers=${binding.tvLatitude.text.toString()},${binding.tvLongitude.text.toString()}" +
                        "&key=${apiMapsStaticKey}"

            //biblioteca passa acessar a rede no Android
            val url = URL(enderecoURL)

            //Responsável pela conexão como servidor, ele inicia o processo da conexão
            val urlConnection = url.openConnection()

            //estabelece a conexão e lê os dados da url
            val inputStream = urlConnection.getInputStream()

            val imagem = BitmapFactory.decodeStream(inputStream)

            runOnUiThread{
                binding.ivMapa.setImageBitmap(imagem)
            }

        }).start()
    }

}