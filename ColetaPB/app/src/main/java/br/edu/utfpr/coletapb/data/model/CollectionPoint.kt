package br.edu.utfpr.coletapb.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Modelo de dados para um ponto de coleta da rota
 */
@Parcelize
data class CollectionPoint(
    val id: Long,
    val routeId: Long,
    val sequenceOrder: Int,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val wasteType: String, // COMMERCIAL, RESIDENTIAL, ORGANIC, etc
    val estimatedCapacityKg: Double?,
    val collectionFrequency: String?, // DAILY, WEEKLY, ALTERNATE
    val notes: String?,
    val active: Boolean = true,
    var status: PointStatus = PointStatus.PENDING // Status durante a execução
) : Parcelable {
    // Para serialização do enum no Parcelable
    val statusString: String
        get() = status.name
}

enum class PointStatus {
    PENDING,    // Ainda não visitado
    COLLECTED,  // Coleta realizada
    PROBLEM,    // Problema registrado
    SKIPPED     // Pulado
}

// Extensão para serializar PointStatus como String (necessário para Parcelable)
fun PointStatus.toSerializedString(): String = this.name
fun String.toPointStatus(): PointStatus = try {
    PointStatus.valueOf(this)
} catch (e: IllegalArgumentException) {
    PointStatus.PENDING
}

