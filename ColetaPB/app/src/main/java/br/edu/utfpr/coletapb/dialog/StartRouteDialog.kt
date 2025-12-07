package br.edu.utfpr.coletapb.dialog

import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import br.edu.utfpr.coletapb.R
import br.edu.utfpr.coletapb.data.model.GpsEventType
import br.edu.utfpr.coletapb.data.repository.ExecutionRepository
import br.edu.utfpr.coletapb.data.repository.GpsRepository
import br.edu.utfpr.coletapb.data.local.SharedPreferencesHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

/**
 * Dialog para iniciar rota (START)
 * Evento: START
 * Após iniciar, inicia loop automático de NORMAL a cada 15 segundos
 */
class StartRouteDialog : DialogFragment() {
    
    private var assignmentId: Long? = null
    private var routeName: String? = null
    private var currentLocation: Location? = null
    
    // Views
    private lateinit var txtRouteName: TextView
    private lateinit var btnCancel: Button
    private lateinit var btnStart: Button
    
    private lateinit var executionRepository: ExecutionRepository
    private lateinit var gpsRepository: GpsRepository
    private lateinit var prefsHelper: SharedPreferencesHelper
    
    // Callback para quando a rota for iniciada
    var onRouteStarted: ((Long) -> Unit)? = null
    
    // Job para o loop de NORMAL
    private var normalLoopJob: kotlinx.coroutines.Job? = null
    
