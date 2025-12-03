package br.edu.utfpr.coletapb.data.model

data class Execution(
    val id: Long,
    val assignmentId: Long,
    val routeId: Long,
    val routeName: String?,
    val driverId: Long,
    val vehicleId: Long,
    val status: String, // IN_PROGRESS, COMPLETED, CANCELLED
    val startTime: String?,
    val endTime: String?,
    val startLat: Double?,
    val startLng: Double?,
    val endLat: Double?,
    val endLng: Double?
)

data class ExecutionRequest(
    val assignmentId: Long,
    val startLat: Double? = null,
    val startLng: Double? = null
)

data class ExecutionCompleteRequest(
    val endLat: Double? = null,
    val endLng: Double? = null,
    val notes: String? = null
)

