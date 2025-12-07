package br.edu.utfpr.coletapb.dialog

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
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
import java.io.File
import java.io.FileOutputStream

/**
 * Dialog para registrar parada (STOP ou BREAK)
 * Eventos: STOP ou BREAK
 */
class RegisterStopDialog : DialogFragment() {
    
    private var executionId: Long? = null
    private var currentLocation: Location? = null
    private var photoFile: File? = null
    
    // Views
    private lateinit var edtObservations: TextInputEditText
    private lateinit var btnAddPhoto: Button
    private lateinit var imgPhotoPreview: ImageView
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
    
    // Launcher para seleção de foto (método moderno que não requer permissões)
    private val photoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            handlePhotoSelection(it)
        }
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
        edtObservations = view.findViewById(R.id.edtObservations)
        btnAddPhoto = view.findViewById(R.id.btnAddPhoto)
        imgPhotoPreview = view.findViewById(R.id.imgPhotoPreview)
        btnCancel = view.findViewById(R.id.btnCancel)
        btnSaveStop = view.findViewById(R.id.btnSaveStop)
        
        // Listeners
        btnAddPhoto.setOnClickListener {
            openPhotoPicker()
        }
        
        btnCancel.setOnClickListener {
            dismiss()
        }
        
        btnSaveStop.setOnClickListener {
            saveStop()
        }
    }
    
    /**
     * Abre o seletor de foto (galeria)
     * Usa PickVisualMedia que não requer permissões explícitas em Android 13+
     */
    private fun openPhotoPicker() {
        val request = PickVisualMediaRequest.Builder()
            .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
            .build()
        photoPickerLauncher.launch(request)
    }
    
    /**
     * Processa a seleção de foto
     */
    private fun handlePhotoSelection(uri: Uri) {
        try {
            val inputStream = requireContext().contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            
            photoFile = File(requireContext().cacheDir, "photo_${System.currentTimeMillis()}.jpg")
            FileOutputStream(photoFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            
            imgPhotoPreview.setImageBitmap(bitmap)
            imgPhotoPreview.visibility = View.VISIBLE
            
            Toast.makeText(requireContext(), "Foto adicionada", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao processar foto: ${e.message}", e)
            Toast.makeText(requireContext(), "Erro ao processar foto", Toast.LENGTH_SHORT).show()
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
        
        // Usa STOP como tipo padrão (sem seleção de tipo)
        val eventType = GpsEventType.STOP
        
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
                        description = observations,
                        photoFile = photoFile
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

