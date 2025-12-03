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
            }
            
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
            
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val data = body["data"] as? Map<String, Any>
                
                if (data != null) {
                    val record = try {
                        GpsRecord(
                            id = (data["id"] as? Number)?.toLong() ?: 0L,
                            executionId = executionId,
                            latitude = (data["latitude"] as? Number)?.toDouble() ?: latitude,
                            longitude = (data["longitude"] as? Number)?.toDouble() ?: longitude,
                            speedKmh = (data["speedKmh"] as? Number)?.toDouble(),
                            headingDegrees = (data["headingDegrees"] as? Number)?.toDouble(),
                            accuracyMeters = (data["accuracyMeters"] as? Number)?.toDouble(),
                            eventType = data["eventType"] as? String ?: eventType,
                            isAutomatic = data["isAutomatic"] as? Boolean ?: isAutomatic,
                            isOffline = data["isOffline"] as? Boolean ?: isOffline,
                            gpsTimestamp = data["gpsTimestamp"] as? String ?: "",
                            description = data["description"] as? String,
                            pointId = (data["pointId"] as? Number)?.toLong(),
                            collectedWeightKg = (data["collectedWeightKg"] as? Number)?.toDouble(),
                            pointCondition = data["pointCondition"] as? String
                        )
                    } catch (e: Exception) {
                        Log.e("GpsRepository", "Erro ao mapear GPS record: ${e.message}")
                        null
                    }
                    
                    if (record != null) {
                        Result.success(record)
                    } else {
                        Result.failure(Exception("Erro ao processar resposta do GPS"))
                    }
                } else {
                    Result.failure(Exception("Resposta vazia do servidor"))
                }
            } else {
                val errorMsg = response.errorBody()?.string() ?: "Erro desconhecido"
                Log.e("GpsRepository", "Erro ao registrar GPS: ${response.code()} - $errorMsg")
                Result.failure(Exception("Erro ao registrar GPS: ${response.code()}"))
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

