package br.edu.utfpr.coletapb.dialog

import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import br.edu.utfpr.coletapb.R
import br.edu.utfpr.coletapb.data.model.GpsEventType
import br.edu.utfpr.coletapb.data.repository.ExecutionRepository
import br.edu.utfpr.coletapb.data.repository.GpsRepository
import br.edu.utfpr.coletapb.data.local.SharedPreferencesHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dialog para encerrar rota (END)
 * Evento: END
 * Após enviar END, chama PATCH /api/v1/executions/{id}/complete
 */
class EndRouteDialog : DialogFragment() {
    
    private var executionId: Long? = null
    private var currentLocation: Location? = null
    
    // Views
    private lateinit var btnCancel: Button
    private lateinit var btnEndRoute: Button
    
    private lateinit var executionRepository: ExecutionRepository
    private lateinit var gpsRepository: GpsRepository
    private lateinit var prefsHelper: SharedPreferencesHelper
    
    // Callback para quando a rota for encerrada
    var onRouteEnded: (() -> Unit)? = null
    
    // Callback para parar o loop de NORMAL (deve ser chamado pela activity)
    var onStopNormalLoop: (() -> Unit)? = null
    
    companion object {
        private const val TAG = "EndRouteDialog"
        
        fun newInstance(
            executionId: Long,
            location: Location?
        ): EndRouteDialog {
            return EndRouteDialog().apply {
                arguments = Bundle().apply {
                    putLong("executionId", executionId)
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
            executionId = it.getLong("executionId")
            
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
        return inflater.inflate(R.layout.dialog_end_route, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Inicializar views
        btnCancel = view.findViewById(R.id.btnCancel)
        btnEndRoute = view.findViewById(R.id.btnEndRoute)
        
        // Listeners
        btnCancel.setOnClickListener {
            dismiss()
        }
        
        btnEndRoute.setOnClickListener {
            endRoute()
        }
    }
    
    /**
     * Encerra a rota
     */
    private fun endRoute() {
        if (executionId == null) {
            Toast.makeText(requireContext(), "Erro: executionId não encontrado", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (currentLocation == null) {
            Toast.makeText(requireContext(), "Erro: localização não disponível", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Parar o loop de NORMAL
        onStopNormalLoop?.invoke()
        
        // Mostrar loading
        btnEndRoute.isEnabled = false
        btnEndRoute.text = "Encerrando..."
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // 1. Enviar evento END
                val speedKmh = if (currentLocation!!.hasSpeed()) {
                    currentLocation!!.speed * 3.6
                } else null
                
                val headingDegrees = if (currentLocation!!.hasBearing()) {
                    currentLocation!!.bearing.toDouble()
                } else null
                
                val accuracyMeters = if (currentLocation!!.hasAccuracy()) {
                    currentLocation!!.accuracy.toDouble()
                } else null
                
                val endEventResult = withContext(Dispatchers.IO) {
                    gpsRepository.registerGpsPosition(
                        executionId = executionId!!,
                        latitude = currentLocation!!.latitude,
                        longitude = currentLocation!!.longitude,
                        speedKmh = speedKmh,
                        headingDegrees = headingDegrees,
                        accuracyMeters = accuracyMeters,
                        eventType = GpsEventType.END.apiValue,
                        isAutomatic = false,
                        isOffline = false,
                        description = "Fim da coleta"
                    )
                }
                
                endEventResult.fold(
                    onSuccess = {
                        // 2. Chamar PATCH /api/v1/executions/{id}/complete
                        val completeResult = withContext(Dispatchers.IO) {
                            executionRepository.completeExecution(
                                executionId = executionId!!,
                                endLat = currentLocation!!.latitude,
                                endLng = currentLocation!!.longitude,
                                notes = "Rota finalizada"
                            )
                        }
                        
                        completeResult.fold(
                            onSuccess = {
                                Toast.makeText(requireContext(), "Rota encerrada com sucesso!", Toast.LENGTH_SHORT).show()
                                onRouteEnded?.invoke()
                                dismiss()
                            },
                            onFailure = { error ->
                                Log.e(TAG, "Erro ao finalizar execução: ${error.message}", error)
                                Toast.makeText(requireContext(), "Erro ao finalizar execução: ${error.message}", Toast.LENGTH_LONG).show()
                                btnEndRoute.isEnabled = true
                                btnEndRoute.text = "ENCERRAR ROTA"
                            }
                        )
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Erro ao enviar evento END: ${error.message}", error)
                        Toast.makeText(requireContext(), "Erro ao enviar evento END: ${error.message}", Toast.LENGTH_LONG).show()
                        btnEndRoute.isEnabled = true
                        btnEndRoute.text = "ENCERRAR ROTA"
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Exceção ao encerrar rota: ${e.message}", e)
                Toast.makeText(requireContext(), "Erro: ${e.message}", Toast.LENGTH_LONG).show()
                btnEndRoute.isEnabled = true
                btnEndRoute.text = "ENCERRAR ROTA"
            }
        }
    }
}

