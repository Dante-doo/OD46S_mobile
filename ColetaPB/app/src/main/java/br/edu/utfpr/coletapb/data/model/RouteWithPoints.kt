package br.edu.utfpr.coletapb.data.model

/**
 * Modelo de dados para rota com seus pontos de coleta
 */
data class RouteWithPoints(
    val id: Long,
    val name: String,
    val description: String?,
    val collectionType: String,
    val periodicity: String,
    val priority: String?,
    val estimatedTimeMinutes: Int?,
    val distanceKm: Double?,
    val active: Boolean,
    val notes: String?,
    val collectionPoints: List<CollectionPoint>
)

