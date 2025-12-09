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
        
        // Mostrar loading
        btnStart.isEnabled = false
        btnStart.text = "Obtendo localização..."
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // 0. OBTER LOCALIZAÇÃO ATUAL (obrigatório antes de iniciar)
                Log.d(TAG, "=== OBTENDO LOCALIZAÇÃO ATUAL ===")
                val location = withContext(Dispatchers.IO) {
                    try {
                        // Tenta obter localização atual usando FusedLocationProviderClient
                        val fusedLocationClient = com.google.android.gms.location.LocationServices
                            .getFusedLocationProviderClient(requireContext())
                        
                        // Usa getCurrentLocation com PRIORITY_HIGH_ACCURACY
                        val locationResult = com.google.android.gms.tasks.Tasks.await(
                            fusedLocationClient.getCurrentLocation(
                                com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
                                null
                            )
                        )
                        
                        if (locationResult != null) {
                            Log.d(TAG, "✅ Localização obtida via FusedLocationProviderClient: lat=${locationResult.latitude}, lng=${locationResult.longitude}, accuracy=${locationResult.accuracy}m")
                            locationResult
                        } else {
                            Log.w(TAG, "⚠️ getCurrentLocation retornou null, tentando usar currentLocation do dialog")
                            // Se getCurrentLocation retornou null, tenta usar a localização já disponível no dialog
                            if (currentLocation != null) {
                                Log.d(TAG, "Usando currentLocation do dialog: lat=${currentLocation!!.latitude}, lng=${currentLocation!!.longitude}")
                                currentLocation
                            } else {
                                null
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Erro ao obter localização via FusedLocationProviderClient: ${e.message}", e)
                        // Se falhar, tenta usar a localização já disponível no dialog
                        if (currentLocation != null) {
                            Log.d(TAG, "Usando currentLocation do dialog como fallback: lat=${currentLocation!!.latitude}, lng=${currentLocation!!.longitude}")
                            currentLocation
                        } else {
                            null
                        }
                    }
                }
                
                if (location == null) {
                    Log.e(TAG, "❌ Não foi possível obter localização atual")
                    AlertDialog.Builder(requireContext())
                        .setTitle("Erro de Localização")
                        .setMessage("Não foi possível obter sua localização atual.\n\n" +
                                "Por favor, verifique:\n" +
                                "• Se o GPS está habilitado\n" +
                                "• Se as permissões de localização foram concedidas\n" +
                                "• Se você está em uma área com sinal GPS\n\n" +
                                "Tente novamente após verificar essas condições.")
                        .setPositiveButton("OK") { _, _ ->
                            btnStart.isEnabled = true
                            btnStart.text = "INICIAR"
                        }
                        .setCancelable(true)
                        .show()
                    return@launch
                }
                
                // Atualiza currentLocation com a localização obtida
                currentLocation = location
                Log.d(TAG, "✅ Localização confirmada: lat=${location.latitude}, lng=${location.longitude}, accuracy=${location.accuracy}m")
                
                btnStart.text = "Iniciando..."
                
                // 1. Validar periodicity antes de iniciar
                val assignmentResult = withContext(Dispatchers.IO) {
                    assignmentRepository.getMyAssignments()
                }
                
                val assignment = assignmentResult.getOrNull()?.find { it.id == assignmentId }
                
                if (assignment != null && !assignment.periodicity.isNullOrBlank()) {
                    val periodicity = assignment.periodicity
                    Log.d(TAG, "Validando periodicity: $periodicity")
                    
                    val isAllowed = PeriodicityUtils.isTodayAllowed(periodicity)
                    Log.d(TAG, "Periodicity validation result: isAllowed=$isAllowed")
                    
                    if (!isAllowed) {
                        val allowedDays = PeriodicityUtils.formatPeriodicity(periodicity)
                        val allowedDaysList = PeriodicityUtils.getAllowedDays(periodicity)
                        val allowedDaysStr = if (allowedDaysList.isNotEmpty()) {
                            allowedDaysList.joinToString(", ")
                        } else {
                            allowedDays
                        }
                        
                        Log.w(TAG, "Rota não pode ser iniciada hoje. Dias permitidos: $allowedDaysStr")
                        
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
                    } else {
                        Log.d(TAG, "Periodicity validation passed, continuando com início da rota")
                    }
                } else {
                    Log.d(TAG, "Nenhuma periodicity definida ou assignment não encontrado, permitindo início")
                }
                
                // 2. Chamar POST /api/v1/executions/start
                Log.d(TAG, "=== INICIANDO ROTA NO BACKEND ===")
                Log.d(TAG, "assignmentId: $assignmentId")
                Log.d(TAG, "location: lat=${location.latitude}, lng=${location.longitude}")
                
                val startResult = withContext(Dispatchers.IO) {
                    executionRepository.startExecution(
                        assignmentId = assignmentId!!,
                        startLat = location.latitude,
                        startLng = location.longitude
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
                
                // 3. Enviar evento START para /api/v1/executions/:executionId/gps
                // OBRIGATÓRIO: Se falhar, a rota deve ser cancelada
                val speedKmh = if (location.hasSpeed()) {
                    location.speed * 3.6
                } else null
                
                val headingDegrees = if (location.hasBearing()) {
                    location.bearing.toDouble()
                } else null
                
                val accuracyMeters = if (location.hasAccuracy()) {
                    location.accuracy.toDouble()
                } else null
                
                Log.d(TAG, "=== ENVIANDO EVENTO START ===")
                Log.d(TAG, "executionId: $executionId")
                Log.d(TAG, "latitude: ${location.latitude}, longitude: ${location.longitude}")
                Log.d(TAG, "speedKmh: $speedKmh, headingDegrees: $headingDegrees, accuracyMeters: $accuracyMeters")
                Log.d(TAG, "eventType: ${GpsEventType.START.apiValue}")
                Log.d(TAG, "isAutomatic: false, isOffline: false")
                
                btnStart.text = "Registrando início..."
                
                val startEventResult = withContext(Dispatchers.IO) {
                    try {
                        Log.d(TAG, "Chamando gpsRepository.registerGpsPosition()...")
                        val result = gpsRepository.registerGpsPosition(
                            executionId = executionId,
                            latitude = location.latitude,
                            longitude = location.longitude,
                            speedKmh = speedKmh,
                            headingDegrees = headingDegrees,
                            accuracyMeters = accuracyMeters,
                            eventType = GpsEventType.START.apiValue,
                            isAutomatic = false,
                            isOffline = false,
                            description = "Início da coleta"
                        )
                        Log.d(TAG, "gpsRepository.registerGpsPosition() retornou: $result")
                        result
                    } catch (e: Exception) {
                        Log.e(TAG, "Exceção ao chamar registerGpsPosition: ${e.message}", e)
                        Result.failure(e)
                    }
                }
                
                Log.d(TAG, "startEventResult recebido, processando...")
                startEventResult.fold(
                    onSuccess = { record ->
                        Log.d(TAG, "✅ Evento START enviado com sucesso: id=${record.id}, eventType=${record.eventType}")
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
                        Log.e(TAG, "❌ Erro ao enviar evento START: ${error.message}", error)
                        Log.e(TAG, "Stack trace:", error)
                        
                        // OBRIGATÓRIO: Se o START não foi registrado, a rota DEVE ser cancelada
                        // Cancela a execução criada no backend para evitar estado inconsistente
                        var cancelSuccess = false
                        withContext(Dispatchers.IO) {
                            try {
                                Log.d(TAG, "Cancelando execução $executionId devido a falha no registro do evento START...")
                                val cancelResult = executionRepository.cancelExecution(
                                    executionId, 
                                    "Erro ao registrar evento START: ${error.message}"
                                )
                                cancelResult.fold(
                                    onSuccess = {
                                        Log.d(TAG, "✅ Execução cancelada no backend devido a falha no START")
                                        cancelSuccess = true
                                    },
                                    onFailure = { cancelError ->
                                        Log.e(TAG, "❌ Erro ao cancelar execução: ${cancelError.message}", cancelError)
                                        cancelSuccess = false
                                    }
                                )
                            } catch (cancelError: Exception) {
                                Log.e(TAG, "❌ Exceção ao cancelar execução: ${cancelError.message}", cancelError)
                                cancelSuccess = false
                            }
                        }
                        
                        // Notifica o usuário sobre o problema com localização
                        val cancelMessage = if (cancelSuccess) {
                            "A rota foi cancelada automaticamente."
                        } else {
                            "Atenção: Não foi possível cancelar a rota automaticamente. Entre em contato com o suporte."
                        }
                        
                        AlertDialog.Builder(requireContext())
                            .setTitle("Erro ao Iniciar Rota")
                            .setMessage("Não foi possível registrar o evento de início da rota no servidor.\n\n" +
                                    "Possíveis causas:\n" +
                                    "• Problema com a localização GPS\n" +
                                    "• Falha na conexão com o servidor\n" +
                                    "• Erro: ${error.message}\n\n" +
                                    "$cancelMessage\n\n" +
                                    "Por favor, verifique sua conexão e localização GPS, e tente novamente.")
                            .setPositiveButton("OK") { _, _ ->
                                // Não chama onRouteStarted - a rota não foi iniciada
                                // Não inicia o loop de NORMAL
                                // Permite que o usuário tente novamente
                            }
                            .setCancelable(true)
                            .show()
                        
                        // Reabilita o botão para permitir nova tentativa
                        btnStart.isEnabled = true
                        btnStart.text = "INICIAR"
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


