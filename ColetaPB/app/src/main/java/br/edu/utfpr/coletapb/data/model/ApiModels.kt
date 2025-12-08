package br.edu.utfpr.coletapb.data.model

import com.google.gson.annotations.SerializedName

// --- Veículos ---
data class VehicleDto(
    val id: Long,
    @SerializedName("licensePlate") val license_plate: String, // JSON: licensePlate -> Kotlin: license_plate
    val model: String,
    val brand: String? = null, // Pode vir nulo
    val status: String? = null,
    val year: Int? = null
)

// --- Rotas ---
data class RouteDto(
    val id: Long,
    val name: String,
    val description: String?,

    @SerializedName("collectionType")
    val collection_type: String?, // JSON: collectionType -> Kotlin: collection_type

    val periodicity: String?,

    // Estes campos não vieram no JSON, então devem ser anuláveis
    val priority: String?,
    @SerializedName("estimatedTimeMinutes") val estimated_time_minutes: Int?,
    @SerializedName("distanceKm") val distance_km: Double?
)

// --- Escala (Assignment) ---
data class AssignmentResponse(
    val success: Boolean,
    val data: AssignmentData
)

data class AssignmentData(
    val assignment: AssignmentDto
)

data class AssignmentDto(
    val id: Long,
    val route: RouteDto,
    val vehicle: VehicleDto,
    val status: String,

    @SerializedName("startDate")
    val start_date: String,

    @SerializedName("endDate")
    val end_date: String?
)