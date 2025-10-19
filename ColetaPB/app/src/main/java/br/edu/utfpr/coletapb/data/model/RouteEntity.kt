package br.edu.utfpr.coletapb.data

import androidx.room.Entity
import androidx.room.PrimaryKey

// Entidade que representa a tabela de rotas
@Entity(tableName = "routes")
data class RouteEntity(
    @PrimaryKey val id: Int,
    val name: String,
    val description: String?,
    val collection_type: String,
    val periodicity: String,
    val priority: String,
    val estimated_time_minutes: Int,
    val distance_km: Double
)