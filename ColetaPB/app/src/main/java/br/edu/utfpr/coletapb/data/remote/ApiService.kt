package br.edu.utfpr.coletapb.data.remote

import br.edu.utfpr.coletapb.data.RouteEntity
import br.edu.utfpr.coletapb.data.model.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    // ========== AUTHENTICATION ==========
    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>
    
    @POST("auth/refresh")
    suspend fun refreshToken(@Body request: Map<String, String>): Response<LoginResponse>

    // ========== ASSIGNMENTS ==========
    /**
     * Busca as escalas/atribuições do motorista autenticado
     */
    @GET("assignments")
    suspend fun getAssignments(
        @Query("driver_id") driverId: Long? = null,
        @Query("status") status: String? = null,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20
    ): Response<Map<String, Any>>

    @GET("assignments/my-current")
    suspend fun getMyCurrentAssignment(): Response<Map<String, Any>>

    // ========== ROUTES ==========
    /**
     * Busca a lista de rotas filtrada.
     */
    @GET("routes")
    suspend fun getRoutes(
        @Query("driverId") driverId: Long? = null,
        @Query("truckId") truckId: Long? = null
    ): Response<List<RouteEntity>>

    /**
     * Busca uma rota específica com seus pontos de coleta
     */
    @GET("routes/{id}")
    suspend fun getRouteWithPoints(@Path("id") routeId: Long): Response<Map<String, Any>>

    // ========== EXECUTIONS ==========
    /**
     * Inicia uma nova execução de coleta
     */
    @POST("executions/start")
    suspend fun startExecution(@Body request: ExecutionRequest): Response<Map<String, Any>>

    /**
     * Finaliza uma execução
     */
    @HTTP(method = "PATCH", path = "executions/{id}/complete", hasBody = true)
    suspend fun completeExecution(
        @Path("id") executionId: Long,
        @Body request: ExecutionCompleteRequest
    ): Response<Map<String, Any>>

    /**
     * Cancela uma execução
     */
    @HTTP(method = "PATCH", path = "executions/{id}/cancel", hasBody = true)
    suspend fun cancelExecution(
        @Path("id") executionId: Long,
        @Body request: Map<String, Any>
    ): Response<Map<String, Any>>

    /**
     * Busca a execução atual do motorista
     */
    @GET("executions/my-current")
    suspend fun getMyCurrentExecution(): Response<Map<String, Any>>

    /**
     * Lista execuções
     */
    @GET("executions")
    suspend fun getExecutions(
        @Query("assignment_id") assignmentId: Long? = null,
        @Query("driver_id") driverId: Long? = null,
        @Query("status") status: String? = null,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20
    ): Response<Map<String, Any>>

    // ========== GPS TRACKING ==========
    /**
     * Registra uma posição GPS / evento
     */
    @Multipart
    @POST("executions/{executionId}/gps")
    suspend fun registerGpsPosition(
        @Path("executionId") executionId: Long,
        @Part("latitude") latitude: RequestBody,
        @Part("longitude") longitude: RequestBody,
        @Part("speed_kmh") speedKmh: RequestBody? = null,
        @Part("heading_degrees") headingDegrees: RequestBody? = null,
        @Part("accuracy_meters") accuracyMeters: RequestBody? = null,
        @Part("event_type") eventType: RequestBody? = null,
        @Part("is_automatic") isAutomatic: RequestBody? = null,
        @Part("is_offline") isOffline: RequestBody? = null,
        @Part("gps_timestamp") gpsTimestamp: RequestBody? = null,
        @Part("description") description: RequestBody? = null,
        @Part("point_id") pointId: RequestBody? = null,
        @Part("collected_weight_kg") collectedWeightKg: RequestBody? = null,
        @Part("point_condition") pointCondition: RequestBody? = null,
        @Part photo: MultipartBody.Part? = null
    ): Response<Map<String, Any>>

    /**
     * Registra múltiplos pontos GPS em lote (para sincronização offline)
     */
    @POST("executions/{executionId}/gps/batch")
    suspend fun registerGpsBatch(
        @Path("executionId") executionId: Long,
        @Body records: List<Map<String, Any>>
    ): Response<Map<String, Any>>

    /**
     * Busca o rastro GPS de uma execução
     */
    @GET("executions/{executionId}/gps")
    suspend fun getGpsTrace(
        @Path("executionId") executionId: Long,
        @Query("start_time") startTime: String? = null,
        @Query("end_time") endTime: String? = null
    ): Response<Map<String, Any>>
}