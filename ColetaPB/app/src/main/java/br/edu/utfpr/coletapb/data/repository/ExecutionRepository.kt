package br.edu.utfpr.coletapb.data.repository

import android.util.Log
import br.edu.utfpr.coletapb.data.local.SharedPreferencesHelper
import br.edu.utfpr.coletapb.data.model.Execution
import br.edu.utfpr.coletapb.data.model.ExecutionCompleteRequest
import br.edu.utfpr.coletapb.data.model.ExecutionRequest
import br.edu.utfpr.coletapb.data.remote.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ExecutionRepository(private val prefsHelper: SharedPreferencesHelper) {
    
    suspend fun startExecution(assignmentId: Long, startLat: Double? = null, startLng: Double? = null): Result<Execution> = withContext(Dispatchers.IO) {
        try {
            val request = ExecutionRequest(
                assignmentId = assignmentId,
                startLat = startLat,
                startLng = startLng
            )
            
            val response = RetrofitClient.apiService.startExecution(request)
            
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val data = body["data"] as? Map<String, Any>
                
                if (data != null) {
                    val execution = try {
                        Execution(
                            id = (data["id"] as? Number)?.toLong() ?: 0L,
                            assignmentId = (data["assignmentId"] as? Number)?.toLong() ?: 0L,
                            routeId = ((data["route"] as? Map<*, *>)?.get("id") as? Number)?.toLong() ?: 0L,
                            routeName = (data["route"] as? Map<*, *>)?.get("name") as? String,
                            driverId = ((data["driver"] as? Map<*, *>)?.get("id") as? Number)?.toLong() ?: 0L,
                            vehicleId = ((data["vehicle"] as? Map<*, *>)?.get("id") as? Number)?.toLong() ?: 0L,
                            status = data["status"] as? String ?: "IN_PROGRESS",
                            startTime = data["startTime"] as? String,
                            endTime = data["endTime"] as? String,
                            startLat = (data["startLat"] as? Number)?.toDouble(),
                            startLng = (data["startLng"] as? Number)?.toDouble(),
                            endLat = (data["endLat"] as? Number)?.toDouble(),
                            endLng = (data["endLng"] as? Number)?.toDouble()
                        )
                    } catch (e: Exception) {
                        Log.e("ExecutionRepository", "Erro ao mapear execution: ${e.message}")
                        null
                    }
                    
                    if (execution != null) {
                        Result.success(execution)
                    } else {
                        Result.failure(Exception("Erro ao processar resposta da execução"))
                    }
                } else {
                    Result.failure(Exception("Resposta vazia do servidor"))
                }
            } else {
                val errorMsg = response.errorBody()?.string() ?: "Erro desconhecido"
                Log.e("ExecutionRepository", "Erro ao iniciar execução: ${response.code()} - $errorMsg")
                Result.failure(Exception("Erro ao iniciar execução: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e("ExecutionRepository", "Exceção ao iniciar execução: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    suspend fun completeExecution(executionId: Long, endLat: Double? = null, endLng: Double? = null, notes: String? = null): Result<Execution> = withContext(Dispatchers.IO) {
        try {
            val request = ExecutionCompleteRequest(
                endLat = endLat,
                endLng = endLng,
                notes = notes
            )
            
            val response = RetrofitClient.apiService.completeExecution(executionId, request)
            
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val data = body["data"] as? Map<String, Any>
                
                if (data != null) {
                    val execution = try {
                        Execution(
                            id = (data["id"] as? Number)?.toLong() ?: 0L,
                            assignmentId = (data["assignmentId"] as? Number)?.toLong() ?: 0L,
                            routeId = ((data["route"] as? Map<*, *>)?.get("id") as? Number)?.toLong() ?: 0L,
                            routeName = (data["route"] as? Map<*, *>)?.get("name") as? String,
                            driverId = ((data["driver"] as? Map<*, *>)?.get("id") as? Number)?.toLong() ?: 0L,
                            vehicleId = ((data["vehicle"] as? Map<*, *>)?.get("id") as? Number)?.toLong() ?: 0L,
                            status = data["status"] as? String ?: "COMPLETED",
                            startTime = data["startTime"] as? String,
                            endTime = data["endTime"] as? String,
                            startLat = (data["startLat"] as? Number)?.toDouble(),
                            startLng = (data["startLng"] as? Number)?.toDouble(),
                            endLat = (data["endLat"] as? Number)?.toDouble(),
                            endLng = (data["endLng"] as? Number)?.toDouble()
                        )
                    } catch (e: Exception) {
                        Log.e("ExecutionRepository", "Erro ao mapear execution: ${e.message}")
                        null
                    }
                    
                    if (execution != null) {
                        Result.success(execution)
                    } else {
                        Result.failure(Exception("Erro ao processar resposta da execução"))
                    }
                } else {
                    Result.failure(Exception("Resposta vazia do servidor"))
                }
            } else {
                val errorMsg = response.errorBody()?.string() ?: "Erro desconhecido"
                Log.e("ExecutionRepository", "Erro ao finalizar execução: ${response.code()} - $errorMsg")
                Result.failure(Exception("Erro ao finalizar execução: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e("ExecutionRepository", "Exceção ao finalizar execução: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    suspend fun cancelExecution(executionId: Long, reason: String? = null): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val request = mapOf<String, Any>(
                "reason" to (reason ?: "Cancelado pelo motorista")
            )
            
            val response = RetrofitClient.apiService.cancelExecution(executionId, request)
            
            if (response.isSuccessful) {
                Result.success(true)
            } else {
                val errorMsg = response.errorBody()?.string() ?: "Erro desconhecido"
                Log.e("ExecutionRepository", "Erro ao cancelar execução: ${response.code()} - $errorMsg")
                Result.failure(Exception("Erro ao cancelar execução: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e("ExecutionRepository", "Exceção ao cancelar execução: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    suspend fun getMyCurrentExecution(): Result<Execution?> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.apiService.getMyCurrentExecution()
            
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val data = body["data"] as? Map<String, Any>?
                
                if (data != null) {
                    val execution = try {
                        Execution(
                            id = (data["id"] as? Number)?.toLong() ?: 0L,
                            assignmentId = (data["assignmentId"] as? Number)?.toLong() ?: 0L,
                            routeId = ((data["route"] as? Map<*, *>)?.get("id") as? Number)?.toLong() ?: 0L,
                            routeName = (data["route"] as? Map<*, *>)?.get("name") as? String,
                            driverId = ((data["driver"] as? Map<*, *>)?.get("id") as? Number)?.toLong() ?: 0L,
                            vehicleId = ((data["vehicle"] as? Map<*, *>)?.get("id") as? Number)?.toLong() ?: 0L,
                            status = data["status"] as? String ?: "UNKNOWN",
                            startTime = data["startTime"] as? String,
                            endTime = data["endTime"] as? String,
                            startLat = (data["startLat"] as? Number)?.toDouble(),
                            startLng = (data["startLng"] as? Number)?.toDouble(),
                            endLat = (data["endLat"] as? Number)?.toDouble(),
                            endLng = (data["endLng"] as? Number)?.toDouble()
                        )
                    } catch (e: Exception) {
                        Log.e("ExecutionRepository", "Erro ao mapear execution atual: ${e.message}")
                        null
                    }
                    Result.success(execution)
                } else {
                    Result.success(null)
                }
            } else {
                if (response.code() == 404) {
                    // Não há execução atual - isso é normal
                    Result.success(null)
                } else {
                    val errorMsg = response.errorBody()?.string() ?: "Erro desconhecido"
                    Log.e("ExecutionRepository", "Erro ao buscar execução atual: ${response.code()} - $errorMsg")
                    Result.failure(Exception("Erro ao buscar execução atual: ${response.code()}"))
                }
            }
        } catch (e: Exception) {
            Log.e("ExecutionRepository", "Exceção ao buscar execução atual: ${e.message}", e)
            Result.failure(e)
        }
    }
}

