package br.edu.utfpr.coletapb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import br.edu.utfpr.coletapb.data.remote.RetrofitClient
import br.edu.utfpr.coletapb.utils.CustomTileSource
import br.edu.utfpr.coletapb.utils.SSLHelper
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

/**
 * Tela que exibe o trajeto percorrido em uma execução de rota
 */
class ExecutionMapActivity : AppCompatActivity() {
    
    private var executionId: Long = 0L
    private lateinit var mapView: MapView
    private lateinit var tvRouteName: TextView
    private lateinit var tvStats: TextView
    private lateinit var toolbar: MaterialToolbar
    
    private val gpsPoints = mutableListOf<GeoPoint>()
    private val eventMarkers = mutableListOf<Marker>()
    private var routePolyline: Polyline? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_execution_map)
        
        // Remove ActionBar padrão
        supportActionBar?.hide()
        
        executionId = intent.getLongExtra("execution_id", 0L)
        if (executionId == 0L) {
            Toast.makeText(this, "ID de execução inválido", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        // Configura MaterialToolbar
        toolbar = findViewById<MaterialToolbar>(R.id.topAppBar)
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        
        // Inicializa views
        mapView = findViewById(R.id.mapView)
        tvRouteName = findViewById(R.id.tvRouteName)
        tvStats = findViewById(R.id.tvStats)
        
        // Configura SSL para OSMDroid
        SSLHelper.configureSSLForOSMDroid()
        
        // Configura OSMDroid
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        val userAgent = "ColetaPB/1.0 (Android ${android.os.Build.VERSION.RELEASE}; ${android.os.Build.MODEL})"
        Configuration.getInstance().userAgentValue = userAgent
        
        // Configura cache de tiles
        val osmdroidBasePath = File(getExternalFilesDir(null), "osmdroid")
        if (!osmdroidBasePath.exists()) {
            osmdroidBasePath.mkdirs()
        }
        Configuration.getInstance().osmdroidBasePath = osmdroidBasePath
        
        val osmdroidTileCache = File(osmdroidBasePath, "tiles")
        if (!osmdroidTileCache.exists()) {
            osmdroidTileCache.mkdirs()
        }
        Configuration.getInstance().osmdroidTileCache = osmdroidTileCache
        
        // Configura mapa
        try {
            val customTileSource = CustomTileSource.createOpenStreetMapHttps()
            mapView.setTileSource(customTileSource)
        } catch (e: Exception) {
            mapView.setTileSource(TileSourceFactory.MAPNIK)
        }
        
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(15.0)
        mapView.setUseDataConnection(true)
        
        // Carrega dados do trajeto
        loadRouteData()
        loadGpsTrack()
    }
    
    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }
    
    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }
    
    /**
     * Carrega dados básicos da execução (nome da rota, etc)
     */
    private fun loadRouteData() {
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.getExecutionById(executionId)
                }
                
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    val data = body["data"] as? Map<String, Any>?
                    val execution = (data?.get("execution") as? Map<String, Any>) ?: data
                    
                    val assignment = execution?.get("assignment") as? Map<*, *>
                    val route = assignment?.get("route") as? Map<*, *>
                    val routeName = route?.get("name") as? String ?: "Rota"
                    
                    tvRouteName.text = routeName
                    
                    // Atualiza título da toolbar
                    withContext(Dispatchers.Main) {
                        toolbar.title = routeName
                    }
                }
            } catch (e: Exception) {
                Log.e("ExecutionMap", "Erro ao carregar dados da rota: ${e.message}", e)
            }
        }
    }
    
    /**
     * Carrega o rastro GPS e desenha no mapa
     */
    private fun loadGpsTrack() {
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.getGpsTrace(executionId)
                }
                
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    val data = body["data"] as? Map<String, Any>?
                    val gpsTrack = (data?.get("gps_track") as? List<Map<String, Any>>) ?: emptyList()
                    val statistics = data?.get("statistics") as? Map<String, Any>
                    
                    Log.d("ExecutionMap", "Carregados ${gpsTrack.size} pontos GPS")
                    
                    // Atualiza estatísticas
                    val totalPoints = (statistics?.get("total_points") as? Number)?.toInt() ?: gpsTrack.size
                    val totalDistance = (statistics?.get("total_distance_km") as? Number)?.toDouble() ?: 0.0
                    tvStats.text = "Pontos: $totalPoints | Distância: ${String.format("%.2f", totalDistance)} km"
                    
                    // Processa pontos GPS
                    gpsPoints.clear()
                    val importantEvents = mutableListOf<Map<String, Any>>()
                    
                    var lastPoint: GeoPoint? = null
                    gpsTrack.forEach { record ->
                        val lat = (record["latitude"] as? Number)?.toDouble()
                        val lng = (record["longitude"] as? Number)?.toDouble()
                        
                        if (lat != null && lng != null && lat != 0.0 && lng != 0.0) {
                            val geoPoint = GeoPoint(lat, lng)
                            
                            // Só adiciona se for diferente do ponto anterior (evita pontos duplicados)
                            if (lastPoint == null || lastPoint!!.latitude != geoPoint.latitude || lastPoint!!.longitude != geoPoint.longitude) {
                                gpsPoints.add(geoPoint)
                                lastPoint = geoPoint
                                Log.d("ExecutionMap", "Ponto adicionado: lat=$lat, lng=$lng")
                            } else {
                                Log.d("ExecutionMap", "Ponto duplicado ignorado: lat=$lat, lng=$lng")
                            }
                            
                            // Marca eventos importantes
                            val eventType = record["eventType"] as? String
                            if (eventType != null && eventType != "NORMAL") {
                                importantEvents.add(record)
                            }
                        } else {
                            Log.w("ExecutionMap", "Ponto GPS inválido: lat=$lat, lng=$lng")
                        }
                    }
                    
                    Log.d("ExecutionMap", "Total de pontos GPS válidos processados: ${gpsPoints.size} (de ${gpsTrack.size} registros)")
                    
                    // Desenha no mapa
                    withContext(Dispatchers.Main) {
                        if (gpsPoints.isNotEmpty()) {
                            Log.d("ExecutionMap", "Processados ${gpsPoints.size} pontos GPS válidos")
                            drawRouteOnMap()
                            addEventMarkers(importantEvents)
                            
                            // Centraliza mapa no trajeto
                            try {
                                if (gpsPoints.size == 1) {
                                    // Se há apenas um ponto, centraliza nele com zoom fixo
                                    mapView.controller.setCenter(gpsPoints.first())
                                    mapView.controller.setZoom(18.0)
                                    Log.d("ExecutionMap", "Mapa centralizado em ponto único")
                                } else {
                                    // Múltiplos pontos: calcula bounding box
                                    val bounds = BoundingBox.fromGeoPoints(gpsPoints)
                                    mapView.zoomToBoundingBox(bounds, false, 50)
                                    Log.d("ExecutionMap", "Mapa centralizado no trajeto (${gpsPoints.size} pontos)")
                                }
                            } catch (e: Exception) {
                                Log.e("ExecutionMap", "Erro ao centralizar mapa: ${e.message}", e)
                                // Fallback: centraliza no primeiro ponto
                                if (gpsPoints.isNotEmpty()) {
                                    mapView.controller.setCenter(gpsPoints.first())
                                    mapView.controller.setZoom(15.0)
                                    Log.d("ExecutionMap", "Mapa centralizado no primeiro ponto (fallback)")
                                }
                            }
                        } else {
                            Log.w("ExecutionMap", "Nenhum ponto GPS válido encontrado")
                            Toast.makeText(this@ExecutionMapActivity, "Nenhum ponto GPS encontrado para esta execução", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    val errorMsg = response.errorBody()?.string() ?: "Erro desconhecido"
                    Log.e("ExecutionMap", "Erro ao carregar trajeto: ${response.code()} - $errorMsg")
                    Toast.makeText(
                        this@ExecutionMapActivity,
                        "Erro ao carregar trajeto: ${response.code()}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e("ExecutionMap", "Erro ao carregar trajeto: ${e.message}", e)
                Toast.makeText(
                    this@ExecutionMapActivity,
                    "Erro: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    /**
     * Desenha a linha do trajeto no mapa
     */
    private fun drawRouteOnMap() {
        if (gpsPoints.isEmpty()) {
            Log.w("ExecutionMap", "Não há pontos GPS para desenhar")
            Toast.makeText(this, "Não há pontos GPS disponíveis para esta execução", Toast.LENGTH_SHORT).show()
            return
        }
        
        Log.d("ExecutionMap", "Desenhando trajeto com ${gpsPoints.size} pontos")
        
        // Remove polyline anterior se existir
        routePolyline?.let {
            mapView.overlays.remove(it)
        }
        
        // Se há apenas um ponto, adiciona um marcador
        if (gpsPoints.size == 1) {
            val marker = Marker(mapView).apply {
                position = gpsPoints.first()
                title = "Localização"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapView.overlays.add(marker)
            Log.d("ExecutionMap", "Adicionado marcador único (apenas 1 ponto GPS)")
        } else {
            // Cria nova polyline para múltiplos pontos
            routePolyline = Polyline(mapView).apply {
                setPoints(gpsPoints)
                color = ContextCompat.getColor(this@ExecutionMapActivity, android.R.color.holo_blue_dark)
                width = 12f
                outlinePaint.strokeWidth = 12f
                outlinePaint.color = ContextCompat.getColor(this@ExecutionMapActivity, android.R.color.holo_blue_dark)
            }
            
            // Adiciona a polyline ao mapa
            mapView.overlays.add(routePolyline)
            Log.d("ExecutionMap", "Polyline adicionada ao mapa com ${gpsPoints.size} pontos")
        }
        
        // Força atualização do mapa
        mapView.post {
            mapView.invalidate()
            // Garante que o mapa seja atualizado após um pequeno delay
            mapView.postDelayed({
                mapView.invalidate()
                Log.d("ExecutionMap", "Mapa invalidado novamente após delay")
            }, 200)
        }
        
        Log.d("ExecutionMap", "Trajeto desenhado com ${gpsPoints.size} pontos")
        if (gpsPoints.isNotEmpty()) {
            Log.d("ExecutionMap", "Primeiro ponto: lat=${gpsPoints.first().latitude}, lng=${gpsPoints.first().longitude}")
            Log.d("ExecutionMap", "Último ponto: lat=${gpsPoints.last().latitude}, lng=${gpsPoints.last().longitude}")
        }
    }
    
    /**
     * Adiciona marcadores para eventos importantes
     */
    private fun addEventMarkers(events: List<Map<String, Any>>) {
        // Remove marcadores anteriores
        eventMarkers.forEach { mapView.overlays.remove(it) }
        eventMarkers.clear()
        
        events.forEach { record ->
            val lat = (record["latitude"] as? Number)?.toDouble()
            val lng = (record["longitude"] as? Number)?.toDouble()
            val eventType = record["eventType"] as? String
            val description = record["description"] as? String ?: ""
            val timestamp = record["gpsTimestamp"] as? String
            
            if (lat != null && lng != null && eventType != null) {
                val drawable = getEventIcon(eventType)
                
                val marker = Marker(mapView).apply {
                    position = GeoPoint(lat, lng)
                    
                    // Define ícone baseado no tipo de evento
                    drawable?.let {
                        val size = (48 * resources.displayMetrics.density).toInt()
                        it.setBounds(0, 0, size, size)
                        icon = it
                    }
                    
                    // Define título e descrição
                    val eventName = when (eventType) {
                        "START" -> "Início da coleta"
                        "POINT_COLLECTED" -> "Ponto coletado"
                        "PROBLEM" -> "Problema"
                        "STOP" -> "Parada"
                        "BREAK" -> "Intervalo"
                        "END" -> "Fim da coleta"
                        else -> eventType
                    }
                    
                    title = eventName
                    snippet = if (description.isNotEmpty()) {
                        "$description${if (timestamp != null) "\n$timestamp" else ""}"
                    } else {
                        timestamp ?: ""
                    }
                    
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
                
                mapView.overlays.add(marker)
                eventMarkers.add(marker)
            }
        }
        
        mapView.invalidate()
        Log.d("ExecutionMap", "Adicionados ${eventMarkers.size} marcadores de eventos")
    }
    
    /**
     * Retorna o ícone apropriado para o tipo de evento
     */
    private fun getEventIcon(eventType: String): Drawable? {
        val iconRes = when (eventType) {
            "START" -> android.R.drawable.ic_menu_mylocation
            "END" -> android.R.drawable.ic_menu_close_clear_cancel
            "POINT_COLLECTED" -> android.R.drawable.ic_menu_compass
            "PROBLEM" -> android.R.drawable.ic_dialog_alert
            "STOP", "BREAK" -> android.R.drawable.ic_menu_recent_history
            else -> android.R.drawable.ic_menu_mylocation
        }
        
        return ContextCompat.getDrawable(this, iconRes)?.apply {
            setTint(ContextCompat.getColor(this@ExecutionMapActivity, android.R.color.holo_red_dark))
        }
    }
    
    /**
     * Converte um Drawable para Bitmap
     */
    private fun drawableToBitmap(drawable: Drawable, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
    
}

