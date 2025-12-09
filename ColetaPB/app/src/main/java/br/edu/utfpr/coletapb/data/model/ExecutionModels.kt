package br.edu.utfpr.coletapb.data.model

import com.google.gson.annotations.SerializedName

data class StartExecutionRequest(
    @SerializedName("assignment_id") val assignment_id: Long,
    @SerializedName("initial_km") val initial_km: Int,
    val latitude: Double,
    val longitude: Double,
    @SerializedName("initial_notes") val initial_notes: String? = null
)

data class CompleteExecutionRequest(
    @SerializedName("final_km") val final_km: Int,
    val latitude: Double,
    val longitude: Double,
    @SerializedName("final_notes") val final_notes: String? = null,
    @SerializedName("assignment_id") val assignment_id: Long
)

data class StartExecutionResponse(
    val success: Boolean?,
    val data: ExecutionData?
)

data class ExecutionData(
    val execution: ExecutionDto
)

// ATUALIZADO: Campos necessários para restaurar o estado
data class ExecutionDto(
    val id: Long,
    val status: String,
    @SerializedName("assignment_id") val assignment_id: Long?,
    @SerializedName("initial_km") val initial_km: Int?,
    @SerializedName("start_time") val start_time: String?
)

data class BatchResponse(
    val success: Boolean,
    val data: Any?
)

data class GpsRecordRequest(
    val latitude: Double,
    val longitude: Double,
    @SerializedName("gps_timestamp") val gps_timestamp: String,
    @SerializedName("event_type") val event_type: String = "NORMAL",
    @SerializedName("is_automatic") val is_automatic: Boolean = true,
    @SerializedName("is_offline") val is_offline: Boolean = true,
    val description: String? = null,
    @SerializedName("point_id") val point_id: Long? = null,
    @SerializedName("collected_weight_kg") val collected_weight_kg: Double? = null,
    @SerializedName("point_condition") val point_condition: String? = null
)