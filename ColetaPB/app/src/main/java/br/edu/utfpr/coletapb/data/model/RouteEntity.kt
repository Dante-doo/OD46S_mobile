package br.edu.utfpr.coletapb.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "routes")
data class RouteEntity(
    @PrimaryKey val id: Long,      // ID vindo do backend
    val name: String,
    val description: String?,
    val collection_type: String,   // Ex: RESIDENTIAL, COMMERCIAL
    val periodicity: String?,      // Expressão Cron (ex: "0 8 * * 1")
    val priority: String,          // Ex: HIGH, MEDIUM
    val estimated_time_minutes: Int,
    val distance_km: Double
)