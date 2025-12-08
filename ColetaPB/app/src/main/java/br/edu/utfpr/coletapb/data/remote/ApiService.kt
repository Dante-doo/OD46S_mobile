package br.edu.utfpr.coletapb.data.remote

import br.edu.utfpr.coletapb.data.model.*
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    // Login
    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    // Veículos
    @GET("vehicles")
    suspend fun getVehicles(): Response<VehicleListResponse>

    // Rotas
    @GET("routes")
    suspend fun getRoutes(): Response<RouteListResponse>

    // Escala do Motorista
    @GET("assignments/my-current")
    suspend fun getMyAssignment(): Response<AssignmentResponse>

    // Execuções
    @POST("executions/start")
    suspend fun startExecution(@Body request: StartExecutionRequest): Response<StartExecutionResponse>

    @POST("executions/{id}/gps/batch")
    suspend fun sendGpsBatch(@Path("id") id: Long, @Body gpsRecords: List<GpsRecordRequest>): Response<BatchResponse>

    @PATCH("executions/{id}/complete")
    suspend fun completeExecution(@Path("id") id: Long, @Body request: CompleteExecutionRequest): Response<Void>
}