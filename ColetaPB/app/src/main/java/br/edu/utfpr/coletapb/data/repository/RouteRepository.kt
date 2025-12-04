package br.edu.utfpr.coletapb.data.repository

import android.util.Log
import br.edu.utfpr.coletapb.data.local.SharedPreferencesHelper
import br.edu.utfpr.coletapb.data.model.CollectionPoint
import br.edu.utfpr.coletapb.data.model.PointStatus
import br.edu.utfpr.coletapb.data.model.RouteWithPoints
import br.edu.utfpr.coletapb.data.remote.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal

class RouteRepository(private val prefsHelper: SharedPreferencesHelper) {
    
    suspend fun getRoutes(driverId: Long, truckId: Long): retrofit2.Response<List<br.edu.utfpr.coletapb.data.RouteEntity>> = withContext(Dispatchers.IO) {
        RetrofitClient.apiService.getRoutes(driverId = driverId, truckId = truckId)
    }
    
    suspend fun getRouteWithPoints(routeId: Long): Result<RouteWithPoints> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.apiService.getRouteWithPoints(routeId)
            
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val data = body["data"] as? Map<String, Any>
                val routeData = data?.get("route") as? Map<String, Any>
                
                if (routeData != null) {
                    val collectionPointsList = (routeData["collection_points"] as? List<Map<String, Any>>) ?: emptyList()
                    
                    val points = collectionPointsList.mapNotNull { pointMap ->
                        try {
                            CollectionPoint(
                                id = ((pointMap["id"] as? Number)?.toLong() ?: 0L),
                                routeId = routeId,
                                sequenceOrder = ((pointMap["sequence_order"] as? Number)?.toInt() ?: 0),
                                address = (pointMap["address"] as? String) ?: "",
                                latitude = (pointMap["latitude"] as? Number)?.toDouble() 
                                    ?: (pointMap["latitude"] as? BigDecimal)?.toDouble() ?: 0.0,
                                longitude = (pointMap["longitude"] as? Number)?.toDouble()
                                    ?: (pointMap["longitude"] as? BigDecimal)?.toDouble() ?: 0.0,
                                wasteType = (pointMap["waste_type"] as? String) ?: "UNKNOWN",
                                estimatedCapacityKg = (pointMap["estimated_capacity_kg"] as? Number)?.toDouble()
                                    ?: (pointMap["estimated_capacity_kg"] as? BigDecimal)?.toDouble(),
                                collectionFrequency = pointMap["collection_frequency"] as? String,
                                notes = pointMap["notes"] as? String,
                                active = (pointMap["active"] as? Boolean) ?: true,
                                status = PointStatus.PENDING
                            )
                        } catch (e: Exception) {
                            Log.e("RouteRepository", "Erro ao mapear ponto: ${e.message}", e)
                            null
                        }
                    }
                    
                    val route = RouteWithPoints(
                        id = ((routeData["id"] as? Number)?.toLong() ?: 0L),
                        name = (routeData["name"] as? String) ?: "",
                        description = routeData["description"] as? String,
                        collectionType = (routeData["collection_type"] as? String) ?: "UNKNOWN",
                        periodicity = (routeData["periodicity"] as? String) ?: "",
                        priority = routeData["priority"] as? String,
                        estimatedTimeMinutes = (routeData["estimated_time_minutes"] as? Number)?.toInt(),
                        distanceKm = (routeData["distance_km"] as? Number)?.toDouble()
                            ?: (routeData["distance_km"] as? BigDecimal)?.toDouble(),
                        active = (routeData["active"] as? Boolean) ?: true,
                        notes = routeData["notes"] as? String,
                        collectionPoints = points.sortedBy { it.sequenceOrder }
                    )
                    
                    Result.success(route)
                } else {
                    Result.failure(Exception("Resposta vazia do servidor"))
                }
            } else {
                val errorMsg = response.errorBody()?.string() ?: "Erro desconhecido"
                Log.e("RouteRepository", "Erro ao buscar rota: ${response.code()} - $errorMsg")
                Result.failure(Exception("Erro ao buscar rota: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e("RouteRepository", "Exceção ao buscar rota: ${e.message}", e)
            Result.failure(e)
        }
    }
}
