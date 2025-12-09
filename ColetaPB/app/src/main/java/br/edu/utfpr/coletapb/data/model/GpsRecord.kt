package br.edu.utfpr.coletapb.data.model

data class GpsRecord(
    val id: Long,
    val executionId: Long,
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Double?,
    val headingDegrees: Double?,
    val accuracyMeters: Double?,
    val eventType: String, // NORMAL, STOP, INCIDENT, COLLECTION
    val isAutomatic: Boolean,
    val isOffline: Boolean,
    val gpsTimestamp: String,
    val description: String?,
    val pointId: Long?,
    val collectedWeightKg: Double?,
    val pointCondition: String?
)

data class GpsRecordRequest(
    val latitude: String,
    val longitude: String,
    val speed_kmh: String? = null,
    val heading_degrees: String? = null,
    val accuracy_meters: String? = null,
    val event_type: String = "NORMAL",
    val is_automatic: Boolean? = null,
    val is_offline: Boolean? = null,
    val gps_timestamp: String? = null,
    val description: String? = null,
    val point_id: Long? = null,
    val collected_weight_kg: String? = null,
    val point_condition: String? = null
)

data class GpsBatchRequest(
    val records: List<GpsRecordRequest>
)

