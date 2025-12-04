package br.edu.utfpr.coletapb.data.repository

import android.util.Log
import br.edu.utfpr.coletapb.data.local.SharedPreferencesHelper
import br.edu.utfpr.coletapb.data.model.GpsRecord
import br.edu.utfpr.coletapb.data.model.GpsRecordRequest
import br.edu.utfpr.coletapb.data.remote.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class GpsRepository(private val prefsHelper: SharedPreferencesHelper) {
    
    suspend fun registerGpsPosition(
        executionId: Long,
        latitude: Double,
        longitude: Double,
        speedKmh: Double? = null,
        headingDegrees: Double? = null,
        accuracyMeters: Double? = null,
        eventType: String = "NORMAL",
        isAutomatic: Boolean = true,
        isOffline: Boolean = false,
        description: String? = null,
        pointId: Long? = null,
        collectedWeightKg: Double? = null,
        pointCondition: String? = null,
        photoFile: File? = null
    ): Result<GpsRecord> = withContext(Dispatchers.IO) {
        try {
            Log.d("GpsRepository", "=== Iniciando registro GPS ===")
            Log.d("GpsRepository", "executionId: $executionId")
            Log.d("GpsRepository", "latitude: $latitude, longitude: $longitude")
            Log.d("GpsRepository", "eventType: $eventType")
            Log.d("GpsRepository", "pointId: $pointId")
            Log.d("GpsRepository", "description: $description")
            Log.d("GpsRepository", "isAutomatic: $isAutomatic, isOffline: $isOffline")
            
            val latitudeBody = latitude.toString().toRequestBody("text/plain".toMediaType())
            val longitudeBody = longitude.toString().toRequestBody("text/plain".toMediaType())
            
            val speedBody = speedKmh?.toString()?.toRequestBody("text/plain".toMediaType())
            val headingBody = headingDegrees?.toString()?.toRequestBody("text/plain".toMediaType())
            val accuracyBody = accuracyMeters?.toString()?.toRequestBody("text/plain".toMediaType())
            val eventTypeBody = eventType.toRequestBody("text/plain".toMediaType())
            val isAutomaticBody = isAutomatic.toString().toRequestBody("text/plain".toMediaType())
            val isOfflineBody = isOffline.toString().toRequestBody("text/plain".toMediaType())
            val descriptionBody = description?.toRequestBody("text/plain".toMediaType())
            val pointIdBody = pointId?.toString()?.toRequestBody("text/plain".toMediaType())
            val weightBody = collectedWeightKg?.toString()?.toRequestBody("text/plain".toMediaType())
            val conditionBody = pointCondition?.toRequestBody("text/plain".toMediaType())
            
            var photoPart: MultipartBody.Part? = null
            if (photoFile != null && photoFile.exists()) {
                val requestFile = photoFile.asRequestBody("image/jpeg".toMediaType())
                photoPart = MultipartBody.Part.createFormData("photo", photoFile.name, requestFile)
                Log.d("GpsRepository", "Foto anexada: ${photoFile.name}")
            }
            
            Log.d("GpsRepository", "Chamando API: POST /executions/$executionId/gps")
            val response = RetrofitClient.apiService.registerGpsPosition(
                executionId = executionId,
                latitude = latitudeBody,
                longitude = longitudeBody,
                speedKmh = speedBody,
                headingDegrees = headingBody,
                accuracyMeters = accuracyBody,
                eventType = eventTypeBody,
                isAutomatic = isAutomaticBody,
                isOffline = isOfflineBody,
                description = descriptionBody,
                pointId = pointIdBody,
                collectedWeightKg = weightBody,
                pointCondition = conditionBody,
                photo = photoPart
            )
            
            Log.d("GpsRepository", "Resposta recebida: code=${response.code()}, isSuccessful=${response.isSuccessful}")
            
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                Log.d("GpsRepository", "Body da resposta: $body")
                
                // Backend retorna { success: true, data: { gps_record: { ... } } }
                val data = body["data"] as? Map<String, Any>
                val gpsRecord = data?.get("gps_record") as? Map<String, Any> ?: data
                
                if (gpsRecord != null) {
                    Log.d("GpsRepository", "GPS record recebido: $gpsRecord")
                    val record = try {
                        GpsRecord(
                            id = (gpsRecord["id"] as? Number)?.toLong() ?: 0L,
                            executionId = executionId,
                            latitude = (gpsRecord["latitude"] as? Number)?.toDouble() ?: latitude,
                            longitude = (gpsRecord["longitude"] as? Number)?.toDouble() ?: longitude,
                            speedKmh = (gpsRecord["speed_kmh"] as? Number)?.toDouble(),
                            headingDegrees = (gpsRecord["heading_degrees"] as? Number)?.toDouble(),
                            accuracyMeters = (gpsRecord["accuracy_meters"] as? Number)?.toDouble(),
                            eventType = gpsRecord["event_type"] as? String ?: eventType,
                            isAutomatic = gpsRecord["is_automatic"] as? Boolean ?: isAutomatic,
                            isOffline = gpsRecord["is_offline"] as? Boolean ?: isOffline,
                            gpsTimestamp = gpsRecord["gps_timestamp"] as? String ?: "",
                            description = gpsRecord["description"] as? String,
                            pointId = (gpsRecord["point_id"] as? Number)?.toLong(),
                            collectedWeightKg = (gpsRecord["collected_weight_kg"] as? Number)?.toDouble(),
                            pointCondition = gpsRecord["point_condition"] as? String
                        )
                    } catch (e: Exception) {
                        Log.e("GpsRepository", "Erro ao mapear GPS record: ${e.message}", e)
                        null
                    }
                    
                    if (record != null) {
                        Log.d("GpsRepository", "GPS registrado com sucesso: id=${record.id}")
                        Result.success(record)
                    } else {
                        Log.e("GpsRepository", "Erro: record é null após mapeamento")
                        Result.failure(Exception("Erro ao processar resposta do GPS"))
                    }
                } else {
                    Log.e("GpsRepository", "Erro: data ou gps_record é null na resposta")
                    Result.failure(Exception("Resposta vazia do servidor"))
                }
            } else {
                val errorBody = response.errorBody()?.string()
                Log.e("GpsRepository", "Erro HTTP ${response.code()}: $errorBody")
                Result.failure(Exception("Erro ao registrar GPS: ${response.code()} - $errorBody"))
            }
        } catch (e: Exception) {
            Log.e("GpsRepository", "Exceção ao registrar GPS: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    suspend fun registerGpsBatch(
        executionId: Long,
        records: List<GpsRecordRequest>
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val recordsMap = records.map { record ->
                mutableMapOf<String, Any>(
                    "latitude" to record.latitude,
                    "longitude" to record.longitude,
                    "event_type" to record.event_type
                ).apply {
                    record.speed_kmh?.let { put("speed_kmh", it) }
                    record.heading_degrees?.let { put("heading_degrees", it) }
                    record.accuracy_meters?.let { put("accuracy_meters", it) }
                    record.is_automatic?.let { put("is_automatic", it) }
                    record.is_offline?.let { put("is_offline", it) }
                    record.gps_timestamp?.let { put("gps_timestamp", it) }
                    record.description?.let { put("description", it) }
                    record.point_id?.let { put("point_id", it) }
                    record.collected_weight_kg?.let { put("collected_weight_kg", it) }
                    record.point_condition?.let { put("point_condition", it) }
                }
            }
            
            val response = RetrofitClient.apiService.registerGpsBatch(executionId, recordsMap)
            
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val savedCount = (body["saved_count"] as? Number)?.toInt() ?: records.size
                Result.success(savedCount)
            } else {
                val errorMsg = response.errorBody()?.string() ?: "Erro desconhecido"
                Log.e("GpsRepository", "Erro ao registrar GPS em lote: ${response.code()} - $errorMsg")
                Result.failure(Exception("Erro ao registrar GPS em lote: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e("GpsRepository", "Exceção ao registrar GPS em lote: ${e.message}", e)
            Result.failure(e)
        }
    }
}

