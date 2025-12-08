package br.edu.utfpr.coletapb.data.model

// Mantém apenas o que é exclusivo de Execução e GPS
data class StartExecutionRequest(
    val assignment_id: Long,
    val initial_km: Int,
    val latitude: Double,
    val longitude: Double,
    val initial_notes: String? = null
)

data class StartExecutionResponse(
    val success: Boolean,
    val data: ExecutionData
)

data class ExecutionData(
    val execution: Execution
)

data class Execution(
    val id: Long,
    val status: String
)

data class GpsRecordRequest(
    val latitude: Double,
    val longitude: Double,
    val gps_timestamp: String,
    val event_type: String = "NORMAL",
    val is_automatic: Boolean = true,
    val is_offline: Boolean = true
)

data class BatchResponse(
    val success: Boolean,
    val data: Any
)

data class CompleteExecutionRequest(
    val final_km: Int,
    val latitude: Double,
    val longitude: Double,
    val final_notes: String? = null
)