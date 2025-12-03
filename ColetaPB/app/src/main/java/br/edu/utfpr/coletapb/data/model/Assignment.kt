package br.edu.utfpr.coletapb.data.model

data class Assignment(
    val id: Long,
    val routeId: Long,
    val routeName: String?,
    val driverId: Long,
    val driverName: String?,
    val vehicleId: Long,
    val vehiclePlate: String?,
    val status: String, // ACTIVE, COMPLETED, CANCELLED
    val startDate: String?,
    val endDate: String?,
    val frequency: String?, // DAILY, WEEKLY, etc
    val isCurrent: Boolean = false // Indica se é a rota atual (em execução)
)

