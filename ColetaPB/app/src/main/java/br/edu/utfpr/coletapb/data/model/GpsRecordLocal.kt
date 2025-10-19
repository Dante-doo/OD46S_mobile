package br.edu.utfpr.coletapb.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "gps_records_local")
data class GpsRecordLocal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val executionLocalId: Long,   // FK local → ExecutionLocal.localId
    val timestamp: Long,
    val lat: Double,
    val lng: Double,
    val eventType: String         // START | STOP | END | NORMAL
)
