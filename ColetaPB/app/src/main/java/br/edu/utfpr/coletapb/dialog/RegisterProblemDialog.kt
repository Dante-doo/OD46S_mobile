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
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Dialog para registrar problema (PROBLEM)
 * Evento: PROBLEM
 */
class RegisterProblemDialog : DialogFragment() {
    
    private var executionId: Long? = null
    private var currentPointId: Long? = null
    private var currentLocation: Location? = null
    private var availablePoints: List<PointOption> = emptyList()
    private var photoFile: File? = null
    
    // Views
    private lateinit var spinnerPointName: AutoCompleteTextView
    private lateinit var edtProblemDescription: TextInputEditText
    private lateinit var btnAddPhoto: Button
    private lateinit var imgPhotoPreview: ImageView
    private lateinit var btnCancel: Button
    private lateinit var btnSaveProblem: Button
    
    private lateinit var gpsRepository: GpsRepository
    private lateinit var prefsHelper: SharedPreferencesHelper
    
    // Callback para quando o problema for salvo
    var onProblemSaved: ((Long) -> Unit)? = null
    
    data class PointOption(val id: Long, val name: String)
    
    companion object {
        private const val TAG = "RegisterProblemDialog"
        
        fun newInstance(
            executionId: Long,
            currentPointId: Long?,
            currentPointName: String?,
            location: Location?,
            availablePoints: List<PointOption> = emptyList()
        ): RegisterProblemDialog {
            return RegisterProblemDialog().apply {
                arguments = Bundle().apply {
                    putLong("executionId", executionId)
                    currentPointId?.let { putLong("currentPointId", it) }
                    currentPointName?.let { putString("currentPointName", it) }
                    location?.let {
                        putDouble("latitude", it.latitude)
                        putDouble("longitude", it.longitude)
                        it.speed.let { speed -> if (speed > 0) putFloat("speed", speed) }
                        it.bearing.let { bearing -> if (bearing > 0) putFloat("bearing", bearing) }
                        it.accuracy.let { accuracy -> if (accuracy > 0) putFloat("accuracy", accuracy) }
                    }
                    // Salvar lista de pontos (simplificado - pode melhorar depois)
                    putInt("pointsCount", availablePoints.size)
                    availablePoints.forEachIndexed { index, point ->
                        putLong("point_${index}_id", point.id)
                        putString("point_${index}_name", point.name)
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
            currentPointId = if (it.containsKey("currentPointId")) it.getLong("currentPointId") else null
            
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
            
            // Recriar lista de pontos
            val pointsCount = it.getInt("pointsCount", 0)
            availablePoints = (0 until pointsCount).map { index ->
                PointOption(
                    id = it.getLong("point_${index}_id"),
                    name = it.getString("point_${index}_name") ?: ""
                )
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
        return inflater.inflate(R.layout.dialog_register_problem_new, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Inicializar views
        spinnerPointName = view.findViewById(R.id.spinnerPointName)
        edtProblemDescription = view.findViewById(R.id.edtProblemDescription)
        btnAddPhoto = view.findViewById(R.id.btnAddPhoto)
        imgPhotoPreview = view.findViewById(R.id.imgPhotoPreview)
        btnCancel = view.findViewById(R.id.btnCancel)
        btnSaveProblem = view.findViewById(R.id.btnSaveProblem)
        
        // Configurar spinner de pontos
        if (availablePoints.isNotEmpty()) {
            val adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                availablePoints.map { it.name }
            )
            spinnerPointName.setAdapter(adapter)
            
            // Selecionar ponto atual se disponível
            currentPointId?.let { pointId ->
                availablePoints.find { it.id == pointId }?.let { point ->
                    spinnerPointName.setText(point.name, false)
                }
            }
        } else {
            // Se não tem lista, usar o ponto atual como texto fixo
            arguments?.getString("currentPointName")?.let {
                spinnerPointName.setText(it, false)
            }
        }
        
        // Listeners
        btnAddPhoto.setOnClickListener {
            openPhotoPicker()
        }
        
        btnCancel.setOnClickListener {
            dismiss()
        }
        
        btnSaveProblem.setOnClickListener {
            saveProblem()
        }
    }
    
    /**
     * Abre o seletor de foto
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
     * Salva o problema no backend
     */
    private fun saveProblem() {
        // Validações
        val description = edtProblemDescription.text?.toString()?.trim()
        if (description.isNullOrEmpty()) {
            edtProblemDescription.error = "Descrição do problema é obrigatória"
            return
        }
        
        val selectedPointName = spinnerPointName.text?.toString()?.trim()
        if (selectedPointName.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "Selecione um ponto de coleta", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Encontrar pointId selecionado
        val selectedPointId = availablePoints.find { it.name == selectedPointName }?.id ?: currentPointId
        
        if (executionId == null || currentLocation == null) {
            Toast.makeText(requireContext(), "Erro: dados incompletos", Toast.LENGTH_SHORT).show()
            return
        }
        
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
        btnSaveProblem.isEnabled = false
        btnSaveProblem.text = "Salvando..."
        
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
                        eventType = GpsEventType.PROBLEM.apiValue,
                        isAutomatic = false,
                        isOffline = false,
                        description = description,
                        pointId = selectedPointId,
                        photoFile = photoFile
                    )
                }
                
                result.fold(
                    onSuccess = { record ->
                        Toast.makeText(requireContext(), "Problema registrado com sucesso!", Toast.LENGTH_SHORT).show()
                        onProblemSaved?.invoke(record.id)
                        dismiss()
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Erro ao registrar problema: ${error.message}", error)
                        Toast.makeText(requireContext(), "Erro ao registrar problema: ${error.message}", Toast.LENGTH_LONG).show()
                        btnSaveProblem.isEnabled = true
                        btnSaveProblem.text = "SALVAR PROBLEMA"
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Exceção ao registrar problema: ${e.message}", e)
                Toast.makeText(requireContext(), "Erro: ${e.message}", Toast.LENGTH_LONG).show()
                btnSaveProblem.isEnabled = true
                btnSaveProblem.text = "SALVAR PROBLEMA"
            }
        }
    }
}

