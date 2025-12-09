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
    val periodicity: String?, // Formato cron: "0 8 * * 1" (minuto hora * * dia_da_semana)
    val isCurrent: Boolean = false // Indica se é a rota atual (em execução)
)

