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

    @Multipart
    @POST("executions/{id}/gps")
    suspend fun sendGpsWithPhoto(
        @Path("id") executionId: Long,
        // Campos Obrigatórios
        @Part("latitude") latitude: RequestBody,
        @Part("longitude") longitude: RequestBody,
        @Part("gpsTimestamp") timestamp: RequestBody,
        @Part("eventType") eventType: RequestBody,
        @Part("isAutomatic") isAutomatic: RequestBody,
        @Part("isOffline") isOffline: RequestBody,

        // Foto (Obrigatória neste método, opcional na lógica de negócio)
        @Part photo: MultipartBody.Part,

        // Campos Opcionais (Podem ser null - Snake Case nas chaves @Part)
        @Part("description") description: RequestBody? = null,
        @Part("point_id") pointId: RequestBody? = null,
        @Part("collected_weight_kg") weight: RequestBody? = null,
        @Part("point_condition") condition: RequestBody? = null
    ): Response<Any>
}