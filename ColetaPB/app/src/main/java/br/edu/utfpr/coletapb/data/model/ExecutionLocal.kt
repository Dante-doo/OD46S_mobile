package br.edu.utfpr.coletapb.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "executions_local")
data class ExecutionLocal(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0L,
    val serverExecutionId: Long? = null,

    // Campos para garantir sincronia posterior
    val assignmentId: Long, // Guardamos o ID da escala
    val initialKm: Int,     // Guardamos o KM inicial

    val routeId: Long,
    val status: String,
    val startTimestamp: Long,
    val startLat: Double,
    val startLng: Double,

    val endTimestamp: Long? = null,
    val endLat: Double? = null,
    val endLng: Double? = null
)