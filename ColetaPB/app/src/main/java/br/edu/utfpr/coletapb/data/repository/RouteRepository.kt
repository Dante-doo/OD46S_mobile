package br.edu.utfpr.coletapb.data.repository

import br.edu.utfpr.coletapb.data.RouteEntity
import br.edu.utfpr.coletapb.data.remote.ApiService
import retrofit2.Response

/**
 * Esta classe é responsável por buscar os dados das rotas,
 * servindo como intermediário entre o ViewModel e o ApiService.
 */
class RouteRepository(private val apiService: ApiService) {

    /**
     * Busca as rotas filtradas do backend.
     */
    suspend fun getRoutes(driverId: Long, truckId: Long): Response<List<RouteEntity>> {
        return apiService.getRoutes(driverId = driverId, truckId = truckId)
    }
}