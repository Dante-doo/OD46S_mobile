package br.edu.utfpr.coletapb.dialog

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.net.Uri
import android.os.Build
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
import androidx.activity.result.PickVisualMediaRequest
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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
    private var cameraPhotoUri: Uri? = null
    
    // Views
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
    
    // Launcher para seleção de foto da galeria (método moderno que não requer permissões)
    private val photoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            handlePhotoSelection(it)
        }
    }
    
    // Launcher para solicitar permissão de câmera
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            openCameraInternal()
        } else {
            Toast.makeText(requireContext(), "Permissão de câmera necessária para tirar fotos", Toast.LENGTH_LONG).show()
        }
    }
    
    // Launcher para tirar foto com a câmera
    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            cameraPhotoUri?.let { uri ->
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
        edtProblemDescription = view.findViewById(R.id.edtProblemDescription)
        btnAddPhoto = view.findViewById(R.id.btnAddPhoto)
        imgPhotoPreview = view.findViewById(R.id.imgPhotoPreview)
        btnCancel = view.findViewById(R.id.btnCancel)
        btnSaveProblem = view.findViewById(R.id.btnSaveProblem)
        
        // Listeners
        btnAddPhoto.setOnClickListener {
            showPhotoSourceDialog()
        }
        
        btnCancel.setOnClickListener {
            dismiss()
        }
        
        btnSaveProblem.setOnClickListener {
            saveProblem()
        }
    }
    
    /**
     * Mostra diálogo para escolher entre câmera ou galeria
     */
    private fun showPhotoSourceDialog() {
        val options = arrayOf("Tirar foto", "Escolher da galeria")
        AlertDialog.Builder(requireContext())
            .setTitle("Adicionar foto")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openCamera()
                    1 -> openGallery()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    /**
     * Abre a câmera para tirar foto (verifica permissão primeiro)
     */
    private fun openCamera() {
        // Verificar se a permissão já foi concedida
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCameraInternal()
        } else {
            // Solicitar permissão
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    
    /**
     * Abre a câmera internamente (após verificar permissão)
     */
    private fun openCameraInternal() {
        try {
            // Criar arquivo temporário para a foto
            val photoFile = File(requireContext().getExternalFilesDir(null), "photo_${System.currentTimeMillis()}.jpg")
            cameraPhotoUri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                photoFile
            )
            
            cameraLauncher.launch(cameraPhotoUri)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao abrir câmera: ${e.message}", e)
            Toast.makeText(requireContext(), "Erro ao abrir câmera", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Abre o seletor de foto da galeria
     * Usa PickVisualMedia que não requer permissões explícitas em Android 13+
     */
    private fun openGallery() {
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
     * Salva o problema no backend
     */
    private fun saveProblem() {
        // Observações são opcionais
        val description = edtProblemDescription.text?.toString()?.trim()
        
        // Usa currentPointId diretamente (se disponível)
        val selectedPointId = currentPointId
        
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

