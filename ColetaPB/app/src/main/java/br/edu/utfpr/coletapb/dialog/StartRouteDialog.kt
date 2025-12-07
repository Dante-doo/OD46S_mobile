package br.edu.utfpr.coletapb.dialog

import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import br.edu.utfpr.coletapb.R
import br.edu.utfpr.coletapb.data.model.GpsEventType
import br.edu.utfpr.coletapb.data.repository.ExecutionRepository
import br.edu.utfpr.coletapb.data.repository.GpsRepository
import br.edu.utfpr.coletapb.data.repository.AssignmentRepository
import br.edu.utfpr.coletapb.data.local.SharedPreferencesHelper
import br.edu.utfpr.coletapb.utils.PeriodicityUtils
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
    private lateinit var assignmentRepository: AssignmentRepository
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
        assignmentRepository = AssignmentRepository(prefsHelper)
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
            AlertDialog.Builder(requireContext())
                .setTitle("Erro")
                .setMessage("Erro: assignmentId não encontrado")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        
        if (currentLocation == null) {
            AlertDialog.Builder(requireContext())
                .setTitle("Erro")
                .setMessage("Erro: localização não disponível")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        
        // Mostrar loading
        btnStart.isEnabled = false
        btnStart.text = "Iniciando..."
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // 0. Validar periodicity antes de iniciar
                val assignmentResult = withContext(Dispatchers.IO) {
                    assignmentRepository.getMyAssignments()
                }
                
                val assignment = assignmentResult.getOrNull()?.find { it.id == assignmentId }
                
                if (assignment != null && !assignment.periodicity.isNullOrBlank()) {
                    val isAllowed = PeriodicityUtils.isTodayAllowed(assignment.periodicity)
                    if (!isAllowed) {
                        val allowedDays = PeriodicityUtils.formatPeriodicity(assignment.periodicity)
                        val allowedDaysList = PeriodicityUtils.getAllowedDays(assignment.periodicity)
                        val allowedDaysStr = if (allowedDaysList.isNotEmpty()) {
                            allowedDaysList.joinToString(", ")
                        } else {
                            allowedDays
                        }
                        
                        AlertDialog.Builder(requireContext())
                            .setTitle("Rota não disponível hoje")
                            .setMessage("Esta rota está ativa, mas só pode ser iniciada nos seguintes dias:\n\n$allowedDaysStr\n\nPor favor, aguarde o dia correto para iniciar a rota.")
                            .setPositiveButton("OK") { _, _ ->
                                dismiss()
                            }
                            .setCancelable(true)
                            .show()
                        
                        btnStart.isEnabled = true
                        btnStart.text = "INICIAR"
                        return@launch
                    }
                }
                
                // 1. Chamar POST /api/v1/executions/start
                Log.d(TAG, "=== INICIANDO ROTA ===")
                Log.d(TAG, "assignmentId: $assignmentId")
                Log.d(TAG, "location: lat=${currentLocation!!.latitude}, lng=${currentLocation!!.longitude}")
                
                val startResult = withContext(Dispatchers.IO) {
                    executionRepository.startExecution(
                        assignmentId = assignmentId!!,
                        startLat = currentLocation!!.latitude,
                        startLng = currentLocation!!.longitude
                    )
                }
                
                val executionId = startResult.fold(
                    onSuccess = { execution ->
                        Log.d(TAG, "✅ Execução criada no backend: id=${execution.id}")
                        execution.id
                    },
                    onFailure = { error ->
                        Log.e(TAG, "❌ Erro ao criar execução no backend: ${error.message}", error)
                        Log.e(TAG, "Erro ao iniciar execução: ${error.message}", error)
                        
                        val errorMessage = error.message ?: "Erro desconhecido ao iniciar a rota"
                        
                        // Verifica se é erro 409 (rota já executada hoje)
                        val isConflictError = errorMessage.contains("já foi executada hoje", ignoreCase = true) ||
                                             errorMessage.contains("already exists", ignoreCase = true) ||
                                             errorMessage.contains("EXECUTION_CONFLICT", ignoreCase = true)
                        
                        // Verifica se é erro de periodicity (dia não permitido)
                        val isPeriodicityError = errorMessage.contains("só pode ser iniciada", ignoreCase = true) ||
                                                errorMessage.contains("não é um dia permitido", ignoreCase = true) ||
                                                errorMessage.contains("dia permitido", ignoreCase = true)
                        
                        if (isConflictError) {
                            // Mostra mensagem amigável com AlertDialog
                            AlertDialog.Builder(requireContext())
                                .setTitle("Rota já executada")
                                .setMessage("Esta rota já foi executada hoje. Não é possível iniciar uma nova execução no mesmo dia.")
                                .setPositiveButton("OK") { _, _ ->
                                    dismiss()
                                }
                                .setCancelable(true)
                                .show()
                        } else if (isPeriodicityError) {
                            // Mostra mensagem de erro de periodicity com AlertDialog
                            AlertDialog.Builder(requireContext())
                                .setTitle("Rota não disponível hoje")
                                .setMessage(errorMessage)
                                .setPositiveButton("OK") { _, _ ->
                                    dismiss()
                                }
                                .setCancelable(true)
                                .show()
                        } else {
                            // Outro tipo de erro - mostra com AlertDialog para melhor visibilidade
                            AlertDialog.Builder(requireContext())
                                .setTitle("Erro ao iniciar rota")
                                .setMessage(errorMessage)
                                .setPositiveButton("OK", null)
                                .setCancelable(true)
                                .show()
                        }
                        
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
                
                Log.d(TAG, "Enviando evento START para execução: $executionId")
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
                    onSuccess = { record ->
                        Log.d(TAG, "✅ Evento START enviado com sucesso: id=${record.id}")
                        // 3. Iniciar loop de NORMAL a cada 15 segundos
                        startNormalLoop(executionId)
                        
                        AlertDialog.Builder(requireContext())
                            .setTitle("Sucesso")
                            .setMessage("Rota iniciada com sucesso!")
                            .setPositiveButton("OK") { _, _ ->
                                Log.d(TAG, "Callback onRouteStarted sendo chamado com executionId: $executionId")
                                onRouteStarted?.invoke(executionId)
                                dismiss()
                            }
                            .setCancelable(false)
                            .show()
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Erro ao enviar evento START: ${error.message}", error)
                        // Mesmo com erro no START, continua o fluxo (o evento foi salvo localmente)
                        // Mas mostra o erro para o usuário saber
                        AlertDialog.Builder(requireContext())
                            .setTitle("Aviso")
                            .setMessage("Rota iniciada, mas houve um problema ao enviar o evento START ao servidor: ${error.message}\n\nOs dados foram salvos localmente e serão sincronizados quando possível.")
                            .setPositiveButton("OK") { _, _ ->
                                // Continua o fluxo mesmo com erro
                                startNormalLoop(executionId)
                                onRouteStarted?.invoke(executionId)
                                dismiss()
                            }
                            .setCancelable(false)
                            .show()
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Exceção ao iniciar rota: ${e.message}", e)
                AlertDialog.Builder(requireContext())
                    .setTitle("Erro")
                    .setMessage("Erro: ${e.message}")
                    .setPositiveButton("OK", null)
                    .show()
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

