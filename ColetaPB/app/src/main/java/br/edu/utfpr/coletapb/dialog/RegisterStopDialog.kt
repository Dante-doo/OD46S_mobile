package br.edu.utfpr.coletapb.dialog

import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import br.edu.utfpr.coletapb.R
import br.edu.utfpr.coletapb.data.model.GpsEventType
import br.edu.utfpr.coletapb.data.repository.GpsRepository
import br.edu.utfpr.coletapb.data.local.SharedPreferencesHelper
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dialog para registrar parada (STOP ou BREAK)
 * Eventos: STOP ou BREAK
 */
class RegisterStopDialog : DialogFragment() {
    
    private var executionId: Long? = null
    private var currentLocation: Location? = null
    
    // Views
    private lateinit var radioGroupStopType: RadioGroup
    private lateinit var radioStop: RadioButton
    private lateinit var radioBreak: RadioButton
    private lateinit var edtObservations: TextInputEditText
    private lateinit var btnCancel: Button
    private lateinit var btnSaveStop: Button
    
    private lateinit var gpsRepository: GpsRepository
    private lateinit var prefsHelper: SharedPreferencesHelper
    
    // Callback para quando a parada for salva
    var onStopSaved: ((Long) -> Unit)? = null
    
    companion object {
        private const val TAG = "RegisterStopDialog"
        
        fun newInstance(
            executionId: Long,
            location: Location?
        ): RegisterStopDialog {
            return RegisterStopDialog().apply {
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
        gpsRepository = GpsRepository(prefsHelper)
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_register_stop_new, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Inicializar views
        radioGroupStopType = view.findViewById(R.id.radioGroupStopType)
        radioStop = view.findViewById(R.id.radioStop)
        radioBreak = view.findViewById(R.id.radioBreak)
        edtObservations = view.findViewById(R.id.edtObservations)
        btnCancel = view.findViewById(R.id.btnCancel)
        btnSaveStop = view.findViewById(R.id.btnSaveStop)
        
        // Por padrão, "Parada rápida" (STOP) está selecionado
        radioStop.isChecked = true
        
        // Listeners
        btnCancel.setOnClickListener {
            dismiss()
        }
        
        btnSaveStop.setOnClickListener {
            saveStop()
        }
    }
    
    /**
     * Salva a parada no backend
     */
    private fun saveStop() {
        if (executionId == null || currentLocation == null) {
            Toast.makeText(requireContext(), "Erro: dados incompletos", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Determinar tipo de evento baseado na seleção
        val eventType = when {
            radioStop.isChecked -> GpsEventType.STOP
            radioBreak.isChecked -> GpsEventType.BREAK
            else -> GpsEventType.STOP // Default
        }
        
        val observations = edtObservations.text?.toString()?.trim()
        
        // Calcular velocidade, heading e accuracy se disponíveis
        val speedKmh = if (currentLocation!!.hasSpeed()) {
            currentLocation!!.speed * 3.6
        } else null
        
        val headingDegrees = if (currentLocation!!.hasBearing()) {
            currentLocation!!.bearing.toDouble()
        } else null
        
        val accuracyMeters = if (currentLocation!!.hasAccuracy()) {
            currentLocation!!.accuracy.toDouble()
        } else null
        
        // Mostrar loading
        btnSaveStop.isEnabled = false
        btnSaveStop.text = "Registrando..."
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    gpsRepository.registerGpsPosition(
                        executionId = executionId!!,
                        latitude = currentLocation!!.latitude,
                        longitude = currentLocation!!.longitude,
                        speedKmh = speedKmh,
                        headingDegrees = headingDegrees,
                        accuracyMeters = accuracyMeters,
                        eventType = eventType.apiValue,
                        isAutomatic = false,
                        isOffline = false,
                        description = observations
                    )
                }
                
                result.fold(
                    onSuccess = { record ->
                        Toast.makeText(requireContext(), "Parada registrada com sucesso!", Toast.LENGTH_SHORT).show()
                        onStopSaved?.invoke(record.id)
                        dismiss()
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Erro ao registrar parada: ${error.message}", error)
                        Toast.makeText(requireContext(), "Erro ao registrar parada: ${error.message}", Toast.LENGTH_LONG).show()
                        btnSaveStop.isEnabled = true
                        btnSaveStop.text = "REGISTRAR PARADA"
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Exceção ao registrar parada: ${e.message}", e)
                Toast.makeText(requireContext(), "Erro: ${e.message}", Toast.LENGTH_LONG).show()
                btnSaveStop.isEnabled = true
                btnSaveStop.text = "REGISTRAR PARADA"
            }
        }
    }
}

