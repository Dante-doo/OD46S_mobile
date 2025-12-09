package br.edu.utfpr.coletapb.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

// Entidade que representa a tabela de pontos de coleta
@Entity(tableName = "collection_points")
data class CollectionPointEntity(
    @PrimaryKey val id: Int,
    val routeId: Int, // Chave estrangeira para associar ao RouteEntity
    val sequence_order: Int,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val waste_type: String,
    val estimated_capacity_kg: Double,
    val notes: String?
)