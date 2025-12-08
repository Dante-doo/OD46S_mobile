package br.edu.utfpr.coletapb.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "gps_records_local")
data class GpsRecordLocal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val executionLocalId: Long,
    val timestamp: Long,
    val lat: Double,
    val lng: Double,

    // Tipos: NORMAL, START, STOP, LUNCH, FUEL, PROBLEM,
    // POINT_COLLECTED, POINT_SKIPPED, POINT_PROBLEM
    val eventType: String,

    val photoPath: String? = null,

    // --- NOVOS CAMPOS (Baseados nos seus arquivos .bru) ---
    val description: String? = null,      // Para LUNCH, FUEL, PROBLEM
    val pointId: Long? = null,            // Para eventos de PONTO
    val collectedWeight: Double? = null,  // Para POINT_COLLECTED / POINT_PROBLEM
    val pointCondition: String? = null    // NORMAL, SATURATED, DAMAGED, INACCESSIBLE
)