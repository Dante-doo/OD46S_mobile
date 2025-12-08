package br.edu.utfpr.coletapb.data.repository

import br.edu.utfpr.coletapb.data.model.RouteListResponse
import br.edu.utfpr.coletapb.data.remote.ApiService
import retrofit2.Response

class RouteRepository(private val apiService: ApiService) {

    // CORREÇÃO: O tipo de retorno deve ser Response<RouteListResponse>
    // CORREÇÃO: O parâmetro nomeado na chamada é vehicleId
    suspend fun getRoutes(driverId: Long, truckId: Long): Response<RouteListResponse> {
        return apiService.getRoutes(driverId = driverId, vehicleId = truckId)
    }
}