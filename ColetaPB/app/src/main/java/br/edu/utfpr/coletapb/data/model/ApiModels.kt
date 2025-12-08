package br.edu.utfpr.coletapb.data.model

import com.google.gson.annotations.SerializedName

// --- Veículos ---
data class VehicleDto(
    val id: Long,
    @SerializedName("license_plate") val license_plate: String, // CORRIGIDO: snake_case
    val model: String,
    val brand: String? = null,
    val status: String? = null,
    val year: Int? = null
)

// --- Rotas ---
data class RouteDto(
    val id: Long,
    val name: String,
    val description: String?,
    @SerializedName("collection_type") val collection_type: String?, // CORRIGIDO: snake_case
    val periodicity: String?,
    val priority: String?,
    @SerializedName("estimated_time_minutes") val estimated_time_minutes: Int?, // CORRIGIDO
    @SerializedName("distance_km") val distance_km: Double? // CORRIGIDO
)

// --- Resposta de Lista de Rotas ---
data class RouteListResponse(
    val success: Boolean,
    val data: RouteListData
)

data class RouteListData(
    val routes: List<RouteDto>
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
    @SerializedName("start_date") val start_date: String, // CORRIGIDO: snake_case
    @SerializedName("end_date") val end_date: String?     // CORRIGIDO: snake_case
)