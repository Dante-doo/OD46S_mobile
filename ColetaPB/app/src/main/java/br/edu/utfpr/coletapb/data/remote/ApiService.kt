package br.edu.utfpr.coletapb.data.remote

import br.edu.utfpr.coletapb.data.model.* // Vamos criar esses models abaixo
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    // Login (Já existente)
    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    // 1. Iniciar Execução (Seção 6.3 do contrato)
    @POST("executions/start")
    suspend fun startExecution(@Body request: StartExecutionRequest): Response<StartExecutionResponse>

    // 2. Enviar GPS em Lote (Seção 7.2 do contrato)
    @POST("executions/{id}/gps/batch")
    suspend fun sendGpsBatch(
        @Path("id") executionId: Long,
        @Body gpsRecords: List<GpsRecordRequest>
    ): Response<BatchResponse>

    // 3. Finalizar Execução (Seção 6.4 do contrato)
    @PATCH("executions/{id}/complete")
    suspend fun completeExecution(
        @Path("id") executionId: Long,
        @Body request: CompleteExecutionRequest
    ): Response<Void>
}