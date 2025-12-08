package br.edu.utfpr.coletapb.data.model

data class StartExecutionRequest(
    val assignment_id: Long,
    val initial_km: Int,
    val latitude: Double,
    val longitude: Double,
    val initial_notes: String? = null
)

data class StartExecutionResponse(
    val success: Boolean,
    val data: ExecutionData // <--- TEM QUE TER O "val" AQUI
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
    val gps_timestamp: String, // Formato ISO-8601
    val event_type: String = "NORMAL",
    val is_automatic: Boolean = true,
    val is_offline: Boolean = true
)

data class BatchResponse(
    val success: Boolean,
    val data: Any // Pode refinar se precisar ler o retorno detalhado
)

data class CompleteExecutionRequest(
    val final_km: Int,
    val latitude: Double,
    val longitude: Double,
    val final_notes: String? = null
)