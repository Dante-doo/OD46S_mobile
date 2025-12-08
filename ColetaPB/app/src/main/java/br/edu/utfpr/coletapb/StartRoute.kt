package br.edu.utfpr.coletapb

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import br.edu.utfpr.coletapb.data.AppDatabase
import br.edu.utfpr.coletapb.data.dao.ExecutionDao
import br.edu.utfpr.coletapb.data.dao.GpsDao
import br.edu.utfpr.coletapb.data.model.CompleteExecutionRequest
import br.edu.utfpr.coletapb.data.model.ExecutionLocal
import br.edu.utfpr.coletapb.data.model.GpsRecordLocal
import br.edu.utfpr.coletapb.data.model.GpsRecordRequest
import br.edu.utfpr.coletapb.data.model.StartExecutionRequest
import br.edu.utfpr.coletapb.data.remote.RetrofitClient
import com.google.android.gms.location.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StartRoute : AppCompatActivity() {

    private lateinit var btStart: Button
    private lateinit var btFinish: Button
    private lateinit var btActions: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvHeader: TextView
    private lateinit var tvSub: TextView

    private var routeStarted = false
    private var execLocalId: Long = 0L
    private var currentAssignmentId: Long? = null
    private var routeId: Long = 0L
    private var routeName: String? = null

    private lateinit var db: AppDatabase
    private lateinit var executionDao: ExecutionDao
    private lateinit var gpsDao: GpsDao
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var lastLocation: Location? = null

    private var currentPhotoPath: String? = null
    private var pendingRecord: GpsRecordLocal? = null
    private var pendingEventType: String? = null
    private var pendingDesc: String? = null
    private var pendingPointId: Long? = null
    private var pendingWeight: Double? = null
    private var pendingCondition: String? = null

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && currentPhotoPath != null) {
            val recordToSave = pendingRecord ?: GpsRecordLocal(
                executionLocalId = execLocalId,
                timestamp = System.currentTimeMillis(),
                lat = lastLocation?.latitude ?: 0.0,
                lng = lastLocation?.longitude ?: 0.0,
                eventType = pendingEventType ?: "PROBLEM",
                description = pendingDesc,
                pointId = pendingPointId,
                collectedWeight = pendingWeight,
                pointCondition = pendingCondition
            )
            saveRecordToDb(recordToSave.copy(photoPath = currentPhotoPath))
        } else {
            Toast.makeText(this, "Foto cancelada", Toast.LENGTH_SHORT).show()
        }
        clearPendingData()
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            showKmDialog(isStart = true)
        } else {
            Toast.makeText(this, "Permissão de GPS necessária!", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_start_route)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Execução da Rota"

        routeId = intent.getLongExtra("route_id", 0L)
        routeName = intent.getStringExtra("route_name")

        tvHeader = findViewById(R.id.tvHeader)
        tvSub = findViewById(R.id.tvSub)
        tvStatus = tvSub
        btStart = findViewById(R.id.btStart)
        btFinish = findViewById(R.id.btFinish)
        btActions = findViewById(R.id.btIncident)

        tvHeader.text = routeName ?: "Rota"
        btActions.text = "REGISTRAR AÇÃO"

        db = AppDatabase.getDatabase(this)
        executionDao = db.executionDao()
        gpsDao = db.gpsDao()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        setupLocationCallback()

        if (savedInstanceState != null) {
            restoreInstanceState(savedInstanceState)
        } else {
            checkForActiveExecution()
        }

        applyUiState()
        fetchAssignment()

        btStart.setOnClickListener {
            if (currentAssignmentId == null) {
                fetchAssignment()
                Toast.makeText(this, "Buscando escala...", Toast.LENGTH_SHORT).show()
            } else {
                checkPermissionsAndShowDialog()
            }
        }

        btFinish.setOnClickListener { showKmDialog(isStart = false) }
        btActions.setOnClickListener { showActionMenu() }
    }

    private fun checkForActiveExecution() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = RetrofitClient.getApiService(applicationContext).getMyCurrentExecution()

                if (response.isSuccessful) {
                    val execDto = response.body()?.data?.execution
                    if (execDto != null && execDto.status == "IN_PROGRESS") {
                        Log.d("StartRoute", "Recuperado do servidor: ID ${execDto.id}")

                        val existingLocal = executionDao.getByServerId(execDto.id)
                        if (existingLocal != null) {
                            execLocalId = existingLocal.localId
                        } else {
                            val newLocal = ExecutionLocal(
                                routeId = routeId,
                                serverExecutionId = execDto.id,
                                assignmentId = execDto.assignment_id ?: 0L,
                                initialKm = execDto.initial_km ?: 0,
                                status = "IN_PROGRESS",
                                startLat = 0.0,
                                startLng = 0.0,
                                startTimestamp = System.currentTimeMillis()
                            )
                            execLocalId = executionDao.insert(newLocal)
                        }
                        currentAssignmentId = execDto.assignment_id
                        routeStarted = true

                        withContext(Dispatchers.Main) {
                            applyUiState()
                            startLocationUpdates()
                            Toast.makeText(this@StartRoute, "Sincronizado com execução ativa!", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }
                } else {
                    val errorBody = response.errorBody()?.string()
                    if (errorBody != null && errorBody.contains("NO_ACTIVE_EXECUTION")) {
                        Log.d("StartRoute", "API confirmou: Nenhuma execução ativa.")
                        return@launch
                    }
                }
            } catch (e: Exception) {
                Log.e("StartRoute", "Erro de conexão (Offline?): ${e.message}")
            }

            val localActive = executionDao.getActiveExecution()
            if (localActive != null) {
                Log.d("StartRoute", "Recuperado localmente (Offline): ID ${localActive.localId}")
                execLocalId = localActive.localId
                currentAssignmentId = localActive.assignmentId
                routeStarted = true
                withContext(Dispatchers.Main) {
                    applyUiState()
                    startLocationUpdates()
                    Toast.makeText(this@StartRoute, "Modo Offline: Rota recuperada!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun restoreInstanceState(savedInstanceState: Bundle) {
        routeStarted = savedInstanceState.getBoolean("route_started")
        execLocalId = savedInstanceState.getLong("exec_local_id")
        if (currentAssignmentId == null) {
            currentAssignmentId = savedInstanceState.getLong("assignment_id").takeIf { it != 0L }
        }
        currentPhotoPath = savedInstanceState.getString("photo_path")
        pendingEventType = savedInstanceState.getString("pending_type")
        pendingDesc = savedInstanceState.getString("pending_desc")
        if (savedInstanceState.containsKey("pending_point_id"))
            pendingPointId = savedInstanceState.getLong("pending_point_id")
        if (savedInstanceState.containsKey("pending_weight"))
            pendingWeight = savedInstanceState.getDouble("pending_weight")
        pendingCondition = savedInstanceState.getString("pending_cond")

        if (routeStarted) startLocationUpdates()
    }

    private fun clearPendingData() {
        pendingRecord = null
        currentPhotoPath = null
        pendingEventType = null
        pendingDesc = null
        pendingPointId = null
        pendingWeight = null
        pendingCondition = null
    }

    private fun startRouteWithLocation(initialKm: Int) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Sem permissão de GPS", Toast.LENGTH_SHORT).show()
            return
        }
        startLocationUpdates()
        Toast.makeText(this, "Iniciando...", Toast.LENGTH_SHORT).show()
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                val lat = loc?.latitude ?: 0.0
                val lng = loc?.longitude ?: 0.0
                createExecution(lat, lng, initialKm)
            }.addOnFailureListener {
                createExecution(0.0, 0.0, initialKm)
            }
        } catch (e: SecurityException) {
            createExecution(0.0, 0.0, initialKm)
        }
    }

    private fun createExecution(lat: Double, lng: Double, initialKm: Int) {
        if (routeStarted) return
        lifecycleScope.launch(Dispatchers.IO) {
            var serverId: Long? = null
            try {
                if (currentAssignmentId != null) {
                    val req = StartExecutionRequest(
                        assignment_id = currentAssignmentId!!,
                        initial_km = initialKm,
                        latitude = lat,
                        longitude = lng,
                        initial_notes = "Iniciado pelo App"
                    )
                    val res = RetrofitClient.getApiService(applicationContext).startExecution(req)
                    if (res.isSuccessful && res.body() != null) {
                        serverId = res.body()?.data?.execution?.id
                    } else {
                        val errorBody = res.errorBody()?.string() ?: "Sem corpo de erro"
                        Log.e("StartRoute", "Erro API: " + res.code() + " - " + errorBody)
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }

            if (serverId != null) {
                val now = System.currentTimeMillis()
                val newLocal = ExecutionLocal(
                    routeId = routeId,
                    serverExecutionId = serverId,
                    assignmentId = currentAssignmentId ?: 0L,
                    initialKm = initialKm,
                    status = "IN_PROGRESS",
                    startLat = lat,
                    startLng = lng,
                    startTimestamp = now
                )
                execLocalId = executionDao.insert(newLocal)

                gpsDao.insert(GpsRecordLocal(
                    executionLocalId = execLocalId,
                    timestamp = now,
                    lat = lat,
                    lng = lng,
                    eventType = "START"
                ))

                withContext(Dispatchers.Main) {
                    routeStarted = true
                    applyUiState()
                    Toast.makeText(this@StartRoute, "Rota Iniciada!", Toast.LENGTH_SHORT).show()
                }
            } else {
                withContext(Dispatchers.Main) {
                    stopLocationUpdates()
                    Toast.makeText(this@StartRoute, "Falha ao iniciar rota no servidor.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showActionMenu() {
        val options = arrayOf(
            "Coleta em Ponto (Sucesso)",
            "Ponto Não Coletado (Pulado)",
            "Problema no Ponto",
            "Problema Geral / Via",
            "Abastecimento",
            "Parada Almoço"
        )
        AlertDialog.Builder(this)
            .setTitle("Registrar Ação")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showPointDialog("POINT_COLLECTED")
                    1 -> showPointDialog("POINT_SKIPPED")
                    2 -> showPointDialog("POINT_PROBLEM")
                    3 -> showGenericDialog("PROBLEM")
                    4 -> showGenericDialog("FUEL")
                    5 -> showGenericDialog("LUNCH")
                }
            }
            .show()
    }

    private fun showPointDialog(eventType: String) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_point_action, null)
        val etPointId = view.findViewById<EditText>(R.id.etPointId)
        val etWeight = view.findViewById<EditText>(R.id.etWeight)
        val spCondition = view.findViewById<Spinner>(R.id.spCondition)
        val etDesc = view.findViewById<EditText>(R.id.etDescription)
        val conditions = arrayOf("NORMAL", "SATURATED", "DAMAGED", "INACCESSIBLE")
        spCondition.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, conditions)

        AlertDialog.Builder(this)
            .setTitle("Dados do Ponto")
            .setView(view)
            .setPositiveButton("Salvar") { _, _ ->
                val idText = etPointId.text.toString()
                if (idText.isEmpty()) {
                    Toast.makeText(this, "ID do Ponto é obrigatório!", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                prepareRecord(
                    eventType = eventType,
                    description = etDesc.text.toString(),
                    pointId = idText.toLongOrNull(),
                    weight = etWeight.text.toString().toDoubleOrNull(),
                    condition = spCondition.selectedItem.toString()
                )
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showGenericDialog(eventType: String) {
        val input = EditText(this)
        input.hint = "Descrição (Opcional)"
        AlertDialog.Builder(this)
            .setTitle("Registro")
            .setView(input)
            .setPositiveButton("Salvar") { _, _ ->
                prepareRecord(eventType = eventType, description = input.text.toString())
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun prepareRecord(eventType: String, description: String?, pointId: Long? = null, weight: Double? = null, condition: String? = null) {
        val lat = lastLocation?.latitude ?: 0.0
        val lng = lastLocation?.longitude ?: 0.0
        pendingEventType = eventType
        pendingDesc = description
        pendingPointId = pointId
        pendingWeight = weight
        pendingCondition = condition
        val record = GpsRecordLocal(
            executionLocalId = execLocalId,
            timestamp = System.currentTimeMillis(),
            lat = lat,
            lng = lng,
            eventType = eventType,
            description = description,
            pointId = pointId,
            collectedWeight = weight,
            pointCondition = condition
        )
        AlertDialog.Builder(this)
            .setTitle("Foto")
            .setMessage("Deseja tirar uma foto?")
            .setPositiveButton("Sim") { _, _ ->
                pendingRecord = record
                launchCamera()
            }
            .setNegativeButton("Não") { _, _ ->
                saveRecordToDb(record)
                clearPendingData()
            }
            .show()
    }

    private fun launchCamera() {
        try {
            val photoFile = File.createTempFile("img_${System.currentTimeMillis()}", ".jpg", externalCacheDir)
            currentPhotoPath = photoFile.absolutePath
            val uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.provider",
                photoFile
            )
            takePictureLauncher.launch(uri)
        } catch (e: Exception) {
            Log.e("CAMERA_ERROR", "Erro ao abrir câmera: ${e.message}", e)
            Toast.makeText(this, "Erro ao abrir câmera. Veja o Logcat.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveRecordToDb(record: GpsRecordLocal) {
        lifecycleScope.launch(Dispatchers.IO) {
            gpsDao.insert(record)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@StartRoute, "${record.eventType} salvo!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun finishRoute(finalKm: Int) {
        stopLocationUpdates()
        val lat = lastLocation?.latitude ?: 0.0
        val lng = lastLocation?.longitude ?: 0.0
        lifecycleScope.launch(Dispatchers.IO) {
            val localExec = executionDao.getById(execLocalId)
            var serverId = localExec?.serverExecutionId
            if (serverId == null && localExec != null) {
                serverId = recoverServerId(localExec)
            }

            if (serverId != null && localExec != null) {
                try {
                    val allPoints = gpsDao.listByExecution(execLocalId)
                    val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                    val (withPhoto, withoutPhoto) = allPoints.partition { !it.photoPath.isNullOrEmpty() }

                    if (withoutPhoto.isNotEmpty()) {
                        val batch = withoutPhoto.map {
                            GpsRecordRequest(
                                latitude = it.lat,
                                longitude = it.lng,
                                gps_timestamp = isoFormat.format(Date(it.timestamp)),
                                event_type = it.eventType,
                                is_automatic = it.eventType == "NORMAL",
                                is_offline = true,
                                description = it.description,
                                point_id = it.pointId,
                                collected_weight_kg = it.collectedWeight,
                                point_condition = it.pointCondition
                            )
                        }
                        RetrofitClient.getApiService(applicationContext).sendGpsBatch(serverId, batch)
                    }

                    withPhoto.forEach { record ->
                        uploadSingleRecordWithPhoto(serverId, record, isoFormat)
                    }

                    val assignId = localExec.assignmentId
                    RetrofitClient.getApiService(applicationContext).completeExecution(
                        serverId,
                        CompleteExecutionRequest(
                            final_km = finalKm,
                            latitude = lat,
                            longitude = lng,
                            final_notes = "App finished",
                            assignment_id = assignId
                        )
                    )
                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "Sincronizado com sucesso!", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        Log.e("FinishRoute", "Erro: " + e.message)
                        Toast.makeText(applicationContext, "Erro no envio. Verifique Logcat.", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "Erro: Sem ID do servidor. Dados salvos apenas localmente.", Toast.LENGTH_LONG).show()
                }
            }
            if (localExec != null) {
                executionDao.update(
                    localExec.copy(endTimestamp = System.currentTimeMillis(), status = "COMPLETED", endLat = lat, endLng = lng)
                )
            }
            withContext(Dispatchers.Main) { finish() }
        }
    }

    private suspend fun recoverServerId(localExec: ExecutionLocal): Long? {
        val api = RetrofitClient.getApiService(applicationContext)
        try {
            val current = api.getMyCurrentExecution()
            if (current.isSuccessful) {
                val id = current.body()?.data?.execution?.id
                if (id != null) return id
            }
        } catch (e: Exception) { e.printStackTrace() }
        return null
    }

    private suspend fun uploadSingleRecordWithPhoto(executionId: Long, record: GpsRecordLocal, sdf: SimpleDateFormat) {
        try {
            val file = File(record.photoPath!!)
            if (!file.exists()) {
                Log.e("UploadPhoto", "Arquivo não encontrado: ${record.photoPath}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "Erro: Foto não encontrada no dispositivo", Toast.LENGTH_SHORT).show()
                }
                return
            }

            // CORREÇÃO: Usando image/jpeg especificamente
            val mediaTypeImg = "image/jpeg".toMediaTypeOrNull()
            val reqFile = file.asRequestBody(mediaTypeImg)
            val photoPart = MultipartBody.Part.createFormData("photo", file.name, reqFile)
            val txt = "text/plain".toMediaTypeOrNull()

            val descPart = record.description?.toRequestBody(txt)
            val pIdPart = record.pointId?.toString()?.toRequestBody(txt)
            val wPart = record.collectedWeight?.toString()?.toRequestBody(txt)
            val condPart = record.pointCondition?.toRequestBody(txt)

            val res = RetrofitClient.getApiService(applicationContext).sendGpsWithPhoto(
                executionId = executionId,
                latitude = record.lat.toString().toRequestBody(txt),
                longitude = record.lng.toString().toRequestBody(txt),
                timestamp = sdf.format(Date(record.timestamp)).toRequestBody(txt),
                eventType = record.eventType.toRequestBody(txt),
                isAutomatic = "false".toRequestBody(txt),
                isOffline = "true".toRequestBody(txt),
                photo = photoPart,
                description = descPart,
                pointId = pIdPart,
                weight = wPart,
                condition = condPart
            )

            if (!res.isSuccessful) {
                val errorMsg = res.errorBody()?.string()
                Log.e("UploadPhoto", "Erro no upload da foto: Code ${res.code()} - $errorMsg")
                // Feedback visual para o usuário
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "Falha ao enviar foto: Erro ${res.code()}", Toast.LENGTH_SHORT).show()
                }
            } else {
                Log.d("UploadPhoto", "Foto enviada com sucesso!")
            }
        } catch (e: Exception) {
            Log.e("UploadPhoto", "Exceção no upload: ${e.message}", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(applicationContext, "Erro de conexão ao enviar foto", Toast.LENGTH_SHORT).show()
            }
            e.printStackTrace()
        }
    }

    private fun fetchAssignment() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = RetrofitClient.getApiService(applicationContext).getMyAssignment()
                if (response.isSuccessful) {
                    val assignment = response.body()?.data?.assignment
                    currentAssignmentId = assignment?.id
                    withContext(Dispatchers.Main) {
                        if (assignment != null) {
                            tvHeader.text = assignment.route.name
                            tvSub.text = "Veículo: ${assignment.vehicle.license_plate}"
                        }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun checkPermissionsAndShowDialog() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            showKmDialog(isStart = true)
        } else {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    private fun showKmDialog(isStart: Boolean) {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.hint = "KM do Painel"
        AlertDialog.Builder(this)
            .setTitle(if (isStart) "Iniciar" else "Finalizar")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val km = input.text.toString().toIntOrNull()
                if (km != null) {
                    if (isStart) startRouteWithLocation(km) else finishRoute(km)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(res: LocationResult) {
                res.lastLocation?.let {
                    lastLocation = it
                    tvStatus.text = "GPS: %.4f, %.4f".format(it.latitude, it.longitude)
                    if (routeStarted && execLocalId != 0L) saveGpsPoint(it)
                }
            }
        }
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000).build()
        try {
            fusedLocationClient.requestLocationUpdates(req, locationCallback, android.os.Looper.getMainLooper())
        } catch (e: Exception) {}
    }

    private fun stopLocationUpdates() {
        try { fusedLocationClient.removeLocationUpdates(locationCallback) } catch (e: Exception) {}
    }

    private fun saveGpsPoint(loc: Location) {
        lifecycleScope.launch(Dispatchers.IO) {
            gpsDao.insert(GpsRecordLocal(executionLocalId = execLocalId, timestamp = loc.time, lat = loc.latitude, lng = loc.longitude, eventType = "NORMAL"))
        }
    }

    private fun applyUiState() {
        if (routeStarted) {
            btStart.visibility = View.GONE
            btFinish.visibility = View.VISIBLE
            btActions.isEnabled = true
        } else {
            btStart.visibility = View.VISIBLE
            btFinish.visibility = View.GONE
            btActions.isEnabled = false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("route_started", routeStarted)
        outState.putLong("exec_local_id", execLocalId)
        if (currentAssignmentId != null) outState.putLong("assignment_id", currentAssignmentId!!)
        outState.putString("photo_path", currentPhotoPath)
        outState.putString("pending_type", pendingEventType)
        outState.putString("pending_desc", pendingDesc)
        if (pendingPointId != null) outState.putLong("pending_point_id", pendingPointId!!)
        if (pendingWeight != null) outState.putDouble("pending_weight", pendingWeight!!)
        outState.putString("pending_cond", pendingCondition)
        super.onSaveInstanceState(outState)
    }
}