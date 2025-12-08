package br.edu.utfpr.coletapb.data.model

// --- Veículos (Vehicles) ---
data class VehicleListResponse(
    val success: Boolean,
    val data: VehicleData
)

data class VehicleData(
    val vehicles: List<VehicleDto>
)

data class VehicleDto(
    val id: Long,
    val license_plate: String,
    val model: String,
    val brand: String,
    val status: String
)

// --- Rotas (Routes) ---
data class RouteListResponse(
    val success: Boolean,
    val data: RouteData
)

data class RouteData(
    val routes: List<RouteDto>
)

data class RouteDto(
    val id: Long,
    val name: String,
    val description: String?,
    val collection_type: String,
    val periodicity: String?,
    val priority: String,
    val estimated_time_minutes: Int?,
    val distance_km: Double?
)

// --- Escala (Assignment) - Caso precise ---
data class AssignmentResponse(
    val success: Boolean,
    val data: AssignmentWrapper
)

data class AssignmentWrapper(
    val assignment: AssignmentDto
)

data class AssignmentDto(
    val id: Long,
    val route: RouteDto,
    val vehicle: VehicleDto
)