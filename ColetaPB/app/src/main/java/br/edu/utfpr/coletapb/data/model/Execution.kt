package br.edu.utfpr.coletapb.data.model

import com.google.gson.annotations.SerializedName

data class Execution(
    val id: Long,
    @SerializedName("assignment_id")
    val assignmentId: Long,
    @SerializedName("route_id")
    val routeId: Long,
    @SerializedName("route_name")
    val routeName: String?,
    @SerializedName("driver_id")
    val driverId: Long,
    @SerializedName("vehicle_id")
    val vehicleId: Long,
    val status: String, // IN_PROGRESS, COMPLETED, CANCELLED
    @SerializedName("start_time")
    val startTime: String?,
    @SerializedName("end_time")
    val endTime: String?,
    @SerializedName("start_lat")
    val startLat: Double?,
    @SerializedName("start_lng")
    val startLng: Double?,
    @SerializedName("end_lat")
    val endLat: Double?,
    @SerializedName("end_lng")
    val endLng: Double?
)

data class ExecutionRequest(
    @SerializedName("assignment_id")
    val assignmentId: Long,
    @SerializedName("initial_km")
    val initialKm: Int? = null,
    @SerializedName("start_lat")
    val startLat: Double? = null,
    @SerializedName("start_lng")
    val startLng: Double? = null,
    @SerializedName("initial_notes")
    val initialNotes: String? = null
)

data class ExecutionCompleteRequest(
    @SerializedName("final_km")
    val finalKm: Int? = null,
    @SerializedName("end_lat")
    val endLat: Double? = null,
    @SerializedName("end_lng")
    val endLng: Double? = null,
    @SerializedName("final_notes")
    val notes: String? = null
)

