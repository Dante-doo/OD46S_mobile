package br.edu.utfpr.coletapb.data.remote

import br.edu.utfpr.coletapb.data.RouteEntity // << IMPORTAR
import br.edu.utfpr.coletapb.data.model.LoginRequest
import br.edu.utfpr.coletapb.data.model.LoginResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET    // << IMPORTAR
import retrofit2.http.POST
import retrofit2.http.Query // << IMPORTAR

interface ApiService {

    @POST("login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    // --- NOVO ENDPOINT PARA BUSCAR ROTAS ---
    /**
     * Busca a lista de rotas filtrada.
     * A URL final será algo como: /api/routes?driverId=123&truckId=456
     */
    @GET("routes")
    suspend fun getRoutes(
        @Query("driverId") driverId: Long,
        @Query("truckId") truckId: Long
    ): Response<List<RouteEntity>>
}