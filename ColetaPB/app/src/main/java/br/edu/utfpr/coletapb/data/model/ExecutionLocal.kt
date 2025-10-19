package br.edu.utfpr.coletapb.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "executions_local")
data class ExecutionLocal(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0L,   // id local (Room)
    val backendId: Long? = null,                               // id no backend (quando existir)

    val routeId: Long,                                         // RouteEntity.id
    val vehicleId: Long? = null,
    val driverId: Long? = null,

    val startTimestamp: Long? = null,
    val startLat: Double? = null,
    val startLng: Double? = null,

    val endTimestamp: Long? = null,
    val endLat: Double? = null,
    val endLng: Double? = null,

    val status: String = "SCHEDULED" // SCHEDULED | IN_PROGRESS | COMPLETED
)
