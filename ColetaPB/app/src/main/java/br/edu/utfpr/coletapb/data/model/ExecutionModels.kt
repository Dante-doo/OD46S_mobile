package br.edu.utfpr.coletapb.data.model

import com.google.gson.annotations.SerializedName

data class StartExecutionRequest(
    @SerializedName("assignmentId") val assignment_id: Long,
    @SerializedName("initialKm") val initial_km: Int,
    val latitude: Double,
    val longitude: Double,
    @SerializedName("initialNotes") val initial_notes: String? = null
)

data class CompleteExecutionRequest(
    @SerializedName("finalKm") val final_km: Int,
    val latitude: Double,
    val longitude: Double,
    @SerializedName("finalNotes") val final_notes: String? = null
)

data class StartExecutionResponse(
    val success: Boolean?,
    val data: ExecutionData?
)

data class ExecutionData(
    val execution: ExecutionDto
)

data class ExecutionDto(
    val id: Long,
    val status: String
)

data class BatchResponse(
    val success: Boolean,
    val data: Any?
)

// ATUALIZADO COM OS CAMPOS DO BRUNO
data class GpsRecordRequest(
    val latitude: Double,
    val longitude: Double,
    @SerializedName("gpsTimestamp") val gps_timestamp: String,
    @SerializedName("eventType") val event_type: String = "NORMAL",
    @SerializedName("isAutomatic") val is_automatic: Boolean = true,
    @SerializedName("isOffline") val is_offline: Boolean = true,

    // --- NOVOS CAMPOS OPCIONAIS (Snake Case para o Batch) ---
    val description: String? = null,

    @SerializedName("point_id")
    val point_id: Long? = null,

    @SerializedName("collected_weight_kg")
    val collected_weight_kg: Double? = null,

    @SerializedName("point_condition")
    val point_condition: String? = null
)