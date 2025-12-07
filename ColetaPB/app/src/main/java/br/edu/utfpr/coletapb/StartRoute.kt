package br.edu.utfpr.coletapb

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.provider.Settings
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import br.edu.utfpr.coletapb.data.AppDatabase
import br.edu.utfpr.coletapb.data.dao.ExecutionDao
import br.edu.utfpr.coletapb.data.dao.GpsDao
import br.edu.utfpr.coletapb.data.local.SharedPreferencesHelper
import br.edu.utfpr.coletapb.data.model.*
import br.edu.utfpr.coletapb.data.remote.RetrofitClient
import br.edu.utfpr.coletapb.data.repository.ExecutionRepository
import br.edu.utfpr.coletapb.data.repository.GpsRepository
import br.edu.utfpr.coletapb.data.repository.RouteRepository
import br.edu.utfpr.coletapb.data.repository.SyncRepository
import br.edu.utfpr.coletapb.dialog.*
import br.edu.utfpr.coletapb.service.GpsTrackingService
import com.google.android.gms.location.*
import com.google.android.gms.tasks.Tasks
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import br.edu.utfpr.coletapb.utils.CustomTileSource
import br.edu.utfpr.coletapb.utils.SSLHelper
import org.osmdroid.views.MapView
import javax.net.ssl.HttpsURLConnection
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.Polygon
import br.edu.utfpr.coletapb.utils.GpsMonitor
import br.edu.utfpr.coletapb.utils.PeriodicityUtils
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
 
class StartRoute : AppCompatActivity() {

    private var routeStarted = false

    // UI Components (novo layout)
    private lateinit var btStart: Button
    private lateinit var btRegisterCollection: Button
    private lateinit var btRegisterProblem: Button
    private lateinit var btRegisterStop: Button
    private lateinit var btCancelRoute: Button
    private lateinit var btFinishRoute: Button
    private lateinit var tvRouteName: TextView
    private lateinit var tvStartTime: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvNextPointsTitle: TextView
    private lateinit var tvRouteSubtitle: TextView
    private lateinit var tvProgressCounter: TextView
    private lateinit var llNonContainerInfo: LinearLayout
    private lateinit var llAllCollected: LinearLayout
    private lateinit var tvAllCollected: TextView
    private lateinit var rvNextPoints: androidx.recyclerview.widget.RecyclerView
    private lateinit var tvViewRouteRecords: TextView
    private lateinit var llRouteStatus: LinearLayout
    private lateinit var llSecondaryButtons: LinearLayout
    private lateinit var bottomSheet: androidx.core.widget.NestedScrollView
    private lateinit var bottomSheetBehavior: com.google.android.material.bottomsheet.BottomSheetBehavior<*>
    private lateinit var fabCenterLocation: com.google.android.material.floatingactionbutton.FloatingActionButton
    
    // Adapter para lista de próximos pontos
    private lateinit var nextPointAdapter: br.edu.utfpr.coletapb.adapter.NextPointAdapter

    // Map (OSMDroid)
    private lateinit var mapView: MapView
    private var currentLocationMarker: Marker? = null
    private val pointMarkers = mutableListOf<Marker>()
    
    // Modo de seguir localização no mapa
    private var followLocation: Boolean = true
    
    // Detecção de movimento real (filtra ruído GPS)
    private var isMoving: Boolean = false
    private var lastMovingLocation: Location? = null
    
    // Throttling para atualização de informações (evita atualizar muito frequentemente)
    private var lastNextPointInfoUpdate: Long = 0L
    private val NEXT_POINT_INFO_UPDATE_INTERVAL = 2000L // Atualiza a cada 2 segundos
    private var routePolyline: Polyline? = null
    private val routePolygons = mutableListOf<Polygon>()

    // Route data
    private var routeWithPoints: RouteWithPoints? = null
    private var currentPointIndex: Int = 0
    private var currentLocation: Location? = null

    // DB
    private lateinit var db: AppDatabase
    private lateinit var executionDao: ExecutionDao
    private lateinit var gpsDao: GpsDao

    // Repositórios
    private lateinit var prefsHelper: SharedPreferencesHelper
    private lateinit var executionRepository: ExecutionRepository
    private lateinit var gpsRepository: GpsRepository
    private lateinit var routeRepository: RouteRepository
    private lateinit var syncRepository: SyncRepository
    private lateinit var gpsMonitor: GpsMonitor

    // Location
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    // Execução
    private var execLocalId: Long = 0L
    private var assignmentId: Long = 0L
    private var backendExecutionId: Long? = null

    // extras
    private var routeId: Long = 0L
    private var routeName: String? = null
    private var routeInfo: String? = null

    private val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
    private val sdfTime = SimpleDateFormat("HH:mm", Locale.getDefault())
    
