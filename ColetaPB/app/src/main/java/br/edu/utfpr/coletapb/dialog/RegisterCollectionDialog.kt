package br.edu.utfpr.coletapb.dialog

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.DialogFragment
import br.edu.utfpr.coletapb.R
import br.edu.utfpr.coletapb.data.model.GpsEventType
import br.edu.utfpr.coletapb.data.repository.GpsRepository
import br.edu.utfpr.coletapb.data.local.SharedPreferencesHelper
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Dialog para registrar coleta em um ponto (POINT_COLLECTED)
 * Evento: POINT_COLLECTED
 */
class RegisterCollectionDialog : DialogFragment() {
    
    private var executionId: Long? = null
    private var pointId: Long? = null
    private var pointName: String? = null
    private var currentLocation: Location? = null
    private var photoFile: File? = null
    
    // Views
    private lateinit var txtPointName: TextInputEditText
    private lateinit var edtObservations: TextInputEditText
    private lateinit var btnAddPhoto: Button
    private lateinit var imgPhotoPreview: ImageView
    private lateinit var btnCancel: Button
    private lateinit var btnSaveCollection: Button
    
    private lateinit var gpsRepository: GpsRepository
    private lateinit var prefsHelper: SharedPreferencesHelper
    
    // Callback para quando a coleta for salva
    var onCollectionSaved: ((Long) -> Unit)? = null
    
    companion object {
        private const val TAG = "RegisterCollectionDialog"
        
        fun newInstance(
            executionId: Long,
            pointId: Long?,
            pointName: String?,
            location: Location?
        ): RegisterCollectionDialog {
            return RegisterCollectionDialog().apply {
                arguments = Bundle().apply {
                    putLong("executionId", executionId)
                    pointId?.let { putLong("pointId", it) }
                    pointName?.let { putString("pointName", it) }
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
    
    // Launcher para seleção de foto
    private val photoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handlePhotoSelection(uri)
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Usa estilo padrão do Material Design
        setStyle(STYLE_NORMAL, android.R.style.Theme_Material_Light_Dialog)
        
        arguments?.let {
            executionId = it.getLong("executionId")
            pointId = if (it.containsKey("pointId")) it.getLong("pointId") else null
            pointName = it.getString("pointName")
            
            // Recriar Location a partir dos argumentos
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
        return inflater.inflate(R.layout.dialog_register_collection, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Inicializar views
        txtPointName = view.findViewById(R.id.txtPointName)
        edtObservations = view.findViewById(R.id.edtObservations)
        btnAddPhoto = view.findViewById(R.id.btnAddPhoto)
        imgPhotoPreview = view.findViewById(R.id.imgPhotoPreview)
        btnCancel = view.findViewById(R.id.btnCancel)
        btnSaveCollection = view.findViewById(R.id.btnSaveCollection)
        
        // Preencher dados
        pointName?.let { txtPointName.setText(it) }
        
        // Listeners
        btnAddPhoto.setOnClickListener {
            openPhotoPicker()
        }
        
        btnCancel.setOnClickListener {
            dismiss()
        }
        
        btnSaveCollection.setOnClickListener {
            saveCollection()
        }
    }
    
    /**
     * Abre o seletor de foto (galeria ou câmera)
     * Por enquanto, apenas abre a galeria
     */
    private fun openPhotoPicker() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        photoPickerLauncher.launch(intent)
    }
    
    /**
     * Processa a seleção de foto
     */
    private fun handlePhotoSelection(uri: Uri) {
        try {
            // Salvar temporariamente o arquivo
            val inputStream = requireContext().contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            
            // Salvar em arquivo temporário
            photoFile = File(requireContext().cacheDir, "photo_${System.currentTimeMillis()}.jpg")
            FileOutputStream(photoFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            
            // Mostrar preview
            imgPhotoPreview.setImageBitmap(bitmap)
            imgPhotoPreview.visibility = View.VISIBLE
            
            Toast.makeText(requireContext(), "Foto adicionada", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao processar foto: ${e.message}", e)
            Toast.makeText(requireContext(), "Erro ao processar foto", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Salva a coleta no backend
     */
    private fun saveCollection() {
        if (executionId == null) {
            Toast.makeText(requireContext(), "Erro: executionId não encontrado", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (currentLocation == null) {
            Toast.makeText(requireContext(), "Erro: localização não disponível", Toast.LENGTH_SHORT).show()
            return
        }
        
        val observations = edtObservations.text?.toString()?.trim()
        
        // Calcular velocidade, heading e accuracy se disponíveis
        val speedKmh = if (currentLocation!!.hasSpeed()) {
            currentLocation!!.speed * 3.6 // m/s para km/h
        } else null
        
        val headingDegrees = if (currentLocation!!.hasBearing()) {
            currentLocation!!.bearing.toDouble()
        } else null
        
        val accuracyMeters = if (currentLocation!!.hasAccuracy()) {
            currentLocation!!.accuracy.toDouble()
        } else null
        
        // Mostrar loading
        btnSaveCollection.isEnabled = false
        btnSaveCollection.text = "Salvando..."
        
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
                        eventType = GpsEventType.POINT_COLLECTED.apiValue,
                        isAutomatic = false,
                        isOffline = false,
                        description = observations,
                        pointId = pointId,
                        photoFile = photoFile
                    )
                }
                
                result.fold(
                    onSuccess = { record ->
                        Toast.makeText(requireContext(), "Coleta registrada com sucesso!", Toast.LENGTH_SHORT).show()
                        onCollectionSaved?.invoke(record.id)
                        dismiss()
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Erro ao registrar coleta: ${error.message}", error)
                        Toast.makeText(requireContext(), "Erro ao registrar coleta: ${error.message}", Toast.LENGTH_LONG).show()
                        btnSaveCollection.isEnabled = true
                        btnSaveCollection.text = "SALVAR COLETA"
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Exceção ao registrar coleta: ${e.message}", e)
                Toast.makeText(requireContext(), "Erro: ${e.message}", Toast.LENGTH_LONG).show()
                btnSaveCollection.isEnabled = true
                btnSaveCollection.text = "SALVAR COLETA"
            }
        }
    }
}

