package br.edu.utfpr.coletapb.data.remote

import br.edu.utfpr.coletapb.data.model.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @GET("vehicles")
    suspend fun getVehicles(): Response<List<VehicleDto>>

    @GET("routes")
    suspend fun getRoutes(
        @Query("driver_id") driverId: Long? = null,
        @Query("vehicle_id") vehicleId: Long? = null
    ): Response<RouteListResponse>

    @GET("assignments/my-current")
    suspend fun getMyAssignment(): Response<AssignmentResponse>

    @GET("executions/my-current")
    suspend fun getMyCurrentExecution(): Response<StartExecutionResponse>

    @POST("executions/start")
    suspend fun startExecution(@Body request: StartExecutionRequest): Response<StartExecutionResponse>

    @POST("executions/{id}/gps/batch")
    suspend fun sendGpsBatch(@Path("id") id: Long, @Body gpsRecords: List<GpsRecordRequest>): Response<BatchResponse>

    @PATCH("executions/{id}/complete")
    suspend fun completeExecution(@Path("id") id: Long, @Body request: CompleteExecutionRequest): Response<Void>

    // CORREÇÃO CRÍTICA: Os nomes aqui DEVEM ser snake_case para o servidor aceitar
    @Multipart
    @POST("executions/{id}/gps")
    suspend fun sendGpsWithPhoto(
        @Path("id") executionId: Long,

        @Part("latitude") latitude: RequestBody,
        @Part("longitude") longitude: RequestBody,

        @Part("gps_timestamp") timestamp: RequestBody, // snake_case
        @Part("event_type") eventType: RequestBody,    // snake_case
        @Part("is_automatic") isAutomatic: RequestBody,// snake_case
        @Part("is_offline") isOffline: RequestBody,    // snake_case

        @Part photo: MultipartBody.Part,

        @Part("description") description: RequestBody? = null,
        @Part("point_id") pointId: RequestBody? = null,
        @Part("collected_weight_kg") weight: RequestBody? = null,
        @Part("point_condition") condition: RequestBody? = null
    ): Response<Any>
}