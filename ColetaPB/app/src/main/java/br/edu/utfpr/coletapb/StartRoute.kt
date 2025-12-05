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
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import br.edu.utfpr.coletapb.utils.GpsMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StartRoute : AppCompatActivity() {

    private var routeStarted = false

    // UI Components (novo layout)
    private lateinit var btStart: Button
    private lateinit var btRegisterCollection: Button
    private lateinit var btRegisterProblem: Button
    private lateinit var btCancelRoute: Button
    private lateinit var btFinishRoute: Button
    private lateinit var tvRouteName: TextView
    private lateinit var tvStartTime: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvNextPointsTitle: TextView
    private lateinit var tvPointsProgress: TextView
    private lateinit var tvRouteSubtitle: TextView
    private lateinit var chipMode: com.google.android.material.chip.Chip
    private lateinit var rvNextPoints: androidx.recyclerview.widget.RecyclerView
    private lateinit var tvViewRouteRecords: TextView
    private lateinit var llRouteStatus: LinearLayout
    private lateinit var llActionButtons: LinearLayout
    private lateinit var bottomSheet: androidx.core.widget.NestedScrollView
    private lateinit var bottomSheetBehavior: com.google.android.material.bottomsheet.BottomSheetBehavior<*>
    private lateinit var fabCenterLocation: com.google.android.material.floatingactionbutton.FloatingActionButton
    
    // Adapter para lista de próximos pontos
    private lateinit var nextPointAdapter: br.edu.utfpr.coletapb.adapter.NextPointAdapter

    // Map (OSMDroid)
    private lateinit var mapView: MapView
    private var currentLocationOverlay: MyLocationNewOverlay? = null
    private val pointMarkers = mutableListOf<Marker>()
    private var routePolyline: Polyline? = null

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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_start_route_new)
        
        // Inicializa RetrofitClient se necessário
        RetrofitClient.init(this)
        
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Iniciar rota"

        // extras vindos da RouteList
        routeId = intent.getLongExtra("route_id", 0L)
        assignmentId = intent.getLongExtra("assignment_id", 0L)
        routeName = intent.getStringExtra("route_name")
        routeInfo = intent.getStringExtra("route_info")

        // Inicializa componentes do novo layout
        tvRouteName = findViewById(R.id.tvRouteName)
        tvStartTime = findViewById(R.id.tvStartTime)
        tvStatus = findViewById(R.id.tvStatus)
        tvNextPointsTitle = findViewById(R.id.tvNextPointsTitle)
        tvPointsProgress = findViewById(R.id.tvPointsProgress)
        tvRouteSubtitle = findViewById(R.id.tvRouteSubtitle)
        chipMode = findViewById(R.id.chipMode)
        rvNextPoints = findViewById(R.id.rvNextPoints)
        tvViewRouteRecords = findViewById(R.id.tvViewRouteRecords)
        llRouteStatus = findViewById(R.id.llRouteStatus)
        llActionButtons = findViewById(R.id.llActionButtons)
        bottomSheet = findViewById(R.id.bottomSheet)
        btStart = findViewById(R.id.btStart)
        btRegisterCollection = findViewById(R.id.btRegisterCollection)
        btRegisterProblem = findViewById(R.id.btRegisterProblem)
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
                when (newState) {
                    com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED -> {
                        // Quando expandido, mostra botões de ação se rota iniciada
                        if (routeStarted) {
                            llActionButtons.visibility = View.VISIBLE
                            tvViewRouteRecords.visibility = View.VISIBLE
                        }
                    }
                    com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED -> {
                        // Quando colapsado, esconde botões de ação
                        llActionButtons.visibility = View.GONE
                        tvViewRouteRecords.visibility = View.GONE
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
        
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(15.0)
        mapView.setUseDataConnection(true)
        
        // Habilita zoom controls (os controles de zoom já estão habilitados por padrão)
        // mapView.zoomController.setVisibility(View.VISIBLE) // Opcional: controlar visibilidade
        
        // Configurações adicionais para garantir download de tiles
        mapView.isHorizontalMapRepetitionEnabled = true
        mapView.isVerticalMapRepetitionEnabled = false
        mapView.isTilesScaledToDpi = true
        
        // Força atualização do mapa após um pequeno delay para garantir que tudo está configurado
        mapView.post {
            mapView.invalidate()
            Log.d("StartRoute", "Mapa invalidado após configuração")
        }
        
        Log.d("StartRoute", "Mapa inicializado - Zoom: ${mapView.zoomLevelDouble}, DataConnection: ${mapView.useDataConnection()}, TileSource: ${mapView.tileProvider.tileSource.name()}")
        
        // Configura overlay de localização
        if (checkLocationPermissions() && gpsMonitor.isGpsEnabled()) {
            currentLocationOverlay = MyLocationNewOverlay(mapView)
            currentLocationOverlay?.enableMyLocation()
            mapView.overlays.add(currentLocationOverlay)
        }

        // Carrega pontos da rota
        loadRoutePoints()
        
        // Desenha rota se já estiver carregada
        routeWithPoints?.let { drawRouteOnMap(it) }

        // Verifica se há execução em andamento
        checkCurrentExecution()

        // Restaura estado
        routeStarted = savedInstanceState?.getBoolean("route_started") ?: false
        execLocalId = savedInstanceState?.getLong("exec_local_id") ?: 0L
        backendExecutionId = savedInstanceState?.getLong("backend_exec_id")?.takeIf { it > 0 }
        currentPointIndex = savedInstanceState?.getInt("current_point_index") ?: 0
        
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
        btStart.setOnClickListener { onStartRoute() }
        btRegisterCollection.setOnClickListener { showConfirmCollectionDialog() }
        btRegisterProblem.setOnClickListener { showRegisterProblemDialog() }
        btCancelRoute.setOnClickListener { showCancelRouteDialog() }
        btFinishRoute.setOnClickListener { onFinishRoute() }
        tvViewRouteRecords.setOnClickListener { showRoutePointsList() }
        fabCenterLocation.setOnClickListener { centerMapOnCurrentLocation() }
        
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
            // Se GPS está ativo agora, atualiza o overlay de localização
            if (currentLocationOverlay == null) {
                currentLocationOverlay = MyLocationNewOverlay(mapView)
                currentLocationOverlay?.enableMyLocation()
                mapView.overlays.add(currentLocationOverlay)
            }
        }
    }
    
    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }
    
    private fun checkCurrentExecution() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // SEMPRE verifica primeiro no backend se há execução iniciada
                // Isso garante que só mostra os botões de registrar coleta/problema
                // se realmente houver uma execução iniciada no backend
                if (syncRepository.isOnline() && assignmentId > 0) {
                    Log.d("StartRoute", "Verificando execução no backend...")
                    val backendExecResult = executionRepository.getMyCurrentExecution()
                    backendExecResult.fold(
                        onSuccess = { backendExec ->
                            if (backendExec != null && backendExec.assignmentId == assignmentId) {
                                // Há execução iniciada no backend para este assignment
                                backendExecutionId = backendExec.id
                                Log.d("StartRoute", "Execução encontrada no backend: id=${backendExec.id}")
                                
                                // Verifica se há execução local correspondente
                                val currentExec = executionDao.getCurrentExecution()
                                if (currentExec != null && currentExec.backendId == backendExec.id) {
                                    // Já existe execução local sincronizada
                                    execLocalId = currentExec.localId
                                    Log.d("StartRoute", "Execução local já existe e está sincronizada")
                                } else {
                                    // Cria ou atualiza execução local a partir da do backend
                                    val startTimestamp = try {
                                        backendExec.startTime?.let { 
                                            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
                                                .parse(it)?.time 
                                        } ?: System.currentTimeMillis()
                                    } catch (e: Exception) {
                                        System.currentTimeMillis()
                                    }
                                    
                                    if (currentExec != null) {
                                        // Atualiza execução local existente
                                        val updatedExec = currentExec.copy(
                                            backendId = backendExec.id,
                                            status = backendExec.status ?: "IN_PROGRESS",
                                            startTimestamp = startTimestamp,
                                            startLat = backendExec.startLat ?: currentExec.startLat,
                                            startLng = backendExec.startLng ?: currentExec.startLng
                                        )
                                        executionDao.update(updatedExec)
                                        execLocalId = currentExec.localId
                                        Log.d("StartRoute", "Execução local atualizada com backendId: ${backendExec.id}")
                                    } else {
                                        // Cria nova execução local
                                        val executionLocal = ExecutionLocal(
                                            routeId = routeId,
                                            vehicleId = null,
                                            driverId = prefsHelper.getDriverId().takeIf { it > 0 },
                                            startTimestamp = startTimestamp,
                                            startLat = backendExec.startLat ?: 0.0,
                                            startLng = backendExec.startLng ?: 0.0,
                                            status = backendExec.status ?: "IN_PROGRESS",
                                            backendId = backendExec.id
                                        )
                                        execLocalId = executionDao.insert(executionLocal)
                                        Log.d("StartRoute", "Execução local criada a partir do backend: execLocalId=$execLocalId, backendId=$backendExecutionId")
                                    }
                                }
                                
                                // Marca como iniciada apenas se houver execução no backend
                                withContext(Dispatchers.Main) {
                                    routeStarted = true
                                    applyUiState()
                                }
                            } else {
                                // Não há execução no backend para este assignment
                                Log.d("StartRoute", "Nenhuma execução no backend para este assignment")
                                
                                // Limpa execução local se existir (pode ser antiga ou incompleta)
                                val currentExec = executionDao.getCurrentExecution()
                                if (currentExec != null && currentExec.backendId == null) {
                                    // Se há execução local sem backendId, pode ser antiga
                                    // Não marca como iniciada, apenas limpa se necessário
                                    Log.d("StartRoute", "Execução local sem backendId encontrada, mas não há execução no backend")
                                }
                                
                                withContext(Dispatchers.Main) {
                                    routeStarted = false
                                    applyUiState()
                                }
                            }
                        },
                        onFailure = { error ->
                            // Erro ao buscar do backend ou não há execução (404)
                            Log.d("StartRoute", "Nenhuma execução no backend ou erro: ${error.message}")
                            
                            // Não marca como iniciada se não há confirmação do backend
                            withContext(Dispatchers.Main) {
                                routeStarted = false
                                applyUiState()
                            }
                        }
                    )
                } else {
                    // Offline ou assignmentId inválido - verifica apenas localmente
                    // Mas não marca como iniciada se não tiver backendId
                    val currentExec = executionDao.getCurrentExecution()
                    if (currentExec != null && currentExec.backendId != null) {
                        // Há execução local com backendId (foi iniciada antes)
                        execLocalId = currentExec.localId
                        backendExecutionId = currentExec.backendId
                        withContext(Dispatchers.Main) {
                            routeStarted = true
                            applyUiState()
                        }
                    } else {
                        // Não há execução local válida ou não tem backendId
                        withContext(Dispatchers.Main) {
                            routeStarted = false
                            applyUiState()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("StartRoute", "Erro ao verificar execução atual: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    routeStarted = false
                    applyUiState()
                }
            }
        }
    }

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

                // Registra ponto START
                gpsDao.insert(
                    GpsRecordLocal(
                        executionLocalId = execLocalId,
                        timestamp = now,
                        lat = lat,
                        lng = lng,
                        eventType = "START",
                        isOffline = backendExecutionId == null
                    )
                )

                // Inicia serviço de rastreamento GPS (se tiver permissão)
                if (checkLocationPermissions()) {
                    startGpsTracking()
                }

                withContext(Dispatchers.Main) {
                    routeStarted = true
                    currentLocation = location
                    location?.let { updateCurrentLocationOnMap(it) }
                    
                    // Garante que o bottom sheet fica colapsado quando inicia a rota
                    if (::bottomSheetBehavior.isInitialized) {
                        bottomSheetBehavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED
                    }
                    
                    applyUiState()
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
        val intent = Intent(this, GpsTrackingService::class.java).apply {
            action = GpsTrackingService.ACTION_START_TRACKING
            putExtra(GpsTrackingService.EXTRA_EXECUTION_ID, execLocalId)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
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
                        isOffline = backendExecutionId == null
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
    
    private fun registerGpsEvent(eventType: String, description: String? = null, pointId: Long? = null) {
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
                            isOffline = backendExecutionId == null
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

    private fun onFinishRoute() {
        if (!routeStarted || execLocalId == 0L) return

        AlertDialog.Builder(this)
            .setTitle("Finalizar Rota")
            .setMessage("Deseja realmente finalizar esta rota?")
            .setPositiveButton("Sim") { _, _ ->
                finishRoute()
            }
            .setNegativeButton("Não", null)
            .show()
    }
    
    private fun finishRoute() {
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
                        isOffline = backendExecutionId == null
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
                        executionRepository.completeExecution(
                            executionId = backendExecutionId!!,
                            endLat = lat,
                            endLng = lng
                        )
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

                    Toast.makeText(this@StartRoute, msg, Toast.LENGTH_LONG).show()
                    finish() // volta para a lista
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@StartRoute,
                        "Erro ao finalizar rota: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
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
    }
    
    private fun drawRouteOnMap(route: RouteWithPoints) {
        // Limpa marcadores anteriores
        pointMarkers.forEach { mapView.overlays.remove(it) }
        pointMarkers.clear()
        routePolyline?.let { mapView.overlays.remove(it) }
        
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
        
        mapView.invalidate()
    }
    
    private fun updateNextPointInfo() {
        val route = routeWithPoints ?: return
        val points = route.collectionPoints
        
        if (points.isEmpty()) {
            return
        }
        
        // Atualiza progresso se rota iniciada
        if (routeStarted) {
            val totalPoints = points.size
            val visitedPoints = currentPointIndex
            tvPointsProgress.text = "($visitedPoints/$totalPoints)"
            tvPointsProgress.visibility = View.VISIBLE
        }
        
        // Cria lista dos próximos 2-3 pontos
        val nextPoints = mutableListOf<br.edu.utfpr.coletapb.adapter.NextPointItem>()
        
        if (currentPointIndex < points.size) {
            // Primeiro ponto (próximo)
            val nextPoint = points[currentPointIndex]
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
                    status = if (currentPointIndex == 0) "Recomendado" else "Próximo"
                )
            )
            
            // Segundo e terceiro pontos (se existirem)
            for (i in 1..2) {
                if (currentPointIndex + i < points.size) {
                    val point = points[currentPointIndex + i]
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
                }
            }
        } else {
            // Rota concluída
            tvNextPointsTitle.text = "Rota concluída"
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
            mapView.controller.animateTo(geoPoint)
            mapView.controller.setZoom(16.0)
        } ?: run {
            Toast.makeText(this, "Localização não disponível", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun updateCurrentLocationOnMap(location: Location) {
        currentLocation = location
        
        // O MyLocationNewOverlay já gerencia a localização atual
        // Apenas atualiza a distância até próximo ponto
        updateNextPointInfo()
    }
    
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
        
        val etVolume = dialogView.findViewById<AutoCompleteTextView>(R.id.etVolume)
        val volumeOptions = arrayOf("Baixo", "Médio", "Alto")
        val volumeAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, volumeOptions)
        etVolume.setAdapter(volumeAdapter)
        
        val etObservation = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etObservation)
        
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        
        dialogView.findViewById<Button>(R.id.btCancel).setOnClickListener {
            dialog.dismiss()
        }
        
        dialogView.findViewById<Button>(R.id.btConfirm).setOnClickListener {
            val volume = etVolume.text.toString()
            val observation = etObservation.text.toString()
            confirmCollection(point, volume, observation)
            dialog.dismiss()
        }
        
        dialog.show()
    }
    
    private fun confirmCollection(point: CollectionPoint, volume: String, observation: String?) {
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
                
                val description = buildString {
                    append("Coleta realizada")
                    if (volume.isNotEmpty()) append(" - Volume: $volume")
                    if (!observation.isNullOrEmpty()) append(" - Obs: $observation")
                }
                
                // Registra evento GPS
                registerGpsEvent("POINT_COLLECTED", description, point.id)
                
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
        val route = routeWithPoints ?: run {
            Toast.makeText(this, "Carregando pontos da rota...", Toast.LENGTH_SHORT).show()
            return
        }
        
        val intent = Intent(this, RoutePointsListActivity::class.java).apply {
            putParcelableArrayListExtra("points", ArrayList(route.collectionPoints))
            putExtra("current_point_index", currentPointIndex)
        }
        
        pointsListLauncher.launch(intent)
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
    
    private fun applyUiState() {
        if (routeStarted) {
            // Rota iniciada - mostra informações e botões quando expandido
            btStart.visibility = View.GONE
            llRouteStatus.visibility = View.VISIBLE
            chipMode.visibility = View.VISIBLE
            tvRouteSubtitle.visibility = View.GONE
            
            // Botões de ação aparecem apenas quando expandido (gerenciado pelo listener)
            if (::bottomSheetBehavior.isInitialized) {
                llActionButtons.visibility = if (bottomSheetBehavior.state == com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            } else {
                llActionButtons.visibility = View.GONE
            }
            
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
            llActionButtons.visibility = View.GONE
            llRouteStatus.visibility = View.GONE
            chipMode.visibility = View.GONE
            tvPointsProgress.visibility = View.GONE
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
                        currentLocationOverlay = MyLocationNewOverlay(mapView)
                        currentLocationOverlay?.enableMyLocation()
                        mapView.overlays.add(currentLocationOverlay)
                    }
                } else {
                    // Verifica GPS antes de iniciar
                    if (gpsMonitor.isGpsEnabled()) {
                        onStartRoute()
                    } else {
                        gpsMonitor.checkAndRequestGps(
                            onGpsEnabled = {
                                onStartRoute()
                            },
                            onGpsDisabled = {
                                // GPS não ativado
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