    companion object {
        private const val TAG = "StartRouteDialog"
        
        fun newInstance(
            assignmentId: Long,
            routeName: String?,
            location: Location?
        ): StartRouteDialog {
            return StartRouteDialog().apply {
                arguments = Bundle().apply {
                    putLong("assignmentId", assignmentId)
                    routeName?.let { putString("routeName", it) }
                    location?.let {
                        putDouble("latitude", it.latitude)
                        putDouble("longitude", it.longitude)
                        it.speed.let { speed -> if (speed > 0) putFloat("speed", speed) }
                        it.bearing.let { bearing -> if (bearing > 0) putFloat("bearing", bearing) }
                        it.accuracy.let { accuracy -> if (accuracy > 0) putFloat("accuracy", accuracy) }
                    }
                }
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Usa estilo padrão do Material Design
        setStyle(STYLE_NORMAL, android.R.style.Theme_Material_Light_Dialog)
        
        arguments?.let {
            assignmentId = it.getLong("assignmentId")
            routeName = it.getString("routeName")
            
            // Recriar Location
            if (it.containsKey("latitude") && it.containsKey("longitude")) {
                currentLocation = Location("dialog").apply {
                    latitude = it.getDouble("latitude")
                    longitude = it.getDouble("longitude")
                    if (it.containsKey("speed")) speed = it.getFloat("speed")
                    if (it.containsKey("bearing")) bearing = it.getFloat("bearing")
                    if (it.containsKey("accuracy")) accuracy = it.getFloat("accuracy")
                }
            }
        }
        
        prefsHelper = SharedPreferencesHelper(requireContext())
        executionRepository = ExecutionRepository(prefsHelper)
        gpsRepository = GpsRepository(prefsHelper)
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_start_route, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Inicializar views
        txtRouteName = view.findViewById(R.id.txtRouteName)
        btnCancel = view.findViewById(R.id.btnCancel)
        btnStart = view.findViewById(R.id.btnStart)
        
        // Preencher nome da rota
        routeName?.let {
            txtRouteName.text = "Você está iniciando a rota \"$it\"?"
        } ?: run {
            txtRouteName.text = "Você está iniciando a rota?"
        }
        
        // Listeners
        btnCancel.setOnClickListener {
            dismiss()
        }
        
        btnStart.setOnClickListener {
            startRoute()
        }
    }
    
    /**
     * Inicia a rota
     */
    private fun startRoute() {
        if (assignmentId == null) {
            Toast.makeText(requireContext(), "Erro: assignmentId não encontrado", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (currentLocation == null) {
            Toast.makeText(requireContext(), "Erro: localização não disponível", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Mostrar loading
        btnStart.isEnabled = false
        btnStart.text = "Iniciando..."
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // 1. Chamar POST /api/v1/executions/start
                val startResult = withContext(Dispatchers.IO) {
                    executionRepository.startExecution(
                        assignmentId = assignmentId!!,
                        startLat = currentLocation!!.latitude,
                        startLng = currentLocation!!.longitude
                    )
                }
                
                val executionId = startResult.fold(
                    onSuccess = { execution -> execution.id },
                    onFailure = { error ->
                        Log.e(TAG, "Erro ao iniciar execução: ${error.message}", error)
                        Toast.makeText(requireContext(), "Erro ao iniciar execução: ${error.message}", Toast.LENGTH_LONG).show()
                        btnStart.isEnabled = true
                        btnStart.text = "INICIAR"
                        return@launch
                    }
                )
                
                // 2. Enviar evento START
                val speedKmh = if (currentLocation!!.hasSpeed()) {
                    currentLocation!!.speed * 3.6
                } else null
                
                val headingDegrees = if (currentLocation!!.hasBearing()) {
                    currentLocation!!.bearing.toDouble()
                } else null
                
                val accuracyMeters = if (currentLocation!!.hasAccuracy()) {
                    currentLocation!!.accuracy.toDouble()
                } else null
                
                val startEventResult = withContext(Dispatchers.IO) {
                    gpsRepository.registerGpsPosition(
                        executionId = executionId,
                        latitude = currentLocation!!.latitude,
                        longitude = currentLocation!!.longitude,
                        speedKmh = speedKmh,
                        headingDegrees = headingDegrees,
                        accuracyMeters = accuracyMeters,
                        eventType = GpsEventType.START.apiValue,
                        isAutomatic = false,
                        isOffline = false,
                        description = "Início da coleta"
                    )
                }
                
                startEventResult.fold(
                    onSuccess = {
                        // 3. Iniciar loop de NORMAL a cada 15 segundos
                        startNormalLoop(executionId)
                        
                        Toast.makeText(requireContext(), "Rota iniciada com sucesso!", Toast.LENGTH_SHORT).show()
                        onRouteStarted?.invoke(executionId)
                        dismiss()
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Erro ao enviar evento START: ${error.message}", error)
                        Toast.makeText(requireContext(), "Erro ao enviar evento START: ${error.message}", Toast.LENGTH_LONG).show()
                        btnStart.isEnabled = true
                        btnStart.text = "INICIAR"
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Exceção ao iniciar rota: ${e.message}", e)
                Toast.makeText(requireContext(), "Erro: ${e.message}", Toast.LENGTH_LONG).show()
                btnStart.isEnabled = true
                btnStart.text = "INICIAR"
            }
        }
    }
    
    /**
     * Inicia o loop automático de envio de eventos NORMAL a cada 15 segundos
     */
    private fun startNormalLoop(executionId: Long) {
        normalLoopJob?.cancel()
        
        normalLoopJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(15_000L) // 15 segundos
                
                // Obter localização atual (pode melhorar depois para usar FusedLocationProviderClient)
                // Por enquanto, usa a última localização conhecida
                currentLocation?.let { location ->
                    val speedKmh = if (location.hasSpeed()) {
                        location.speed * 3.6
                    } else null
                    
                    val headingDegrees = if (location.hasBearing()) {
                        location.bearing.toDouble()
                    } else null
                    
                    val accuracyMeters = if (location.hasAccuracy()) {
                        location.accuracy.toDouble()
                    } else null
                    
                    val result = gpsRepository.registerGpsPosition(
                        executionId = executionId,
                        latitude = location.latitude,
                        longitude = location.longitude,
                        speedKmh = speedKmh,
                        headingDegrees = headingDegrees,
                        accuracyMeters = accuracyMeters,
                        eventType = GpsEventType.NORMAL.apiValue,
                        isAutomatic = true,
                        isOffline = false
                    )
                    
                    result.fold(
                        onSuccess = {
                            Log.d(TAG, "Evento NORMAL enviado automaticamente")
                        },
                        onFailure = { error ->
                            Log.e(TAG, "Erro ao enviar evento NORMAL: ${error.message}", error)
                        }
                    )
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        normalLoopJob?.cancel()
    }
}