    // Activity Result Launcher para lista de pontos
    private lateinit var pointsListLauncher: androidx.activity.result.ActivityResultLauncher<Intent>
    
    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val MIN_MOVEMENT_DISTANCE_METERS = 10.0 // Distância mínima para considerar movimento real
        private const val MIN_MOVEMENT_SPEED_MS = 1.0 // Velocidade mínima em m/s para considerar movimento real
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_start_route_new)
        
        // Inicializa RetrofitClient se necessário
        RetrofitClient.init(this)
        
        // Remove ActionBar padrão
        supportActionBar?.hide()
        
        // extras vindos da RouteList
        routeId = intent.getLongExtra("route_id", 0L)
        assignmentId = intent.getLongExtra("assignment_id", 0L)
        routeName = intent.getStringExtra("route_name")
        routeInfo = intent.getStringExtra("route_info")
        
        // Configura MaterialToolbar
        val toolbar = findViewById<MaterialToolbar>(R.id.topAppBar)
        toolbar.title = routeName ?: "Iniciar rota"
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // Inicializa componentes do novo layout
        tvRouteName = findViewById(R.id.tvRouteName)
        tvStartTime = findViewById(R.id.tvStartTime)
        tvStatus = findViewById(R.id.tvStatus)
        tvNextPointsTitle = findViewById(R.id.tvNextPointsTitle)
        tvRouteSubtitle = findViewById(R.id.tvRouteSubtitle)
        tvProgressCounter = findViewById(R.id.tvProgressCounter)
        llNonContainerInfo = findViewById(R.id.llNonContainerInfo)
        llAllCollected = findViewById(R.id.llAllCollected)
        tvAllCollected = findViewById(R.id.tvAllCollected)
        rvNextPoints = findViewById(R.id.rvNextPoints)
        tvViewRouteRecords = findViewById(R.id.tvViewRouteRecords)
        llRouteStatus = findViewById(R.id.llRouteStatus)
        llSecondaryButtons = findViewById(R.id.llSecondaryButtons)
        bottomSheet = findViewById(R.id.bottomSheet)
        btStart = findViewById(R.id.btStart)
        btRegisterCollection = findViewById(R.id.btRegisterCollection)
        btRegisterProblem = findViewById(R.id.btRegisterProblem)
        btRegisterStop = findViewById(R.id.btRegisterStop)
        btCancelRoute = findViewById(R.id.btCancelRoute)
        btFinishRoute = findViewById(R.id.btFinishRoute)
        fabCenterLocation = findViewById(R.id.fabCenterLocation)

        // Configura BottomSheetBehavior usando técnicas corretas de mobile:
        // - Estado inicial: COLLAPSED (mostra mapa)
        // - Arrastar para cima: expande até o header
        // - Arrastar para baixo: volta ao estado inicial
        bottomSheetBehavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet)
        val screenHeight = resources.displayMetrics.heightPixels
        
        // Colapsado: 25% da tela (peek height - altura visível quando colapsado)
        val collapsedHeight = (screenHeight * 0.25).toInt()
        
        // Configura o peek height (altura quando colapsado)
        bottomSheetBehavior.peekHeight = collapsedHeight
        bottomSheetBehavior.isHideable = false
        bottomSheetBehavior.skipCollapsed = false
        bottomSheetBehavior.isFitToContents = false // Permite controlar a altura máxima
        bottomSheetBehavior.isDraggable = true // Garante que é arrastável
        
        // Aguarda o layout ser medido para calcular o expandedOffset
        bottomSheet.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                bottomSheet.viewTreeObserver.removeOnGlobalLayoutListener(this)
                
                // Calcula o offset expandido: distância do topo até onde o bottom sheet deve parar
                val appBarLayout = findViewById<com.google.android.material.appbar.AppBarLayout>(R.id.appBarLayout)
                val appBarHeight = appBarLayout.height
                // Offset expandido = altura do header + pequeno espaço
                val expandedOffset = appBarHeight + (16 * resources.displayMetrics.density).toInt()
                
                // Define o offset para parar abaixo do header quando expandido
                bottomSheetBehavior.expandedOffset = expandedOffset
                
                // Estado inicial COLLAPSED - garante que o mapa seja visível
                bottomSheetBehavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED
                
                Log.d("StartRoute", "BottomSheet configurado - peekHeight: $collapsedHeight, expandedOffset: $expandedOffset")
            }
        })
        
        // Listener para quando o bottom sheet é arrastado
        bottomSheetBehavior.addBottomSheetCallback(object : com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                // Botões de ação agora sempre aparecem quando rota iniciada, independente do estado do bottom sheet
                // Mantém apenas a lógica de visibilidade do tvViewRouteRecords se necessário
                when (newState) {
                    com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED -> {
                        // Quando expandido, mostra lista de pontos se rota iniciada
                        if (routeStarted) {
                            tvViewRouteRecords.visibility = View.VISIBLE
                        }
                    }
                    com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED -> {
                        // Quando colapsado, pode esconder lista de pontos
                        // Mas mantém botões de ação visíveis (gerenciado por applyUiState)
                        if (routeStarted) {
                            tvViewRouteRecords.visibility = View.GONE
                        }
                    }
                    com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_DRAGGING -> {
                        // Durante o arraste (não precisa fazer nada)
                    }
                    com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_SETTLING -> {
                        // Durante a animação (não precisa fazer nada)
                    }
                }
            }
            
            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                // slideOffset: 0.0 quando colapsado, 1.0 quando expandido
                // O BottomSheetBehavior gerencia automaticamente a animação
            }
        })

        // Configura RecyclerView para próximos pontos
        rvNextPoints.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        nextPointAdapter = br.edu.utfpr.coletapb.adapter.NextPointAdapter(emptyList())
        rvNextPoints.adapter = nextPointAdapter

        // Atualiza header
        tvRouteName.text = routeName ?: "Rota"

        // Inicializa componentes
        prefsHelper = SharedPreferencesHelper(this)
        executionRepository = ExecutionRepository(prefsHelper)
        gpsRepository = GpsRepository(prefsHelper)
        routeRepository = RouteRepository(prefsHelper)
        syncRepository = SyncRepository(this, prefsHelper)
        gpsMonitor = GpsMonitor(this)
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        // Verifica GPS e permissões ao abrir a tela - usando post para garantir que a Activity está totalmente criada
        window.decorView.post {
            // Primeiro verifica GPS
            gpsMonitor.checkAndRequestGps(
                onGpsEnabled = {
                    // GPS está ativo, verifica permissões
                    Log.d("StartRoute", "GPS está habilitado, verificando permissões...")
                    if (!checkLocationPermissions()) {
                        Log.d("StartRoute", "Permissões não concedidas, solicitando...")
                        requestLocationPermissions()
                    } else {
                        Log.d("StartRoute", "Permissões já concedidas")
                    }
                },
                onGpsDisabled = {
                    // GPS não ativado, volta para a tela anterior
                    Log.d("StartRoute", "GPS não ativado, voltando para tela anterior")
                    finish()
                }
            )
        }

        // DB
        db = AppDatabase.getDatabase(this)
        executionDao = db.executionDao()
        gpsDao = db.gpsDao()

        // Configura SSL globalmente para o OSMDroid
        // Isso é necessário porque o OSMDroid usa HttpURLConnection que não respeita network_security_config.xml
        SSLHelper.configureSSLForOSMDroid()
        
        // Configura OSMDroid - DEVE ser feito ANTES de criar o MapView
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        
        // User-Agent obrigatório para baixar tiles do OpenStreetMap
        // OpenStreetMap requer um User-Agent identificável e válido
        // Formato recomendado: AppName/Version (OS; Device)
        val userAgent = "ColetaPB/1.0 (Android ${android.os.Build.VERSION.RELEASE}; ${android.os.Build.MODEL})"
        Configuration.getInstance().userAgentValue = userAgent
        Log.d("StartRoute", "User-Agent configurado: $userAgent")
        
        // Verifica se há conexão com internet (com tratamento de erro caso não tenha permissão)
        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }
            val isConnected = capabilities != null && (
                capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
            )
            Log.d("StartRoute", "Conexão com internet: $isConnected")
            
            if (!isConnected) {
                Toast.makeText(this, "Sem conexão com internet. O mapa pode não carregar completamente.", Toast.LENGTH_LONG).show()
            }
        } catch (e: SecurityException) {
            Log.w("StartRoute", "Não foi possível verificar conexão (sem permissão ACCESS_NETWORK_STATE): ${e.message}")
            // Continua normalmente mesmo sem poder verificar a conexão
        } catch (e: Exception) {
            Log.w("StartRoute", "Erro ao verificar conexão: ${e.message}")
        }
        
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
        
        // Configura threads de download para melhor performance
        Configuration.getInstance().setTileDownloadThreads(8)
        Configuration.getInstance().setTileDownloadMaxQueueSize(200)
        
        // Configura timeout para download de tiles
        Configuration.getInstance().setTileFileSystemCacheMaxBytes(50L * 1024 * 1024) // 50MB
        
        Log.d("StartRoute", "OSMDroid configurado - BasePath: $osmdroidBasePath, Cache: $osmdroidTileCache")

        // Inicializa mapa (OSMDroid) - DEVE ser feito DEPOIS da configuração
        mapView = findViewById(R.id.mapView)
        
        // Verifica se o MapView foi encontrado
        if (!::mapView.isInitialized) {
            Log.e("StartRoute", "ERRO: MapView não foi encontrado no layout!")
            Toast.makeText(this, "Erro: Mapa não encontrado", Toast.LENGTH_LONG).show()
        } else {
            Log.d("StartRoute", "MapView encontrado: width=${mapView.width}, height=${mapView.height}, visibility=${mapView.visibility}")
        }
        
        // Garante que o mapa está visível
        mapView.visibility = View.VISIBLE
        
        // Habilita multi-toque e botões de zoom nativos do OSMDroid
        mapView.setMultiTouchControls(true)
        mapView.setBuiltInZoomControls(true) // 🔴 importante: ativar os botões de zoom
        mapView.zoomController.setVisibility(
            org.osmdroid.views.CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT
        )
        
        mapView.controller.setZoom(15.0)
        mapView.setUseDataConnection(true)
        
        // Configura tile source customizado
        // Tenta usar OpenStreetMap primeiro, se falhar tenta CartoDB
        try {
            val customTileSource = CustomTileSource.createOpenStreetMapHttps()
            mapView.setTileSource(customTileSource)
            Log.d("StartRoute", "Tile source configurado: OpenStreetMap HTTPS (Custom)")
        } catch (e: Exception) {
            Log.e("StartRoute", "Erro ao configurar OpenStreetMap: ${e.message}", e)
            // Tenta usar CartoDB como alternativa
            try {
                val cartoDBSource = CustomTileSource.createCartoDB()
                mapView.setTileSource(cartoDBSource)
                Log.d("StartRoute", "Tile source configurado: CartoDB (alternativa)")
            } catch (e2: Exception) {
                Log.e("StartRoute", "Erro ao configurar CartoDB: ${e2.message}", e2)
                // Fallback para tile source padrão
                try {
                    mapView.setTileSource(TileSourceFactory.MAPNIK)
                    Log.d("StartRoute", "Usando tile source padrão: MAPNIK")
                } catch (e3: Exception) {
                    Log.e("StartRoute", "Erro ao configurar MAPNIK: ${e3.message}", e3)
                    try {
                        mapView.setTileSource(TileSourceFactory.DEFAULT_TILE_SOURCE)
                        Log.d("StartRoute", "Usando tile source padrão do sistema")
                    } catch (e4: Exception) {
                        Log.e("StartRoute", "Erro ao configurar tile source padrão: ${e4.message}", e4)
                    }
                }
            }
        }
        
        // Posiciona os botões de zoom no canto superior direito
        // COMENTADO: Removido temporariamente para garantir que os botões apareçam na posição padrão
        /*
        mapView.postDelayed({
            try {
                // O OSMDroid adiciona os botões de zoom como child do mapView
                // Procura pela view que contém os botões de zoom
                val mapContainer = findViewById<android.widget.FrameLayout>(R.id.mapContainer)
                mapContainer?.let { container ->
                    // Procura por views que possam ser os botões de zoom
                    for (i in 0 until container.childCount) {
                        val child = container.getChildAt(i)
                        // Verifica se é uma view que contém botões (geralmente LinearLayout ou similar)
                        if (child is android.view.ViewGroup && child.childCount > 0) {
                            // Verifica se algum child parece ser um botão de zoom
                            var hasZoomButtons = false
                            for (j in 0 until child.childCount) {
                                val grandChild = child.getChildAt(j)
                                if (grandChild is android.widget.ImageButton || 
                                    grandChild is android.widget.Button) {
                                    hasZoomButtons = true
                                    break
                                }
                            }
                            
                            if (hasZoomButtons) {
                                val layoutParams = child.layoutParams as? android.widget.FrameLayout.LayoutParams
                                if (layoutParams != null) {
                                    layoutParams.gravity = android.view.Gravity.TOP or android.view.Gravity.END
                                    val marginPx = (16 * resources.displayMetrics.density).toInt()
                                    layoutParams.setMargins(0, marginPx, marginPx, 0)
                                    child.layoutParams = layoutParams
                                    Log.d("StartRoute", "Botões de zoom posicionados no canto superior direito")
                                    break
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("StartRoute", "Não foi possível posicionar botões de zoom: ${e.message}")
            }
        }, 500) // Delay para garantir que os botões já foram adicionados
        */
        
        // Configurações adicionais para garantir download de tiles
        mapView.isHorizontalMapRepetitionEnabled = true
        mapView.isVerticalMapRepetitionEnabled = false
        mapView.isTilesScaledToDpi = true
        
        // Força atualização do mapa após um pequeno delay para garantir que tudo está configurado
        mapView.post {
            mapView.invalidate()
            Log.d("StartRoute", "Mapa invalidado após configuração")
            
            // Verifica se o mapa está visível após invalidar
            Log.d("StartRoute", "Mapa após invalidate - visibility=${mapView.visibility}, width=${mapView.width}, height=${mapView.height}")
            
            // Se o mapa não tem dimensões, força um layout
            if (mapView.width == 0 || mapView.height == 0) {
                Log.w("StartRoute", "Mapa sem dimensões, forçando layout")
                mapView.requestLayout()
            }
        }
        
        // Aguarda o layout ser medido antes de logar informações
        mapView.postDelayed({
            Log.d("StartRoute", "Mapa inicializado - Zoom: ${mapView.zoomLevelDouble}, DataConnection: ${mapView.useDataConnection()}, TileSource: ${mapView.tileProvider.tileSource.name()}, Width: ${mapView.width}, Height: ${mapView.height}, Visibility: ${mapView.visibility}")
        }, 500)
        
        // Se o usuário tocar/arrastar o mapa, desliga o followLocation
        mapView.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN,
                android.view.MotionEvent.ACTION_MOVE -> {
                    followLocation = false
                }
            }
            // Deixa o MapView tratar o toque normalmente
            false
        }
        
        // Configura overlay de localização
        setupLocationOverlay()

        // Carrega pontos da rota
        loadRoutePoints()
        
        // Desenha rota se já estiver carregada
        routeWithPoints?.let { drawRouteOnMap(it) }

        // Restaura estado básico (mas não marca como iniciada ainda)
        execLocalId = savedInstanceState?.getLong("exec_local_id") ?: 0L
        backendExecutionId = savedInstanceState?.getLong("backend_exec_id")?.takeIf { it > 0 }
        currentPointIndex = savedInstanceState?.getInt("current_point_index") ?: 0
        
        // NÃO restaura routeStarted do savedInstanceState - sempre verifica no backend primeiro
        routeStarted = false
        
        // Verifica se há execução em andamento (isso vai atualizar routeStarted corretamente)
        checkCurrentExecution()
        
        // Aplica estado inicial (será atualizado após checkCurrentExecution)
        applyUiState()

        // Inicializa Activity Result Launcher
        pointsListLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val selectedIndex = result.data?.getIntExtra("selected_point_index", -1) ?: -1
                if (selectedIndex >= 0 && selectedIndex < (routeWithPoints?.collectionPoints?.size ?: 0)) {
                    // Atualiza o índice do ponto atual e centraliza no mapa
                    currentPointIndex = selectedIndex
                    updateNextPointInfo()
                    routeWithPoints?.let { drawRouteOnMap(it) }
                }
            }
        }

        // Listeners
        btStart.setOnClickListener { showStartRouteDialog() }
        btRegisterCollection.setOnClickListener { showCollectionDialog() }
        btRegisterProblem.setOnClickListener { showProblemDialog() }
        btRegisterStop.setOnClickListener { showRegisterStopDialogNew() }
        btCancelRoute.setOnClickListener { showCancelRouteDialog() }
        btFinishRoute.setOnClickListener { showEndRouteDialog() }
        tvViewRouteRecords.setOnClickListener { showRoutePointsList() }
        fabCenterLocation.setOnClickListener {
            followLocation = true
            centerMapOnCurrentLocation()
        }
        
        // Inicia monitoramento contínuo do GPS
        gpsMonitor.startMonitoring {
            // GPS foi desativado enquanto o app está aberto
            if (routeStarted) {
                // Se a rota está em andamento, mostra aviso e bloqueia funcionalidades
                AlertDialog.Builder(this)
                    .setTitle("GPS Desativado")
                    .setMessage("O GPS foi desativado durante a rota. Todas as funcionalidades estão bloqueadas até que o GPS seja reativado.")
                    .setPositiveButton("Ativar GPS") { _, _ ->
                        gpsMonitor.checkAndRequestGps()
                    }
                    .setCancelable(false)
                    .show()
            } else {
                gpsMonitor.showGpsDisabledWarning()
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        mapView.onResume()
        
        // Força atualização do mapa ao retornar
        mapView.post {
            mapView.invalidate()
            Log.d("StartRoute", "Mapa invalidado no onResume")
        }
        
        // Verifica GPS ao retornar
        if (!gpsMonitor.isGpsEnabled()) {
            gpsMonitor.showGpsDisabledWarning()
            return
        }
        
        // Verifica se GPS foi ativado quando o usuário volta das configurações
        if (checkLocationPermissions() && gpsMonitor.isGpsEnabled()) {
            // Se GPS está ativo agora, configura o "overlay" de localização
            // (na prática, apenas o marker azul + updates do FusedLocation)
            setupLocationOverlay()
        }
    }
    
    override fun onPause() {
        super.onPause()
        mapView.onPause()
        
        // Remove atualizações de localização para economizar bateria
        locationCallback?.let { 
            fusedLocationClient.removeLocationUpdates(it)
        }
    }
    
    private fun setupLocationOverlay() {
        // Agora usamos apenas o marker customizado (ponto azul), sem MyLocationNewOverlay
        if (!checkLocationPermissions() || !gpsMonitor.isGpsEnabled()) {
            Log.w(
                "StartRoute",
                "Não foi possível configurar localização: sem permissão ou GPS desativado"
            )
            return
        }

        // Se ainda não criamos o marker, cria agora
        if (currentLocationMarker == null) {
            createCustomLocationMarker()
        } else {
            // Se já existe, apenas reinicia as atualizações de localização
            startLocationUpdatesForMarker()
        }
    }
    
    private fun createCustomLocationMarker() {
        // Remove marker anterior se existir
        currentLocationMarker?.let { mapView.overlays.remove(it) }
        
        // Cria novo marker com ícone customizado
        currentLocationMarker = Marker(mapView)
        currentLocationMarker?.title = "Minha localização"
        
        // Configura ícone customizado (estilo Google Maps - azul)
        try {
            val locationIcon = ContextCompat.getDrawable(this, R.drawable.my_location_icon)
            locationIcon?.let { drawable ->
                // Tamanho em DP bem pequeno, similar ao ponto azul do Google
                val dp = resources.displayMetrics.density
                val size = (10 * dp).toInt() // antes era 20dp
                drawable.setBounds(0, 0, size, size)
                
                // Não aplica tint, o drawable já tem a cor azul definida
                // ancora bem no centro do ponto
                currentLocationMarker?.icon = drawable
                currentLocationMarker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                Log.d("StartRoute", "Marker de localização customizado criado (${size}px, estilo Google Maps menor)")
            } ?: run {
                // Se não conseguir carregar, usa um drawable simples azul (cor do Google)
                val dp = resources.displayMetrics.density
                val size = (10 * dp).toInt()
                val blueCircle = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(android.graphics.Color.parseColor("#4285F4")) // Azul Google
                    setSize(size, size)
                }
                currentLocationMarker?.icon = blueCircle
                currentLocationMarker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                Log.d("StartRoute", "Marker de localização criado com drawable simples azul")
            }
        } catch (e: Exception) {
            Log.w("StartRoute", "Erro ao criar marker customizado: ${e.message}")
            // Fallback: cria um círculo azul simples (cor do Google)
            try {
                val dp = resources.displayMetrics.density
                val size = (10 * dp).toInt()
                val blueCircle = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(android.graphics.Color.parseColor("#4285F4")) // Azul Google
                    setSize(size, size)
                }
                currentLocationMarker?.icon = blueCircle
                currentLocationMarker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            } catch (e2: Exception) {
                Log.e("StartRoute", "Erro ao criar fallback: ${e2.message}")
            }
        }
        
        // Adiciona ao mapa
        currentLocationMarker?.let { mapView.overlays.add(it) }
        
        // Atualiza a posição inicial se já tiver localização
        // Reseta flags de movimento para inicializar corretamente
        currentLocation?.let { location ->
            lastMovingLocation = null // Reseta para forçar detecção na próxima atualização
            isMoving = false
            updateLocationMarkerPosition(location)
        }
        
        // Inicia atualização contínua da localização para o marker
        startLocationUpdatesForMarker()
    }
    
    private fun startLocationUpdatesForMarker() {
        if (!checkLocationPermissions()) return

        // Remove callback anterior se existir
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }

        // Cria novo callback para atualizar o marker
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    // Usa updateCurrentLocationOnMap que já tem detecção de movimento
                    updateCurrentLocationOnMap(location)
                }
            }
        }

        // Solicita atualizações de localização com intervalo otimizado
        try {
            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                1000L // 1s entre updates (mais estável e menos "nervoso")
            )
                .setMinUpdateIntervalMillis(500L)      // pode atualizar até ~0.5s para movimento rápido
                .setMaxUpdateDelayMillis(1500L)         // máximo 1.5 segundos de delay
                .setWaitForAccurateLocation(false)
                .build()

            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                locationCallback?.let { callback ->
                    fusedLocationClient.requestLocationUpdates(
                        locationRequest,
                        callback,
                        mainLooper
                    )
                    Log.d(
                        "StartRoute",
                        "Atualizações de localização iniciadas para marker customizado (estilo Google Maps)"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("StartRoute", "Erro ao iniciar atualizações de localização: ${e.message}", e)
        }
    }
    
    private fun updateLocationMarkerPosition(location: Location) {
        currentLocationMarker?.let { marker ->
            val geoPoint = GeoPoint(location.latitude, location.longitude)
            marker.position = geoPoint

            // Se o modo "seguir" está ligado, anima o mapa para a nova posição
            if (followLocation) {
                mapView.controller.animateTo(geoPoint)
            }

            // Usa postInvalidate para não travar a UI
            mapView.postInvalidate()
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
    
    /**
     * Tenta restaurar a rota a partir do banco local, mesmo sem backendId.
     * Retorna true se conseguiu restaurar, false caso contrário.
     */
    private suspend fun tryRestoreLocalExecutionForCurrentRoute(): Boolean {
        val currentExec = executionDao.getCurrentExecution()
        if (currentExec != null &&
            currentExec.routeId == routeId &&
            currentExec.status == "IN_PROGRESS"
        ) {
            execLocalId = currentExec.localId
            backendExecutionId = currentExec.backendId // pode ser null, sem problema
            Log.d("StartRoute", "Rota restaurada do banco local: execLocalId=$execLocalId, backendId=$backendExecutionId, routeId=$routeId")
            withContext(Dispatchers.Main) {
                routeStarted = true
                // Aplica estado da UI primeiro (mostra botões)
                applyUiState()
                
                // Garante que o bottom sheet fica colapsado quando restaura a rota
                if (::bottomSheetBehavior.isInitialized) {
                    bottomSheetBehavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED
                    // Garante que os botões permanecem visíveis mesmo após colapsar
                    showActionButtons()
                }
                
                if (checkLocationPermissions()) {
                    startGpsTracking()
                }
            }
            return true
        }
        return false
    }
    
    /**
     * Quando estamos ONLINE e o backend diz que NÃO há execução em andamento,
     * só podemos restaurar execuções locais que:
     * - sejam desta rota (routeId)
     * - status = IN_PROGRESS
     * - backendId == null  (rota iniciada offline e nunca sincronizada)
     *
     * Se existir execução IN_PROGRESS com backendId != null, ela é marcada como COMPLETED,
     * pois o backend já não reconhece mais nenhuma execução ativa para este motorista.
     */
    private suspend fun tryRestoreOfflineExecutionForCurrentRoute(): Boolean {
        val currentExec = executionDao.getCurrentExecution()

        if (currentExec == null) {
            Log.d("StartRoute", "Nenhuma execução local IN_PROGRESS encontrada.")
            return false
        }

        // Caso 1: execução OFFLINE válida para esta rota → restaurar
        if (currentExec.routeId == routeId &&
            currentExec.status == "IN_PROGRESS" &&
            currentExec.backendId == null
        ) {
            execLocalId = currentExec.localId
            backendExecutionId = null

            Log.d(
                "StartRoute",
                "Rota offline restaurada (sem backendId): execLocalId=$execLocalId, routeId=$routeId"
            )

            withContext(Dispatchers.Main) {
                routeStarted = true
                applyUiState()

                if (::bottomSheetBehavior.isInitialized) {
                    bottomSheetBehavior.state =
                        com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED
                    showActionButtons()
                }

                if (checkLocationPermissions()) {
                    startGpsTracking()
                }
            }
            return true
        }

        // Caso 2: temos execução IN_PROGRESS com backendId -> está "fantasma"
        // porque o backend acabou de dizer que não tem mais nada ativo.
        if (currentExec.status == "IN_PROGRESS" && currentExec.backendId != null) {
            val fixedExec = currentExec.copy(
                status = "COMPLETED",
                endTimestamp = currentExec.endTimestamp ?: System.currentTimeMillis()
            )
            executionDao.update(fixedExec)
            Log.w(
                "StartRoute",
                "Execução local IN_PROGRESS com backendId=${currentExec.backendId} " +
                    "marcada como COMPLETED porque o backend não tem execução ativa."
            )
        }

        // Não restaurou nada
        return false
    }
    
    private fun checkCurrentExecution() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val isOnline = try {
                    syncRepository.isOnline()
                } catch (e: Exception) {
                    Log.w("StartRoute", "Erro ao verificar conexão: ${e.message}")
                    false
                }

                if (isOnline) {
                    // Se online, SEMPRE consulta o backend (mesmo se assignmentId=0)
                    Log.d(
                        "StartRoute",
                        "checkCurrentExecution() → ONLINE, assignmentId=$assignmentId, routeId=$routeId"
                    )
                    Log.d("StartRoute", "Online: verificando execução no backend...")
                    val backendResult = executionRepository.getMyCurrentExecution()

                    backendResult.fold(
                        onSuccess = { backendExec ->
                            // Verifica se a execução do backend corresponde à rota atual
                            val matchesRoute = if (assignmentId > 0) {
                                // Se temos assignmentId, valida por assignmentId
                                backendExec != null && backendExec.assignmentId == assignmentId
                            } else {
                                // Se não temos assignmentId, valida por routeId
                                backendExec != null && backendExec.routeId == routeId
                            }
                            
                            if (matchesRoute && backendExec != null) {
                                // ✅ Backend tem execução para esta rota -> verifica se está em andamento
                                val execStatus = backendExec.status ?: "IN_PROGRESS"
                                
                                // Só marca como iniciada se o status for IN_PROGRESS
                                if (execStatus == "IN_PROGRESS") {
                                    backendExecutionId = backendExec.id
                                    Log.d("StartRoute", "Execução encontrada no backend: id=${backendExec.id}, routeId=${backendExec.routeId}, assignmentId=${backendExec.assignmentId}, status=IN_PROGRESS")

                                    val startTimestamp = try {
                                        backendExec.startTime?.let {
                                            java.text.SimpleDateFormat(
                                                "yyyy-MM-dd'T'HH:mm:ss",
                                                java.util.Locale.getDefault()
                                            ).parse(it)?.time
                                        } ?: System.currentTimeMillis()
                                    } catch (e: Exception) {
                                        System.currentTimeMillis()
                                    }

                                    val currentExec = executionDao.getCurrentExecution()
                                    if (currentExec != null && currentExec.backendId == backendExec.id) {
                                        // Atualiza local se precisar
                                        val updatedExec = currentExec.copy(
                                            routeId = routeId,
                                            status = "IN_PROGRESS",
                                            startTimestamp = startTimestamp,
                                            startLat = backendExec.startLat ?: currentExec.startLat,
                                            startLng = backendExec.startLng ?: currentExec.startLng
                                        )
                                        executionDao.update(updatedExec)
                                        execLocalId = currentExec.localId
                                    } else {
                                        // Cria execução local sincronizada com backend
                                        val executionLocal = ExecutionLocal(
                                            routeId = routeId,
                                            vehicleId = null,
                                            driverId = prefsHelper.getDriverId().takeIf { it > 0 },
                                            startTimestamp = startTimestamp,
                                            startLat = backendExec.startLat ?: 0.0,
                                            startLng = backendExec.startLng ?: 0.0,
                                            status = "IN_PROGRESS",
                                            backendId = backendExec.id
                                        )
                                        execLocalId = executionDao.insert(executionLocal)
                                    }

                                    withContext(Dispatchers.Main) {
                                        routeStarted = true
                                        applyUiState()

                                        if (::bottomSheetBehavior.isInitialized) {
                                            bottomSheetBehavior.state =
                                                com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED
                                            showActionButtons()
                                        }

                                        if (checkLocationPermissions()) {
                                            startGpsTracking()
                                        }
                                    }
                                } else {
                                    // Execução existe mas está COMPLETED ou CANCELLED
                                    Log.d("StartRoute", "Execução encontrada no backend mas está $execStatus (não IN_PROGRESS). Não iniciando rota.")
                                    
                                    // Atualiza execução local para refletir o status correto
                                    val currentExec = executionDao.getCurrentExecution()
                                    if (currentExec != null && currentExec.backendId == backendExec.id) {
                                        val updatedExec = currentExec.copy(
                                            status = execStatus,
                                            endTimestamp = backendExec.endTime?.let {
                                                try {
                                                    java.text.SimpleDateFormat(
                                                        "yyyy-MM-dd'T'HH:mm:ss",
                                                        java.util.Locale.getDefault()
                                                    ).parse(it)?.time
                                                } catch (e: Exception) {
                                                    null
                                                }
                                            } ?: currentExec.endTimestamp
                                        )
                                        executionDao.update(updatedExec)
                                    }
                                    
                                    // Não marca como iniciada
                                    withContext(Dispatchers.Main) {
                                        routeStarted = false
                                        applyUiState()
                                    }
                                }
                            } else {
                                // ❌ Backend NÃO tem execução em andamento para esta rota
                                Log.d("StartRoute", "Nenhuma execução no backend para esta rota (routeId=$routeId, assignmentId=$assignmentId)")

                                // Tenta restaurar apenas rota OFFLINE local (backendId == null)
                                val restoredOffline = tryRestoreOfflineExecutionForCurrentRoute()

                                if (!restoredOffline) {
                                    // Nenhuma rota offline válida -> zera estado local
                                    withContext(Dispatchers.Main) {
                                        routeStarted = false
                                        execLocalId = 0L
                                        backendExecutionId = null
                                        applyUiState()
                                    }
                                }
                            }
                        },
                        onFailure = { error ->
                            // Erro ao falar com backend -> cai para modo "offline" usando banco local
                            Log.w("StartRoute", "Erro ao consultar backend: ${error.message}. Usando apenas dados locais.")
                            val restored = tryRestoreLocalExecutionForCurrentRoute()
                            withContext(Dispatchers.Main) {
                                if (restored) {
                                    routeStarted = true
                                } else {
                                    routeStarted = false
                                    execLocalId = 0L
                                    backendExecutionId = null
                                }
                                applyUiState()
                            }
                        }
                    )
                } else {
                    // 🌐 OFFLINE -> usar apenas banco local
                    Log.d(
                        "StartRoute",
                        "checkCurrentExecution() → OFFLINE. isOnline=$isOnline, assignmentId=$assignmentId"
                    )
                    Log.d("StartRoute", "Offline. Usando apenas dados locais.")
                    val restored = tryRestoreLocalExecutionForCurrentRoute()
                    withContext(Dispatchers.Main) {
                        if (restored) {
                            routeStarted = true
                        } else {
                            routeStarted = false
                            execLocalId = 0L
                            backendExecutionId = null
                        }
                        applyUiState()
                    }
                }
            } catch (e: Exception) {
                Log.e("StartRoute", "Erro ao verificar execução atual: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    routeStarted = false
                    execLocalId = 0L
                    backendExecutionId = null
                    applyUiState()
                }
            }
        }
    }

    /**
     * Mostra dialog de iniciar rota (usando novo DialogFragment)
     */
    private fun showStartRouteDialog() {
        if (routeStarted) {
            Toast.makeText(this, "Rota já está em andamento.", Toast.LENGTH_SHORT).show()
            return
        }
        
        // ✅ VERIFICA PERMISSÕES ANTES DE ABRIR O DIALOG
        if (!checkLocationPermissions()) {
            Log.d("StartRoute", "Permissões de localização não concedidas. Solicitando antes de abrir dialog...")
            requestLocationPermissions()
            return
        }
        
        // Verifica se GPS está habilitado
        if (!gpsMonitor.isGpsEnabled()) {
            gpsMonitor.checkAndRequestGps(
                onGpsEnabled = {
                    // GPS foi habilitado, tenta abrir o dialog novamente
                    showStartRouteDialog()
                },
                onGpsDisabled = {
                    // Usuário não habilitou GPS ou cancelou
                    Toast.makeText(this, "GPS precisa estar habilitado para iniciar a rota.", Toast.LENGTH_LONG).show()
                }
            )
            return
        }
        
        // Tenta obter localização atual
        val location = getCurrentLocation() ?: currentLocation
        if (location == null) {
            // Se não tem localização ainda, tenta obter de forma assíncrona
            Log.d("StartRoute", "Localização não disponível imediatamente. Obtendo de forma assíncrona...")
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val newLocation = com.google.android.gms.tasks.Tasks.await(
                        fusedLocationClient.lastLocation
                    )
                    withContext(Dispatchers.Main) {
                        if (newLocation != null) {
                            currentLocation = newLocation
                            openStartRouteDialogWithLocation(newLocation)
                        } else {
                            Toast.makeText(this@StartRoute, "Não foi possível obter localização. Verifique se o GPS está habilitado.", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("StartRoute", "Erro ao obter localização: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@StartRoute, "Erro ao obter localização: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            return
        }
        
        openStartRouteDialogWithLocation(location)
    }
    
    /**
     * Abre o dialog de iniciar rota com a localização fornecida
     */
    private fun openStartRouteDialogWithLocation(location: android.location.Location) {
        val routeName = routeWithPoints?.name ?: "Rota"
        
        val dialog = StartRouteDialog.newInstance(
            assignmentId = assignmentId,
            routeName = routeName,
            location = location
        )
        
        dialog.onRouteStarted = { executionId ->
            // Atualizar backendExecutionId e iniciar rota
            backendExecutionId = executionId
            routeStarted = true
            
            // Reseta flags de movimento ao iniciar nova rota
            isMoving = false
            lastMovingLocation = null
            
            applyUiState()
            
            // Iniciar serviço de rastreamento GPS (permissões já foram verificadas)
            startGpsTracking()
        }
        
        dialog.show(supportFragmentManager, "StartRouteDialog")
    }
    
    /**
     * Inicia a rota (método antigo - mantido para compatibilidade)
     */
    private fun onStartRoute() {
        if (routeStarted) return
        
        // Verifica permissões de localização
        if (!checkLocationPermissions()) {
            requestLocationPermissions()
            return
        }
        
        // Verifica se GPS está habilitado
        if (!gpsMonitor.isGpsEnabled()) {
            gpsMonitor.checkAndRequestGps(
                onGpsEnabled = {
                    // GPS ativado, tenta iniciar novamente
                    onStartRoute()
                },
                onGpsDisabled = {
                    // GPS não ativado
                }
            )
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Verifica periodicity se tiver assignmentId
                if (assignmentId > 0) {
                    val assignmentRepository = br.edu.utfpr.coletapb.data.repository.AssignmentRepository(prefsHelper)
                    val assignmentResult = assignmentRepository.getMyCurrentAssignment()
                    
                    // Tenta buscar o assignment específico se não encontrar no current
                    val assignment = assignmentResult.getOrNull() 
                        ?: assignmentRepository.getMyAssignments().getOrNull()?.find { it.id == assignmentId }
                    
                    if (assignment != null && assignment.periodicity != null) {
                        val isAllowed = PeriodicityUtils.isTodayAllowed(assignment.periodicity)
                        if (!isAllowed) {
                            val allowedDays = PeriodicityUtils.formatPeriodicity(assignment.periodicity)
                            withContext(Dispatchers.Main) {
                                AlertDialog.Builder(this@StartRoute)
                                    .setTitle("Rota não disponível hoje")
                                    .setMessage("Esta rota só pode ser iniciada nos seguintes dias e horários:\n\n$allowedDays\n\nPor favor, aguarde o dia correto para iniciar a rota.")
                                    .setPositiveButton("OK", null)
                                    .show()
                            }
                            return@launch
                        }
                    }
                }

                // Obtém localização atual
                val location = getCurrentLocation()
                val lat = location?.latitude ?: 0.0
                val lng = location?.longitude ?: 0.0
                val now = System.currentTimeMillis()

                // Tenta iniciar no backend primeiro (se online)
                val backendResult = if (syncRepository.isOnline() && assignmentId > 0) {
                    Log.d("StartRoute", "Iniciando execução no backend: assignmentId=$assignmentId")
                    executionRepository.startExecution(assignmentId, lat, lng)
                } else {
                    Log.w("StartRoute", "Não foi possível iniciar no backend: online=${syncRepository.isOnline()}, assignmentId=$assignmentId")
                    Result.failure(Exception("Offline ou assignmentId inválido"))
                }

                val backendExecId = backendResult.getOrNull()?.id
                Log.d("StartRoute", "Backend execution ID obtido: $backendExecId")

                // Cria execução local
                val executionLocal = ExecutionLocal(
                    routeId = routeId,
                    vehicleId = null, // TODO: obter do assignment
                    driverId = prefsHelper.getDriverId().takeIf { it > 0 },
                    startTimestamp = now,
                    startLat = lat,
                    startLng = lng,
                    status = "IN_PROGRESS",
                    backendId = backendExecId
                )

                execLocalId = executionDao.insert(executionLocal)
                backendExecutionId = backendExecId
                Log.d("StartRoute", "Execução local criada: execLocalId=$execLocalId, backendExecutionId=$backendExecutionId")

                // Registra ponto START localmente
                gpsDao.insert(
                    GpsRecordLocal(
                        executionLocalId = execLocalId,
                        timestamp = now,
                        lat = lat,
                        lng = lng,
                        eventType = "START",
                        isOffline = shouldMarkRecordAsOffline()
                    )
                )

                // Envia evento START para o backend se online e tiver backendExecutionId
                if (backendExecutionId != null && syncRepository.isOnline() && location != null) {
                    try {
                        val speedKmh = if (location.hasSpeed()) {
                            location.speed * 3.6
                        } else null
                        
                        val headingDegrees = if (location.hasBearing()) {
                            location.bearing.toInt()
                        } else null
                        
                        val accuracyMeters = if (location.hasAccuracy()) {
                            location.accuracy.toDouble()
                        } else null
                        
                        val startEventResult = gpsRepository.registerGpsPosition(
                            executionId = backendExecutionId!!,
                            latitude = lat,
                            longitude = lng,
                            speedKmh = speedKmh,
                            headingDegrees = headingDegrees?.toDouble(),
                            accuracyMeters = accuracyMeters,
                            eventType = "START",
                            isAutomatic = false,
                            isOffline = false,
                            description = "Início da coleta"
                        )
                        
                        startEventResult.fold(
                            onSuccess = {
                                Log.d("StartRoute", "Evento START enviado ao backend com sucesso")
                            },
                            onFailure = { error ->
                                Log.w("StartRoute", "Erro ao enviar evento START ao backend: ${error.message}. Dados salvos localmente.")
                                // Não bloqueia o fluxo - o evento já foi salvo localmente
                            }
                        )
                    } catch (e: Exception) {
                        Log.e("StartRoute", "Exceção ao enviar evento START: ${e.message}", e)
                        // Não bloqueia o fluxo - o evento já foi salvo localmente
                    }
                } else {
                    Log.d("StartRoute", "Evento START não enviado ao backend: backendExecutionId=$backendExecutionId, online=${syncRepository.isOnline()}, location=${location != null}")
                }

                // Inicia serviço de rastreamento GPS (se tiver permissão)
                if (checkLocationPermissions()) {
                    startGpsTracking()
                }

                withContext(Dispatchers.Main) {
                    routeStarted = true
                    currentLocation = location
                    location?.let { updateCurrentLocationOnMap(it) }
                    
                    // Aplica estado da UI primeiro (mostra botões)
                    applyUiState()
                    
                    // Depois garante que o bottom sheet fica colapsado quando inicia a rota
                    if (::bottomSheetBehavior.isInitialized) {
                        bottomSheetBehavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED
                        // Garante que os botões permanecem visíveis mesmo após colapsar
                        showActionButtons()
                    }
                    Toast.makeText(
                        this@StartRoute,
                        "Rota iniciada! GPS ativo.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@StartRoute,
                        "Erro ao iniciar rota: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
    
    private fun getCurrentLocation(): Location? {
        return try {
            if (!checkLocationPermissions()) {
                Log.w("StartRoute", "Permissões de localização não concedidas")
                // Tenta solicitar permissões se ainda não foram solicitadas
                runOnUiThread {
                    if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                        // Usuário negou antes, mostra explicação
                        AlertDialog.Builder(this)
                            .setTitle("Permissão Necessária")
                            .setMessage("Este aplicativo precisa de permissão de localização para registrar coletas e rastrear a rota.")
                            .setPositiveButton("Conceder") { _, _ ->
                                requestLocationPermissions()
                            }
                            .setNegativeButton("Cancelar", null)
                            .show()
                    } else {
                        // Primeira vez, solicita diretamente
                        requestLocationPermissions()
                    }
                }
                return null
            }
            
            if (!gpsMonitor.isGpsEnabled()) {
                Log.w("StartRoute", "GPS não está habilitado")
                return null
            }
            
            // Tenta obter a última localização conhecida (rápido)
            val lastLocationTask = fusedLocationClient.lastLocation
            val lastLocation = try {
                Tasks.await(lastLocationTask, 2, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                Log.w("StartRoute", "Erro ao obter última localização: ${e.message}")
                null
            }
            
            // Se a última localização é válida e recente (< 1 minuto), usa ela
            if (lastLocation != null && lastLocation.latitude != 0.0 && lastLocation.longitude != 0.0) {
                val age = System.currentTimeMillis() - lastLocation.time
                if (age < 60000) { // Menos de 1 minuto
                    Log.d("StartRoute", "Usando última localização conhecida (idade: ${age}ms)")
                    return lastLocation
                }
            }
            
            // Se não tem localização válida, tenta obter uma nova
            Log.d("StartRoute", "Solicitando nova localização...")
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
                .setMaxUpdateDelayMillis(10000)
                .setWaitForAccurateLocation(false)
                .build()
            
            var newLocation: Location? = null
            val locationLatch = java.util.concurrent.CountDownLatch(1)
            
            val locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val loc = result.lastLocation
                    if (loc != null && loc.latitude != 0.0 && loc.longitude != 0.0) {
                        newLocation = loc
                        fusedLocationClient.removeLocationUpdates(this)
                        locationLatch.countDown()
                    }
                }
            }
            
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, mainLooper)
                
                // Aguarda até 10 segundos por uma localização
                locationLatch.await(10, java.util.concurrent.TimeUnit.SECONDS)
                fusedLocationClient.removeLocationUpdates(locationCallback)
            }
            
            newLocation ?: lastLocation
        } catch (e: Exception) {
            Log.e("StartRoute", "Erro ao obter localização: ${e.message}", e)
            null
        }
    }
    
    private fun startGpsTracking() {
        if (execLocalId <= 0L) {
            Log.w("StartRoute", "Não é possível iniciar GPS tracking: execLocalId inválido")
            return
        }
        
        Log.d("StartRoute", "=== INICIANDO SERVIÇO DE GPS TRACKING ===")
        Log.d("StartRoute", "execLocalId: $execLocalId")
        Log.d("StartRoute", "backendExecutionId: $backendExecutionId")
        
        val intent = Intent(this, GpsTrackingService::class.java).apply {
            action = GpsTrackingService.ACTION_START_TRACKING
            putExtra(GpsTrackingService.EXTRA_EXECUTION_ID, execLocalId)
            backendExecutionId?.let {
                putExtra(GpsTrackingService.EXTRA_BACKEND_EXECUTION_ID, it)
                Log.d("StartRoute", "Backend execution ID passado para o serviço: $it")
            } ?: run {
                Log.w("StartRoute", "⚠️ backendExecutionId é null! O serviço tentará buscar do banco local.")
            }
        }
        
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Log.d("StartRoute", "✅ Serviço de GPS tracking iniciado com sucesso")
            Log.d("StartRoute", "   - execLocalId: $execLocalId")
            Log.d("StartRoute", "   - backendId: $backendExecutionId")
        } catch (e: Exception) {
            Log.e("StartRoute", "❌ Erro ao iniciar serviço de GPS tracking: ${e.message}", e)
        }
    }
    
    private fun stopGpsTracking() {
        val intent = Intent(this, GpsTrackingService::class.java).apply {
            action = GpsTrackingService.ACTION_STOP_TRACKING
        }
        startService(intent)
    }

    private fun onIncident() {
        if (!routeStarted || execLocalId == 0L) {
            Toast.makeText(this, "Inicie a rota primeiro.", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Mostra diálogo para descrição do problema
        val input = android.widget.EditText(this)
        input.hint = "Descreva o problema"
        
        AlertDialog.Builder(this)
            .setTitle("Registrar Problema")
            .setView(input)
            .setPositiveButton("Registrar") { _, _ ->
                val description = input.text.toString()
                registerIncident(description)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    private fun registerIncident(description: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val location = getCurrentLocation()
                val lat = location?.latitude ?: 0.0
                val lng = location?.longitude ?: 0.0
                val now = System.currentTimeMillis()

                // Salva localmente
                gpsDao.insert(
                    GpsRecordLocal(
                        executionLocalId = execLocalId,
                        timestamp = now,
                        lat = lat,
                        lng = lng,
                        eventType = "INCIDENT",
                        isOffline = shouldMarkRecordAsOffline()
                    )
                )

                // Tenta enviar ao backend se online
                if (backendExecutionId != null && syncRepository.isOnline()) {
                    gpsRepository.registerGpsPosition(
                        executionId = backendExecutionId!!,
                        latitude = lat,
                        longitude = lng,
                        eventType = "INCIDENT",
                        description = description,
                        isAutomatic = false
                    )
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@StartRoute,
                        "Problema registrado!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@StartRoute,
                        "Erro ao registrar problema: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
    
    private fun registerGpsEvent(
        eventType: String, 
        description: String? = null, 
        pointId: Long? = null,
        collectedWeightKg: Double? = null,
        pointCondition: String? = null
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                var location = getCurrentLocation()
                
                // Se não conseguiu localização, tenta usar a última conhecida do mapa
                if (location == null || (location.latitude == 0.0 && location.longitude == 0.0)) {
                    Log.w("StartRoute", "getCurrentLocation() retornou null ou 0.0, usando currentLocation do mapa")
                    location = currentLocation
                }
                
                // Se ainda não tem localização válida, não pode registrar
                if (location == null || (location.latitude == 0.0 && location.longitude == 0.0)) {
                    Log.e("StartRoute", "ERRO: Não foi possível obter localização válida!")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@StartRoute,
                            "Erro: Não foi possível obter sua localização. Verifique as permissões de GPS.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }
                
                val lat = location.latitude
                val lng = location.longitude
                val now = System.currentTimeMillis()

                Log.d("StartRoute", "Registrando evento GPS: type=$eventType, execId=$backendExecutionId, lat=$lat, lng=$lng")

                // Salva localmente apenas se tivermos um executionLocalId válido
                if (execLocalId > 0L) {
                    gpsDao.insert(
                        GpsRecordLocal(
                            executionLocalId = execLocalId,
                            timestamp = now,
                            lat = lat,
                            lng = lng,
                            eventType = eventType,
                            isOffline = shouldMarkRecordAsOffline()
                        )
                    )
                    Log.d("StartRoute", "GPS salvo localmente com sucesso")
                } else {
                    Log.w("StartRoute", "execLocalId inválido: $execLocalId")
                }

                // Tenta enviar ao backend se online
                if (backendExecutionId != null) {
                    // Verifica se está online de forma mais robusta
                    val isOnline = try {
                        syncRepository.isOnline()
                    } catch (e: Exception) {
                        Log.w("StartRoute", "Erro ao verificar conexão: ${e.message}")
                        true // Assume online se não conseguir verificar
                    }
                    
                    if (isOnline) {
                        Log.d("StartRoute", "Enviando GPS ao backend: executionId=$backendExecutionId, lat=$lat, lng=$lng")
                        val result = gpsRepository.registerGpsPosition(
                            executionId = backendExecutionId!!,
                            latitude = lat,
                            longitude = lng,
                            eventType = eventType,
                            description = description,
                            pointId = pointId,
                            collectedWeightKg = collectedWeightKg,
                            pointCondition = pointCondition,
                            isAutomatic = false
                        )
                        
                        result.fold(
                            onSuccess = { record ->
                                Log.d("StartRoute", "GPS registrado no backend com sucesso: id=${record.id}")
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        this@StartRoute,
                                        "Evento $eventType registrado!",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            onFailure = { error ->
                                Log.e("StartRoute", "Erro ao registrar GPS no backend: ${error.message}", error)
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        this@StartRoute,
                                        "Erro ao enviar ao servidor: ${error.message}. Dados salvos localmente.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        )
                    } else {
                        Log.d("StartRoute", "Dispositivo offline, GPS salvo apenas localmente")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@StartRoute,
                                "Evento $eventType registrado (offline)!",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } else {
                    Log.w("StartRoute", "backendExecutionId é null, GPS salvo apenas localmente")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@StartRoute,
                            "Evento $eventType registrado localmente (aguardando sincronização)!",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("StartRoute", "Exceção ao registrar evento GPS: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@StartRoute,
                        "Erro ao registrar evento: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * Mostra dialog de encerrar rota (usando novo DialogFragment)
     */
    private fun showEndRouteDialog() {
        Log.d("StartRoute", "showEndRouteDialog() chamado - routeStarted=$routeStarted, execLocalId=$execLocalId, backendExecutionId=$backendExecutionId")
        
        if (!routeStarted || execLocalId == 0L) {
            Log.w("StartRoute", "Não há rota em andamento: routeStarted=$routeStarted, execLocalId=$execLocalId")
            Toast.makeText(this, "Não há rota em andamento para encerrar.", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Se tem backendExecutionId, usa o dialog novo (EndRouteDialog)
        if (backendExecutionId != null) {
            Log.d("StartRoute", "Usando EndRouteDialog para rota com backendExecutionId=$backendExecutionId")
            
            // Usa currentLocation que já está sendo atualizada em background
            // Não chama getCurrentLocation() na main thread para evitar ANR
            val location = currentLocation
            if (location == null) {
                Log.w("StartRoute", "Localização não disponível para encerrar rota")
                Toast.makeText(this, "Localização não disponível. Aguarde alguns segundos e tente novamente.", Toast.LENGTH_LONG).show()
                return
            }
            
            val dialog = EndRouteDialog.newInstance(
                executionId = backendExecutionId!!,
                location = location
            )
            
            dialog.onRouteEnded = {
                // Navegar após encerrar (já implementado em finishRoute)
                Log.d("StartRoute", "Rota encerrada via EndRouteDialog, navegando...")
                navigateAfterFinish()
            }
            
            dialog.show(supportFragmentManager, "EndRouteDialog")
        } else {
            // Se não tem backendExecutionId (rota offline), usa o método finishRoute() direto
            // Mostra dialog de confirmação simples
            Log.d("StartRoute", "Rota offline detectada (sem backendExecutionId), usando finishRoute() direto")
            AlertDialog.Builder(this)
                .setTitle("Encerrar Rota")
                .setMessage("Deseja realmente encerrar esta rota?")
                .setPositiveButton("Sim") { _, _ ->
                    Log.d("StartRoute", "Usuário confirmou encerramento de rota offline")
                    finishRoute()
                }
                .setNegativeButton("Não", null)
                .show()
        }
    }
    
    /**
     * Mostra dialog de confirmar encerramento da rota (método antigo - mantido para compatibilidade)
     */
    private fun showConfirmFinishDialog() {
        if (!routeStarted || (execLocalId == 0L && backendExecutionId == null)) {
            Toast.makeText(this, "Não há rota em andamento para encerrar.", Toast.LENGTH_SHORT).show()
            return
        }
        
        val route = routeWithPoints
        val dialogView = layoutInflater.inflate(R.layout.dialog_confirm_finish, null)
        
        val tvRouteName = dialogView.findViewById<TextView>(R.id.tvRouteName)
        val tvSummary = dialogView.findViewById<TextView>(R.id.tvSummary)
        val etFinalNotes = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etFinalNotes)
        
        tvRouteName.text = "Você deseja encerrar a rota \"${route?.name ?: "Rota"}\"?"
        
        // Calcula resumo parcial (pode ser melhorado com dados reais)
        val pointsVisited = route?.collectionPoints?.count { it.status != PointStatus.PENDING } ?: 0
        val totalPoints = route?.collectionPoints?.size ?: 0
        val problems = route?.collectionPoints?.count { it.status == PointStatus.PROBLEM } ?: 0
        
        // TODO: Calcular duração real
        tvSummary.text = "• Pontos visitados: $pointsVisited / $totalPoints\n" +
                "• Problemas: $problems\n" +
                "• Duração: Calculando..."
        
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        
        dialogView.findViewById<Button>(R.id.btCancelFinish).setOnClickListener {
            dialog.dismiss()
        }
        
        dialogView.findViewById<Button>(R.id.btConfirmFinish).setOnClickListener {
            val notes = etFinalNotes.text.toString().trim()
            finishRoute(notes)
            dialog.dismiss()
        }
        
        dialog.show()
    }
    
    private fun onFinishRoute() {
        showConfirmFinishDialog()
    }
    
    private fun finishRoute(finalNotes: String? = null) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Para o rastreamento GPS
                stopGpsTracking()

                val location = getCurrentLocation()
                val lat = location?.latitude ?: 0.0
                val lng = location?.longitude ?: 0.0
                val now = System.currentTimeMillis()

                // Registra ponto END
                gpsDao.insert(
                    GpsRecordLocal(
                        executionLocalId = execLocalId,
                        timestamp = now,
                        lat = lat,
                        lng = lng,
                        eventType = "END",
                        isOffline = shouldMarkRecordAsOffline()
                    )
                )

                // Atualiza execução local
                executionDao.getById(execLocalId)?.let { exec ->
                    val updatedExec = exec.copy(
                        endTimestamp = now,
                        endLat = lat,
                        endLng = lng,
                        status = "COMPLETED"
                    )
                    executionDao.update(updatedExec)

                    // Tenta finalizar no backend se online
                    if (backendExecutionId != null && syncRepository.isOnline()) {
                        val completeResult = executionRepository.completeExecution(
                            executionId = backendExecutionId!!,
                            endLat = lat,
                            endLng = lng,
                            notes = finalNotes
                        )
                        
                        // Se falhar, apenas loga o erro mas continua (já salvou localmente)
                        completeResult.onFailure { error ->
                            Log.w("StartRoute", "Erro ao finalizar execução no backend: ${error.message}")
                            // Não bloqueia o fluxo - a execução já foi salva localmente
                        }
                    }
                }

                // Sincroniza dados pendentes
                val syncResult = syncRepository.syncPendingData()

                // Monta resumo
                val (startStr, endStr, incidents) = buildSummary(execLocalId)

                withContext(Dispatchers.Main) {
                    routeStarted = false
                    applyUiState()

                    val msg = buildString {
                        append("Rota finalizada!\n")
                        append("Início: $startStr\n")
                        append("Fim: $endStr\n")
                        append("Imprevistos: ${incidents.count}")
                        if (syncResult.syncedGpsRecords > 0) {
                            append("\nSincronizados: ${syncResult.syncedGpsRecords} pontos GPS")
                        }
                    }

                    Toast.makeText(this@StartRoute, msg, Toast.LENGTH_SHORT).show()
                    
                    // Navega para a tela de resumo da execução ou lista de assignments
                    navigateAfterFinish()
                }
            } catch (e: Exception) {
                Log.e("StartRoute", "Erro ao finalizar rota: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    // Mesmo com erro, tenta navegar (dados já foram salvos localmente)
                    Toast.makeText(
                        this@StartRoute,
                        "Rota finalizada localmente. ${if (e.message != null) "Erro ao sincronizar: ${e.message}" else ""}",
                        Toast.LENGTH_LONG
                    ).show()
                    navigateAfterFinish()
                }
            }
        }
    }
    
    /**
     * Navega para a tela apropriada após finalizar a rota
     */
    private fun navigateAfterFinish() {
        try {
            if (backendExecutionId != null) {
                // Navega para resumo da execução
                val intent = Intent(this, ExecutionSummaryActivity::class.java).apply {
                    putExtra("execution_id", backendExecutionId)
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            } else {
                // Se não tem backendId, volta para lista de assignments
                val intent = Intent(this, AssignmentListActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            }
            finish()
        } catch (e: Exception) {
            Log.e("StartRoute", "Erro ao navegar após finalizar: ${e.message}", e)
            // Se falhar, pelo menos fecha a tela atual
            finish()
        }
    }

    // Resumo para o Toast final (sem "paradas")
    private suspend fun buildSummary(id: Long): Triple<String, String, IncidentInfo> {
        val exec = executionDao.getById(id)
        val startStr = exec?.startTimestamp?.let { sdf.format(Date(it)) } ?: "-"
        val endStr   = exec?.endTimestamp?.let { sdf.format(Date(it)) } ?: "-"

        val incidents = gpsDao.listByExecution(id).filter { it.eventType == "INCIDENT" }
        val times = incidents.joinToString("\n") { " - ${sdf.format(Date(it.timestamp))}" }

        return Triple(startStr, endStr, IncidentInfo(incidents.size, times))
    }

    data class IncidentInfo(val count: Int, val times: String)

    private fun loadRoutePoints() {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = routeRepository.getRouteWithPoints(routeId)
            result.onSuccess { route ->
                routeWithPoints = route
                withContext(Dispatchers.Main) {
                    // Atualiza título da toolbar com o nome da rota
                    val toolbar = findViewById<MaterialToolbar>(R.id.topAppBar)
                    toolbar.title = route.name ?: routeName ?: "Iniciar rota"
                    
                    // Desenha no mapa
                    drawRouteOnMap(route)
                    updateNextPointInfo()
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@StartRoute, "Erro ao carregar pontos: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        
        // Carrega os polígonos da rota
        loadRoutePolygons()
    }
    
    private fun loadRoutePolygons() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = routeRepository.getRouteMap(routeId)
                result.onSuccess { data ->
                    val geojson = data["geojson"] as? Map<String, Any>
                    if (geojson != null) {
                        val features = geojson["features"] as? List<Map<String, Any>>
                        if (features != null && features.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                drawPolygonsOnMap(features)
                            }
                        }
                    }
                }.onFailure { e ->
                    Log.w("StartRoute", "Erro ao carregar polígonos da rota: ${e.message}")
                    // Não mostra erro ao usuário, pois polígonos são opcionais
                }
            } catch (e: Exception) {
                Log.e("StartRoute", "Exceção ao carregar polígonos: ${e.message}", e)
            }
        }
    }
    
    private fun drawPolygonsOnMap(features: List<Map<String, Any>>) {
        // Limpa polígonos anteriores
        routePolygons.forEach { mapView.overlays.remove(it) }
        routePolygons.clear()
        
        features.forEach { feature ->
            try {
                val geometry = feature["geometry"] as? Map<String, Any>
                val properties = feature["properties"] as? Map<String, Any>
                
                if (geometry != null && geometry["type"] == "Polygon") {
                    val coordinates = geometry["coordinates"] as? List<List<List<Double>>>
                    
                    if (coordinates != null && coordinates.isNotEmpty()) {
                        // Pega o primeiro anel do polígono (exterior ring)
                        val ring = coordinates[0]
                        
                        // Converte coordenadas para GeoPoints
                        val geoPoints = ring.mapNotNull { coord ->
                            if (coord.size >= 2) {
                                // GeoJSON usa [longitude, latitude], OSMDroid usa [latitude, longitude]
                                GeoPoint(coord[1], coord[0])
                            } else {
                                null
                            }
                        }
                        
                        if (geoPoints.size >= 3) {
                            // Cria o polígono
                            val polygon = Polygon(mapView)
                            polygon.points = geoPoints
                            
                            // Aplica estilos do GeoJSON
                            val strokeColor = parseColor(properties?.get("stroke_color") as? String ?: properties?.get("strokeColor") as? String ?: "#0066CC")
                            val fillColor = parseColor(properties?.get("fill_color") as? String ?: properties?.get("fillColor") as? String ?: "#0066CC")
                            val fillOpacity = (properties?.get("fill_opacity") as? Number ?: properties?.get("fillOpacity") as? Number)?.toFloat() ?: 0.4f
                            
                            polygon.strokeColor = strokeColor
                            polygon.fillColor = fillColor
                            polygon.strokeWidth = 2f
                            
                            // Aplica opacidade ao fillColor
                            val alpha = (fillOpacity * 255).toInt()
                            polygon.fillColor = (alpha shl 24) or (fillColor and 0x00FFFFFF)
                            
                            mapView.overlays.add(polygon)
                            routePolygons.add(polygon)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("StartRoute", "Erro ao desenhar polígono: ${e.message}", e)
            }
        }
        
        mapView.invalidate()
    }
    
    private fun parseColor(colorString: String): Int {
        return try {
            if (colorString.startsWith("#")) {
                android.graphics.Color.parseColor(colorString)
            } else {
                android.graphics.Color.parseColor("#$colorString")
            }
        } catch (e: Exception) {
            android.graphics.Color.parseColor("#0066CC") // Cor padrão azul
        }
    }
    
    private fun drawRouteOnMap(route: RouteWithPoints, shouldCenterMap: Boolean = true) {
        // Salva a posição e zoom atual do mapa antes de atualizar
        val currentCenter = mapView.mapCenter
        val currentZoom = mapView.zoomLevelDouble
        
        // Limpa marcadores anteriores
        pointMarkers.forEach { mapView.overlays.remove(it) }
        pointMarkers.clear()
        routePolyline?.let { mapView.overlays.remove(it) }
        // Nota: não limpa os polígonos aqui, eles são gerenciados separadamente
        
        if (route.collectionPoints.isEmpty()) {
            Toast.makeText(this, "Nenhum ponto de coleta encontrado nesta rota.", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Cria marcadores para cada ponto
        val points = route.collectionPoints
        val geoPoints = mutableListOf<GeoPoint>()
        
        points.forEachIndexed { index, point ->
            val geoPoint = GeoPoint(point.latitude, point.longitude)
            geoPoints.add(geoPoint)
            
            val marker = Marker(mapView)
            marker.position = geoPoint
            marker.title = "Ponto ${point.sequenceOrder}"
            marker.snippet = point.address
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            
            // Próximo ponto em destaque (laranja), outros por status
            if (index == currentPointIndex) {
                // Ponto atual - laranja (usando ícone padrão do OSMDroid com cor)
                marker.icon = resources.getDrawable(android.R.drawable.ic_menu_mylocation, theme)
            } else {
                when (point.status) {
                    PointStatus.COLLECTED -> {
                        // Coletado - verde
                        marker.icon = resources.getDrawable(android.R.drawable.checkbox_on_background, theme)
                    }
                    PointStatus.PROBLEM -> {
                        // Problema - vermelho
                        marker.icon = resources.getDrawable(android.R.drawable.ic_dialog_alert, theme)
                    }
                    else -> {
                        // Pendente - azul
                        marker.icon = resources.getDrawable(android.R.drawable.ic_menu_mylocation, theme)
                    }
                }
            }
            
            mapView.overlays.add(marker)
            pointMarkers.add(marker)
        }
        
        // Desenha linha da rota
        if (geoPoints.size > 1) {
            routePolyline = Polyline(mapView)
            routePolyline?.setPoints(geoPoints)
            routePolyline?.color = android.graphics.Color.BLUE
            routePolyline?.width = 8f
            routePolyline?.let { mapView.overlays.add(it) }
        }
        
        // Só centraliza se shouldCenterMap for true (evita perder a visualização atual)
        if (shouldCenterMap) {
            // Centraliza no próximo ponto
            if (currentPointIndex < points.size) {
                val nextPoint = points[currentPointIndex]
                val geoPoint = GeoPoint(nextPoint.latitude, nextPoint.longitude)
                mapView.controller.setCenter(geoPoint)
                mapView.controller.setZoom(14.0)
            } else if (geoPoints.isNotEmpty()) {
                // Se não há próximo ponto, centraliza na rota inteira
                val bounds = org.osmdroid.util.BoundingBox(
                    geoPoints.maxOf { it.latitude },
                    geoPoints.maxOf { it.longitude },
                    geoPoints.minOf { it.latitude },
                    geoPoints.minOf { it.longitude }
                )
                mapView.zoomToBoundingBox(bounds, true, 100)
            }
        } else {
            // Restaura a posição e zoom anteriores para manter a visualização do usuário
            mapView.controller.setCenter(currentCenter)
            mapView.controller.setZoom(currentZoom)
        }
        
        mapView.invalidate()
    }
    
    private fun updateNextPointInfo() {
        val route = routeWithPoints ?: return
        val isContainerRoute = route.collectionType.equals("CONTAINER", ignoreCase = true)
        val points = route.collectionPoints
        
        if (!routeStarted) {
            // Rota não iniciada - esconde tudo
            tvProgressCounter.visibility = View.GONE
            llNonContainerInfo.visibility = View.GONE
            llAllCollected.visibility = View.GONE
            rvNextPoints.visibility = View.GONE
            return
        }
        
        // Atualiza contador de progresso para rotas CONTAINER
        if (isContainerRoute) {
            if (points.isEmpty()) {
                tvProgressCounter.visibility = View.GONE
                llAllCollected.visibility = View.GONE
                rvNextPoints.visibility = View.GONE
                return
            }
            
            // Calcula pontos coletados (status == COLLECTED)
            val collectedCount = points.count { it.status == PointStatus.COLLECTED }
            val totalPoints = points.size
            
            // Atualiza contador
            tvProgressCounter.text = "$collectedCount/$totalPoints pontos coletados"
            tvProgressCounter.visibility = View.VISIBLE
            llNonContainerInfo.visibility = View.GONE
            
            // Verifica se todos foram coletados
            if (collectedCount >= totalPoints) {
                // Estado: todos coletados
                llAllCollected.visibility = View.VISIBLE
                rvNextPoints.visibility = View.GONE
                
                // Esconde botões normais, mostra apenas "Encerrar rota"
                btRegisterCollection.visibility = View.GONE
                llSecondaryButtons.visibility = View.GONE
                btRegisterProblem.visibility = View.GONE // Esconde botão de problema também
                btRegisterStop.visibility = View.GONE
                btCancelRoute.visibility = View.GONE
                btFinishRoute.visibility = View.VISIBLE
                
                return
            } else {
                // Ainda há pontos pendentes
                llAllCollected.visibility = View.GONE
                rvNextPoints.visibility = View.VISIBLE
                
                // Mostra todos os botões normalmente
                btRegisterCollection.visibility = View.VISIBLE
                llSecondaryButtons.visibility = View.VISIBLE
                btRegisterProblem.visibility = View.VISIBLE
                btRegisterStop.visibility = View.VISIBLE
                btCancelRoute.visibility = View.VISIBLE
                btFinishRoute.visibility = View.VISIBLE
            }
        } else {
            // Rota não-CONTAINER
            tvProgressCounter.visibility = View.GONE
            llAllCollected.visibility = View.GONE
            llNonContainerInfo.visibility = View.VISIBLE
            rvNextPoints.visibility = View.GONE // Não há pontos pré-cadastrados para mostrar
            
            // Mostra todos os botões normalmente
            btRegisterCollection.visibility = View.VISIBLE
            llSecondaryButtons.visibility = View.VISIBLE
            btRegisterProblem.visibility = View.VISIBLE
            btRegisterStop.visibility = View.VISIBLE
            btCancelRoute.visibility = View.VISIBLE
            btFinishRoute.visibility = View.VISIBLE
        }
        
        // Cria lista dos próximos 2-3 pontos (apenas para rotas CONTAINER com pontos pendentes)
        if (!isContainerRoute) {
            return
        }
        
        val collectedCount = points.count { it.status == PointStatus.COLLECTED }
        val totalPoints = points.size
        
        if (collectedCount >= totalPoints) {
            return
        }
        
        val nextPoints = mutableListOf<br.edu.utfpr.coletapb.adapter.NextPointItem>()
        
        // Encontra o próximo ponto não coletado
        val nextPendingPointIndex = points.indexOfFirst { it.status != PointStatus.COLLECTED }
        if (nextPendingPointIndex >= 0 && nextPendingPointIndex < points.size) {
            val nextPoint = points[nextPendingPointIndex]
            val distanceText = currentLocation?.let { loc ->
                val distance = FloatArray(1)
                Location.distanceBetween(
                    loc.latitude, loc.longitude,
                    nextPoint.latitude, nextPoint.longitude,
                    distance
                )
                val distanceMeters = distance[0].toInt()
                if (distanceMeters < 1000) {
                    "Em $distanceMeters m"
                } else {
                    "Em ${String.format(Locale.getDefault(), "%.1f", distanceMeters / 1000f)} km"
                }
            } ?: null
            
            nextPoints.add(
                br.edu.utfpr.coletapb.adapter.NextPointItem(
                    point = nextPoint,
                    distance = distanceText,
                    status = if (nextPendingPointIndex == 0) "Recomendado" else "Próximo"
                )
            )
            
            // Segundo e terceiro pontos (se existirem e não estiverem coletados)
            var addedCount = 1
            for (i in (nextPendingPointIndex + 1) until points.size) {
                if (addedCount >= 3) break
                val point = points[i]
                if (point.status != PointStatus.COLLECTED) {
                    val distanceText2 = currentLocation?.let { loc ->
                        val distance = FloatArray(1)
                        Location.distanceBetween(
                            loc.latitude, loc.longitude,
                            point.latitude, point.longitude,
                            distance
                        )
                        val distanceMeters = distance[0].toInt()
                        if (distanceMeters < 1000) {
                            "Em $distanceMeters m"
                        } else {
                            "Em ${String.format(Locale.getDefault(), "%.1f", distanceMeters / 1000f)} km"
                        }
                    } ?: null
                    
                    nextPoints.add(
                        br.edu.utfpr.coletapb.adapter.NextPointItem(
                            point = point,
                            distance = distanceText2,
                            status = "Próximo"
                        )
                    )
                    addedCount++
                }
            }
        }
        
        // Atualiza adapter
        if (::nextPointAdapter.isInitialized) {
            // Se já existe, atualiza a lista
            nextPointAdapter = br.edu.utfpr.coletapb.adapter.NextPointAdapter(nextPoints)
            rvNextPoints.adapter = nextPointAdapter
        } else {
            // Primeira vez, cria o adapter
            nextPointAdapter = br.edu.utfpr.coletapb.adapter.NextPointAdapter(nextPoints)
            rvNextPoints.adapter = nextPointAdapter
        }
    }
    
    private fun centerMapOnCurrentLocation() {
        currentLocation?.let { loc ->
            val geoPoint = GeoPoint(loc.latitude, loc.longitude)
            // Não força zoom toda vez; só ajusta se estiver muito longe
            if (mapView.zoomLevelDouble < 15.0) {
                mapView.controller.setZoom(16.0)
            }
            mapView.controller.animateTo(geoPoint)
        } ?: run {
            Toast.makeText(this, "Localização não disponível", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun updateCurrentLocationOnMap(location: Location) {
        currentLocation = location
        
        // Detecta movimento real (filtra ruído GPS)
        val isMovingNow = detectRealMovement(location)
        
        // Atualiza o marker customizado apenas se houver movimento real
        // Isso evita "tremedeira" do marcador quando parado
        if (isMovingNow) {
            updateLocationMarkerPosition(location)
        } else {
            // Parado: não atualiza posição do marcador para evitar ruído visual
            // Mas atualiza currentLocation para uso em outras funcionalidades
            Log.d("StartRoute", "Parado detectado, não atualizando marcador visual (ruído GPS filtrado)")
        }
        
        // Atualiza informações do próximo ponto com throttling (evita processamento excessivo)
        val now = System.currentTimeMillis()
        if (now - lastNextPointInfoUpdate >= NEXT_POINT_INFO_UPDATE_INTERVAL) {
            updateNextPointInfo()
            lastNextPointInfoUpdate = now
        }
    }
    
    /**
     * Detecta se há movimento real (filtra ruído GPS de 1-2m)
     * Considera movimento apenas se:
     * - Distância desde última posição de movimento >= MIN_MOVEMENT_DISTANCE_METERS (10m)
     * - E velocidade >= MIN_MOVEMENT_SPEED_MS (1 m/s) se disponível
     */
    private fun detectRealMovement(newLocation: Location): Boolean {
        // Primeira localização sempre considera como movimento (para inicializar)
        if (lastMovingLocation == null) {
            lastMovingLocation = newLocation
            isMoving = true
            Log.d("StartRoute", "✅ Primeira localização, inicializando detecção de movimento")
            return true
        }
        
        // Calcula distância desde a última posição de movimento real
        val distance = lastMovingLocation!!.distanceTo(newLocation)
        
        // Verifica velocidade se disponível
        val hasValidSpeed = newLocation.hasSpeed() && newLocation.speed >= MIN_MOVEMENT_SPEED_MS
        
        // Considera movimento real se:
        // 1. Moveu pelo menos MIN_MOVEMENT_DISTANCE_METERS (10m) desde última posição de movimento
        // 2. E (tem velocidade válida >= 1 m/s OU distância é significativamente maior que o ruído)
        val isMovingNow = if (distance >= MIN_MOVEMENT_DISTANCE_METERS) {
            // Se moveu 10m ou mais, verifica velocidade para confirmar
            if (hasValidSpeed) {
                true // Tem velocidade e distância suficiente
            } else {
                // Sem velocidade, mas moveu bastante (pode ser GPS impreciso mas movimento real)
                // Considera movimento se moveu mais que 2x o mínimo (20m)
                distance >= MIN_MOVEMENT_DISTANCE_METERS * 2
            }
        } else {
            // Moveu menos de 10m - provavelmente ruído GPS
            false
        }
        
        // Atualiza flag de movimento
        val wasMoving = isMoving
        isMoving = isMovingNow
        
        if (isMovingNow) {
            if (!wasMoving) {
                Log.d("StartRoute", "🚗 Movimento detectado: distância=${String.format("%.1f", distance)}m, velocidade=${if (newLocation.hasSpeed()) String.format("%.1f", newLocation.speed * 3.6) + "km/h" else "N/A"}")
            }
            // Atualiza última posição de movimento apenas quando realmente moveu
            lastMovingLocation = newLocation
        } else {
            if (wasMoving) {
                Log.d("StartRoute", "🛑 Parado detectado: distância=${String.format("%.1f", distance)}m (ruído GPS, < ${MIN_MOVEMENT_DISTANCE_METERS}m)")
            }
        }
        
        return isMovingNow
    }
    
    /**
     * Mostra dialog de registrar coleta (usando novo DialogFragment)
     */
    private fun showCollectionDialog() {
        if (!routeStarted || backendExecutionId == null) {
            Toast.makeText(this, "Inicie a rota primeiro.", Toast.LENGTH_SHORT).show()
            return
        }
        
        val route = routeWithPoints ?: return
        
        // Verifica se é rota do tipo CONTAINER
        val isContainerRoute = route.collectionType.equals("CONTAINER", ignoreCase = true)
        
        if (isContainerRoute) {
            // Rota CONTAINER: mostra seletor de pontos
            showPointSelectorDialog(route)
        } else {
            // Rota não-CONTAINER: registra na localização atual (sem selecionar ponto)
            registerCollectionAtCurrentLocation(route)
        }
    }
    
    /**
     * Mostra dialog para selecionar um ponto de coleta (apenas para rotas CONTAINER)
     */
    private fun showPointSelectorDialog(route: RouteWithPoints) {
        val activePoints = route.collectionPoints.filter { it.active }
        
        if (activePoints.isEmpty()) {
            Toast.makeText(this, "Não há pontos de coleta disponíveis nesta rota.", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Cria lista de nomes dos pontos para o dialog
        val pointNames = activePoints.mapIndexed { index, point ->
            "Ponto ${point.sequenceOrder} – ${point.address}"
        }.toTypedArray()
        
        // Mostra AlertDialog com lista de pontos
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Selecione o ponto de coleta")
            .setItems(pointNames) { _, which ->
                val selectedPoint = activePoints[which]
                // Usa currentLocation diretamente (já está sendo atualizado pelo GPS monitor)
                val location = currentLocation
                
                if (location == null) {
                    Toast.makeText(this, "Aguardando localização GPS...", Toast.LENGTH_SHORT).show()
                    // Tenta obter localização de forma assíncrona e abrir o dialog depois
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val newLocation = getCurrentLocation()
                            withContext(Dispatchers.Main) {
                                if (newLocation != null) {
                                    // Abre o dialog com a localização obtida
                                    openCollectionDialogWithLocation(route, selectedPoint, newLocation)
                                } else {
                                    Toast.makeText(this@StartRoute, "Localização não disponível.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("StartRoute", "Não foi possível obter localização: ${e.message}")
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@StartRoute, "Erro ao obter localização.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else {
                    // Abre o dialog imediatamente com a localização já disponível
                    openCollectionDialogWithLocation(route, selectedPoint, location)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    /**
     * Registra coleta na localização atual (para rotas não-CONTAINER)
     */
    private fun registerCollectionAtCurrentLocation(route: RouteWithPoints) {
        // Usa currentLocation diretamente (já está sendo atualizado pelo GPS monitor)
        val location = currentLocation
        
        if (location == null) {
            Toast.makeText(this, "Aguardando localização GPS...", Toast.LENGTH_SHORT).show()
            // Tenta obter localização de forma assíncrona e abrir o dialog depois
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val newLocation = getCurrentLocation()
                    withContext(Dispatchers.Main) {
                        if (newLocation != null) {
                            // Abre o dialog com a localização atual (sem ponto específico)
                            openCollectionDialogWithCurrentLocation(route, newLocation)
                        } else {
                            Toast.makeText(this@StartRoute, "Localização não disponível.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.w("StartRoute", "Não foi possível obter localização: ${e.message}")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@StartRoute, "Erro ao obter localização.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            return
        }
        
        // Abre o dialog imediatamente com a localização atual (sem ponto específico)
        openCollectionDialogWithCurrentLocation(route, location)
    }
    
    /**
     * Abre o dialog de registro de coleta com a localização fornecida (para rotas CONTAINER com ponto específico)
     */
    private fun openCollectionDialogWithLocation(route: RouteWithPoints, point: CollectionPoint, location: Location) {
        val dialog = RegisterCollectionDialog.newInstance(
            executionId = backendExecutionId!!,
            pointId = point.id,
            pointName = "Ponto ${point.sequenceOrder} – ${point.address}",
            location = location
        )
        
        dialog.onCollectionSaved = { recordId ->
            // Atualizar UI após coleta salva
            point.status = PointStatus.COLLECTED
            // Atualiza o índice do ponto atual se for o próximo ponto
            if (route.collectionPoints.indexOf(point) == currentPointIndex) {
                currentPointIndex++
            }
            updateNextPointInfo()
            // Não centraliza o mapa para não perder a visualização atual do usuário
            drawRouteOnMap(route, shouldCenterMap = false)
        }
        
        dialog.show(supportFragmentManager, "RegisterCollectionDialog")
    }
    
    /**
     * Abre o dialog de registro de coleta na localização atual (para rotas não-CONTAINER)
     */
    private fun openCollectionDialogWithCurrentLocation(route: RouteWithPoints, location: Location) {
        val dialog = RegisterCollectionDialog.newInstance(
            executionId = backendExecutionId!!,
            pointId = null, // Sem ponto específico para rotas não-CONTAINER
            pointName = null,
            location = location
        )
        
        dialog.onCollectionSaved = { recordId ->
            // Atualizar UI após coleta salva (não precisa atualizar pontos, pois não há pontos específicos)
            // Não centraliza o mapa para não perder a visualização atual do usuário
            drawRouteOnMap(route, shouldCenterMap = false)
        }
        
        dialog.show(supportFragmentManager, "RegisterCollectionDialog")
    }
    
    /**
     * Mostra dialog de confirmar coleta (método antigo - mantido para compatibilidade)
     */
    private fun showConfirmCollectionDialog() {
        val route = routeWithPoints ?: return
        if (currentPointIndex >= route.collectionPoints.size) {
            Toast.makeText(this, "Todos os pontos já foram coletados.", Toast.LENGTH_SHORT).show()
            return
        }
        
        val point = route.collectionPoints[currentPointIndex]
        val dialogView = layoutInflater.inflate(R.layout.dialog_confirm_collection, null)
        
        val tvAddress = dialogView.findViewById<TextView>(R.id.tvCollectionPointAddress)
        tvAddress.text = "Você coletou no ponto ${point.sequenceOrder} – ${point.address}?"
        
        val etWeight = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etWeight)
        val etPointCondition = dialogView.findViewById<AutoCompleteTextView>(R.id.etPointCondition)
        val conditionOptions = arrayOf("NORMAL", "SATURATED", "DAMAGED", "INACCESSIBLE")
        val conditionAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, conditionOptions)
        etPointCondition.setAdapter(conditionAdapter)

        val etObservation = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etObservation)
        
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        
        dialogView.findViewById<Button>(R.id.btCancel).setOnClickListener {
            dialog.dismiss()
        }
        
        dialogView.findViewById<Button>(R.id.btConfirm).setOnClickListener {
            val weightText = etWeight.text.toString().trim()
            val weight = if (weightText.isNotEmpty()) {
                try {
                    weightText.toDouble()
                } catch (e: NumberFormatException) {
                    null
                }
            } else null
            
            val condition = etPointCondition.text.toString().trim().takeIf { it.isNotEmpty() }
            val observation = etObservation.text.toString().trim().takeIf { it.isNotEmpty() }
            
            confirmCollection(point, weight, condition, observation)
            dialog.dismiss()
        }
        
        dialog.show()
    }
    
    private fun confirmCollection(point: CollectionPoint, weightKg: Double? = null, condition: String? = null, observation: String? = null) {
        // Verifica GPS antes de registrar coleta
        if (!gpsMonitor.isGpsEnabled()) {
            gpsMonitor.showGpsDisabledWarning()
            return
        }
        
        // Verifica permissões antes de registrar coleta
        if (!checkLocationPermissions()) {
            Toast.makeText(this, "Permissão de localização necessária para registrar coleta.", Toast.LENGTH_LONG).show()
            requestLocationPermissions()
            return
        }
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d("StartRoute", "=== Confirmando coleta ===")
                Log.d("StartRoute", "point.id: ${point.id}")
                Log.d("StartRoute", "backendExecutionId: $backendExecutionId")
                Log.d("StartRoute", "execLocalId: $execLocalId")
                
                if (backendExecutionId == null) {
                    Log.w("StartRoute", "ATENÇÃO: backendExecutionId é null! Tentando sincronizar...")
                    // Tenta buscar do backend novamente
                    if (syncRepository.isOnline() && assignmentId > 0) {
                        val backendExecResult = executionRepository.getMyCurrentExecution()
                        backendExecResult.fold(
                            onSuccess = { backendExec ->
                                if (backendExec != null && backendExec.assignmentId == assignmentId) {
                                    backendExecutionId = backendExec.id
                                    Log.d("StartRoute", "backendExecutionId sincronizado: $backendExecutionId")
                                    // Atualiza execução local
                                    if (execLocalId > 0L) {
                                        executionDao.getById(execLocalId)?.let { exec ->
                                            val updatedExec = exec.copy(backendId = backendExec.id)
                                            executionDao.update(updatedExec)
                                        }
                                    }
                                }
                            },
                            onFailure = { error ->
                                Log.e("StartRoute", "Erro ao sincronizar backendExecutionId: ${error.message}")
                            }
                        )
                    }
                }
                
                // Monta descrição com informações adicionais
                val fullDescription = buildString {
                    observation?.let { append(it) }
                    if (condition != null) {
                        if (isNotEmpty()) append(" | ")
                        append("Condição: $condition")
                    }
                }.takeIf { it.isNotEmpty() } ?: "Coleta realizada"
                
                registerGpsEvent(
                    eventType = "POINT_COLLECTED",
                    description = fullDescription,
                    pointId = point.id,
                    collectedWeightKg = weightKg,
                    pointCondition = condition
                )
                
                // Atualiza status do ponto
                point.status = PointStatus.COLLECTED
                
                // Avança para próximo ponto
                withContext(Dispatchers.Main) {
                    currentPointIndex++
                    updateNextPointInfo()
                    drawRouteOnMap(routeWithPoints!!)
                    Toast.makeText(this@StartRoute, "Coleta registrada com sucesso!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("StartRoute", "Erro ao confirmar coleta: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@StartRoute, "Erro ao registrar coleta: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    /**
     * Mostra dialog de registrar problema (usando novo DialogFragment)
     */
    private fun showProblemDialog() {
        if (!routeStarted || backendExecutionId == null) {
            Toast.makeText(this, "Inicie a rota primeiro.", Toast.LENGTH_SHORT).show()
            return
        }
        
        val route = routeWithPoints ?: return
        val currentPoint = route.collectionPoints.getOrNull(currentPointIndex)
        
        // Usa currentLocation diretamente (já está sendo atualizado pelo GPS monitor)
        // Isso evita bloqueio da thread principal ao chamar getCurrentLocation() que pode demorar
        val location = currentLocation
        
        // Abre o dialog imediatamente, mesmo se location for null
        // O dialog pode obter a localização depois se necessário
        val availablePoints = route.collectionPoints.map { point ->
            RegisterProblemDialog.PointOption(
                id = point.id,
                name = "Ponto ${point.sequenceOrder} – ${point.address}"
            )
        }
        
        if (location == null) {
            Toast.makeText(this, "Aguardando localização GPS...", Toast.LENGTH_SHORT).show()
            // Tenta obter localização de forma assíncrona e abrir o dialog depois
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val newLocation = getCurrentLocation()
                    withContext(Dispatchers.Main) {
                        if (newLocation != null) {
                            // Abre o dialog com a localização obtida
                            openProblemDialogWithLocation(route, currentPoint, newLocation, availablePoints)
                        } else {
                            Toast.makeText(this@StartRoute, "Localização não disponível.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.w("StartRoute", "Não foi possível obter localização: ${e.message}")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@StartRoute, "Erro ao obter localização.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            return
        }
        
        // Abre o dialog imediatamente com a localização já disponível
        openProblemDialogWithLocation(route, currentPoint, location, availablePoints)
    }
    
    /**
     * Abre o dialog de registro de problema com a localização fornecida
     */
    private fun openProblemDialogWithLocation(
        route: RouteWithPoints,
        currentPoint: CollectionPoint?,
        location: Location,
        availablePoints: List<RegisterProblemDialog.PointOption>
    ) {
        val dialog = RegisterProblemDialog.newInstance(
            executionId = backendExecutionId!!,
            currentPointId = currentPoint?.id,
            currentPointName = currentPoint?.let { "Ponto ${it.sequenceOrder} – ${it.address}" },
            location = location,
            availablePoints = availablePoints
        )
        
        dialog.onProblemSaved = { recordId ->
            Toast.makeText(this, "Problema registrado com sucesso!", Toast.LENGTH_SHORT).show()
        }
        
        dialog.show(supportFragmentManager, "RegisterProblemDialog")
    }
    
    /**
     * Mostra dialog de registrar problema (método antigo - mantido para compatibilidade)
     */
    private fun showRegisterProblemDialog() {
        // Verifica GPS antes de registrar problema
        if (!gpsMonitor.isGpsEnabled()) {
            gpsMonitor.showGpsDisabledWarning()
            return
        }
        
        val route = routeWithPoints ?: return
        if (currentPointIndex >= route.collectionPoints.size) {
            Toast.makeText(this, "Não há ponto atual para registrar problema.", Toast.LENGTH_SHORT).show()
            return
        }
        
        val point = route.collectionPoints[currentPointIndex]
        val dialogView = layoutInflater.inflate(R.layout.dialog_register_problem, null)
        
        val tvAddress = dialogView.findViewById<TextView>(R.id.tvProblemPointAddress)
        tvAddress.text = "Ponto ${point.sequenceOrder} – ${point.address}"
        
        val tilOther = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilOtherDescription)
        val etOther = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etOtherDescription)
        
        var selectedProblem: String? = null
        
        dialogView.findViewById<Button>(R.id.btProblemInaccessible).setOnClickListener {
            selectedProblem = "Lixeira inacessível"
            tilOther.visibility = View.GONE
        }
        
        dialogView.findViewById<Button>(R.id.btProblemWrongAddress).setOnClickListener {
            selectedProblem = "Endereço incorreto"
            tilOther.visibility = View.GONE
        }
        
        dialogView.findViewById<Button>(R.id.btProblemWrongWaste).setOnClickListener {
            selectedProblem = "Resíduos fora do padrão"
            tilOther.visibility = View.GONE
        }
        
        dialogView.findViewById<Button>(R.id.btProblemOther).setOnClickListener {
            selectedProblem = null
            tilOther.visibility = View.VISIBLE
        }
        
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        
        dialogView.findViewById<Button>(R.id.btCancelProblem).setOnClickListener {
            dialog.dismiss()
        }
        
        dialogView.findViewById<Button>(R.id.btConfirmProblem).setOnClickListener {
            val problemDescription = selectedProblem ?: etOther.text.toString()
            if (problemDescription.isEmpty()) {
                Toast.makeText(this, "Por favor, selecione ou descreva o problema.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            registerProblem(point, problemDescription)
            dialog.dismiss()
        }
        
        dialog.show()
    }
    
    /**
     * Mostra dialog para registrar parada (usando novo DialogFragment)
     */
    private fun showRegisterStopDialogNew() {
        if (!routeStarted || backendExecutionId == null) {
            Toast.makeText(this, "Inicie a rota primeiro.", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Usa currentLocation diretamente (já está sendo atualizado pelo GPS monitor)
        // Isso evita bloqueio da thread principal ao chamar getCurrentLocation() que pode demorar
        val location = currentLocation
        
        // Abre o dialog imediatamente, mesmo se location for null
        // O dialog pode obter a localização depois se necessário
        if (location == null) {
            Toast.makeText(this, "Aguardando localização GPS...", Toast.LENGTH_SHORT).show()
            // Tenta obter localização de forma assíncrona e abrir o dialog depois
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val newLocation = getCurrentLocation()
                    withContext(Dispatchers.Main) {
                        if (newLocation != null) {
                            // Abre o dialog com a localização obtida
                            openStopDialogWithLocation(newLocation)
                        } else {
                            Toast.makeText(this@StartRoute, "Localização não disponível.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.w("StartRoute", "Não foi possível obter localização: ${e.message}")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@StartRoute, "Erro ao obter localização.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            return
        }
        
        // Abre o dialog imediatamente com a localização já disponível
        openStopDialogWithLocation(location)
    }
    
    /**
     * Abre o dialog de registro de parada com a localização fornecida
     */
    private fun openStopDialogWithLocation(location: Location) {
        val dialog = RegisterStopDialog.newInstance(
            executionId = backendExecutionId!!,
            location = location
        )
        
        dialog.onStopSaved = { recordId ->
            Toast.makeText(this, "Parada registrada com sucesso!", Toast.LENGTH_SHORT).show()
        }
        
        dialog.show(supportFragmentManager, "RegisterStopDialog")
    }
    
    /**
     * Mostra dialog para registrar parada (LUNCH, FUEL, BREAK, etc) - método antigo
     */
    private fun showRegisterStopDialog() {
        // Verifica GPS antes de registrar parada
        if (!gpsMonitor.isGpsEnabled()) {
            gpsMonitor.showGpsDisabledWarning()
            return
        }
        
        val dialogView = layoutInflater.inflate(R.layout.dialog_register_stop, null)
        
        val tilDescription = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilStopDescription)
        val etDescription = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etStopDescription)
        val btConfirm = dialogView.findViewById<Button>(R.id.btConfirmStop)
        
        var selectedStopType: String? = null
        
        dialogView.findViewById<Button>(R.id.btStopLunch).setOnClickListener {
            selectedStopType = "LUNCH"
            tilDescription.visibility = View.VISIBLE
            btConfirm.visibility = View.VISIBLE
        }
        
        dialogView.findViewById<Button>(R.id.btStopFuel).setOnClickListener {
            selectedStopType = "FUEL"
            tilDescription.visibility = View.VISIBLE
            btConfirm.visibility = View.VISIBLE
        }
        
        dialogView.findViewById<Button>(R.id.btStopBreak).setOnClickListener {
            selectedStopType = "BREAK"
            tilDescription.visibility = View.VISIBLE
            btConfirm.visibility = View.VISIBLE
        }
        
        dialogView.findViewById<Button>(R.id.btStopOther).setOnClickListener {
            selectedStopType = "OTHER"
            tilDescription.visibility = View.VISIBLE
            tilDescription.hint = "Descreva a parada"
            btConfirm.visibility = View.VISIBLE
        }
        
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        
        dialogView.findViewById<Button>(R.id.btCancelStop).setOnClickListener {
            dialog.dismiss()
        }
        
        btConfirm.setOnClickListener {
            if (selectedStopType == null) {
                Toast.makeText(this, "Por favor, selecione um tipo de parada.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            val description = etDescription.text.toString().trim()
            val eventType = selectedStopType!!
            val finalDescription = when (eventType) {
                "LUNCH" -> description.ifBlank { "Parada para almoço" }
                "FUEL" -> description.ifBlank { "Parada para abastecimento" }
                "BREAK" -> description.ifBlank { "Pausa" }
                else -> description.ifBlank { "Parada" }
            }
            
            registerStop(eventType, finalDescription)
            dialog.dismiss()
        }
        
        dialog.show()
    }
    
    /**
     * Registra uma parada (LUNCH, FUEL, BREAK, etc)
     */
    private fun registerStop(eventType: String, description: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Registra evento GPS
                registerGpsEvent(eventType, description)
                
                withContext(Dispatchers.Main) {
                    val stopTypeText = when (eventType) {
                        "LUNCH" -> "Almoço"
                        "FUEL" -> "Abastecimento"
                        "BREAK" -> "Pausa"
                        else -> "Parada"
                    }
                    Toast.makeText(
                        this@StartRoute,
                        "Parada registrada: $stopTypeText",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@StartRoute, "Erro ao registrar parada: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun registerProblem(point: CollectionPoint, description: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Registra evento GPS
                registerGpsEvent("PROBLEM", description, point.id)
                
                // Atualiza status do ponto
                point.status = PointStatus.PROBLEM
                
                withContext(Dispatchers.Main) {
                    updateNextPointInfo()
                    drawRouteOnMap(routeWithPoints!!)
                    
                    // Pergunta se quer pular ou tentar novamente
                    AlertDialog.Builder(this@StartRoute)
                        .setTitle("Problema registrado")
                        .setMessage("Deseja pular este ponto e seguir para o próximo?")
                        .setPositiveButton("Pular") { _, _ ->
                            currentPointIndex++
                            updateNextPointInfo()
                            drawRouteOnMap(routeWithPoints!!)
                        }
                        .setNegativeButton("Manter na rota", null)
                        .show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@StartRoute, "Erro ao registrar problema: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun showRoutePointsList() {
        if (backendExecutionId == null) {
            Toast.makeText(this, "Execução não encontrada.", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Abre tela de registros da rota (eventos GPS)
        val intent = Intent(this, RouteRecordsActivity::class.java).apply {
            putExtra("execution_id", backendExecutionId!!)
        }
        startActivity(intent)
    }
    
    private fun showCancelRouteDialog() {
        if (!routeStarted || (execLocalId == 0L && backendExecutionId == null)) {
            Toast.makeText(this, "Não há rota em andamento para cancelar.", Toast.LENGTH_SHORT).show()
            return
        }
        
        val input = android.widget.EditText(this)
        input.hint = "Motivo do cancelamento (opcional)"
        
        AlertDialog.Builder(this)
            .setTitle("Cancelar Rota")
            .setMessage("Ao cancelar, esta rota será encerrada e as coletas restantes marcadas como \"não realizadas\". Deseja realmente cancelar?")
            .setView(input)
            .setPositiveButton("Cancelar rota") { _, _ ->
                val reason = input.text.toString().ifBlank { "Cancelado pelo motorista" }
                cancelRoute(reason)
            }
            .setNegativeButton("Voltar", null)
            .show()
    }
    
    private fun cancelRoute(reason: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Registra evento GPS de cancelamento
                registerGpsEvent("PROBLEM", "Rota cancelada: $reason")
                
                // Cancela execução no backend, se houver
                backendExecutionId?.let { execId ->
                    executionRepository.cancelExecution(execId, reason)
                }
                
                // Atualiza execução local para CANCELLED
                if (execLocalId > 0L) {
                    executionDao.getById(execLocalId)?.let { exec ->
                        val updatedExec = exec.copy(
                            endTimestamp = System.currentTimeMillis(),
                            status = "CANCELLED"
                        )
                        executionDao.update(updatedExec)
                    }
                }
                
                // Para o rastreamento GPS
                stopGpsTracking()
                
                // Sincroniza dados pendentes
                syncRepository.syncPendingData()
                
                withContext(Dispatchers.Main) {
                    routeStarted = false
                    execLocalId = 0L
                    backendExecutionId = null
                    applyUiState()
                    Toast.makeText(this@StartRoute, "Rota cancelada com sucesso!", Toast.LENGTH_SHORT).show()
                    finish() // Volta para a lista de escalas
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@StartRoute, "Erro ao cancelar rota: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun showActionButtons() {
        btRegisterCollection.visibility = View.VISIBLE
        llSecondaryButtons.visibility = View.VISIBLE
        btRegisterProblem.visibility = View.VISIBLE
        btRegisterStop.visibility = View.VISIBLE
        btCancelRoute.visibility = View.VISIBLE
        btFinishRoute.visibility = View.VISIBLE
    }
    
    private fun hideActionButtons() {
        btRegisterCollection.visibility = View.GONE
        llSecondaryButtons.visibility = View.GONE
        btRegisterProblem.visibility = View.GONE
        btRegisterStop.visibility = View.GONE
        btCancelRoute.visibility = View.GONE
        btFinishRoute.visibility = View.GONE
    }
    
    private fun applyUiState() {
        if (routeStarted) {
            // Rota iniciada - mostra informações e botões de ação
            btStart.visibility = View.GONE
            llRouteStatus.visibility = View.VISIBLE
            tvRouteSubtitle.visibility = View.GONE
            showActionButtons() // Sempre mostra botões quando rota iniciada
            tvViewRouteRecords.visibility = View.VISIBLE
            
            tvStatus.text = "Em andamento"
            supportActionBar?.title = routeName ?: "Rota em andamento"
            
            // Atualiza horário de início (assíncrono)
            if (execLocalId > 0L) {
                lifecycleScope.launch(Dispatchers.IO) {
                    executionDao.getById(execLocalId)?.let { exec ->
                        exec.startTimestamp?.let { timestamp ->
                            withContext(Dispatchers.Main) {
                                tvStartTime.text = "Iniciada às ${sdfTime.format(Date(timestamp))}"
                            }
                        }
                    }
                }
            }
        } else {
            // Rota não iniciada - apenas botão de iniciar
            btStart.visibility = View.VISIBLE
            hideActionButtons()
            llRouteStatus.visibility = View.GONE
            tvViewRouteRecords.visibility = View.GONE
            
            tvRouteSubtitle.text = "Você ainda não iniciou a rota"
            tvRouteSubtitle.visibility = View.VISIBLE
            supportActionBar?.title = routeName ?: "Iniciar rota"
            
            // Garante que o bottom sheet está colapsado quando não iniciada
            if (::bottomSheetBehavior.isInitialized) {
                if (bottomSheetBehavior.state != com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED) {
                    bottomSheetBehavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED
                }
            }
        }
        updateNextPointInfo()
    }

    private fun checkLocationPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    private fun requestLocationPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            PERMISSION_REQUEST_CODE
        )
    }
    
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permissão concedida
                Log.d("StartRoute", "Permissão de localização concedida")
                Toast.makeText(this, "Permissão de localização concedida!", Toast.LENGTH_SHORT).show()
                
                // Se estava tentando iniciar rota, verifica GPS antes de iniciar
                if (routeStarted) {
                    // Rota já iniciada, apenas atualiza o overlay
                    if (gpsMonitor.isGpsEnabled()) {
                        setupLocationOverlay()
                    }
                } else {
                    // Permissões concedidas, agora pode abrir o dialog de iniciar rota
                    // Verifica GPS antes de abrir o dialog
                    if (gpsMonitor.isGpsEnabled()) {
                        showStartRouteDialog()
                    } else {
                        gpsMonitor.checkAndRequestGps(
                            onGpsEnabled = {
                                showStartRouteDialog()
                            },
                            onGpsDisabled = {
                                Toast.makeText(this, "GPS precisa estar habilitado para iniciar a rota.", Toast.LENGTH_LONG).show()
                            }
                        )
                    }
                }
            } else {
                // Permissão negada
                val shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
                if (shouldShowRationale) {
                    // Usuário negou mas pode mudar de ideia
                    AlertDialog.Builder(this)
                        .setTitle("Permissão Necessária")
                        .setMessage("Este aplicativo precisa de permissão de localização para funcionar. Por favor, conceda a permissão nas configurações do aplicativo.")
                        .setPositiveButton("Configurações") { _, _ ->
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = android.net.Uri.fromParts("package", packageName, null)
                            }
                            startActivity(intent)
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                } else {
                    // Usuário negou permanentemente
                    Toast.makeText(
                        this,
                        "Permissão de localização necessária. Ative nas configurações do aplicativo.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * Define se um registro deve ser marcado como offline.
     * - Se não tiver conectividade OU não existir backendExecutionId, considera offline.
     */
    private fun shouldMarkRecordAsOffline(): Boolean {
        return try {
            val online = syncRepository.isOnline()
            !online || backendExecutionId == null
        } catch (e: Exception) {
            // Se der erro para checar online, assume offline
            true
        }
    }
    
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("route_started", routeStarted)
        outState.putLong("exec_local_id", execLocalId)
        outState.putInt("current_point_index", currentPointIndex)
        backendExecutionId?.let { outState.putLong("backend_exec_id", it) }
        super.onSaveInstanceState(outState)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        gpsMonitor.stopMonitoring()
        mapView.onPause()
        // Remove atualizações de localização
        locationCallback?.let { 
            fusedLocationClient.removeLocationUpdates(it)
        }
        if (routeStarted) {
            // Não para o serviço GPS aqui, apenas quando finalizar a rota
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (routeStarted) {
            AlertDialog.Builder(this)
                .setTitle("Atenção")
                .setMessage("Há uma rota em andamento. Deseja realmente sair?")
                .setPositiveButton("Sim") { _, _ ->
                    onBackPressedDispatcher.onBackPressed()
                }
                .setNegativeButton("Não", null)
                .show()
            return false
        }
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
