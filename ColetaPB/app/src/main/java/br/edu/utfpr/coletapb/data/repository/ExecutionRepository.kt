package br.edu.utfpr.coletapb.data.repository

import android.util.Log
import br.edu.utfpr.coletapb.data.local.SharedPreferencesHelper
import br.edu.utfpr.coletapb.data.model.Execution
import br.edu.utfpr.coletapb.data.model.ExecutionCompleteRequest
import br.edu.utfpr.coletapb.data.model.ExecutionRequest
import br.edu.utfpr.coletapb.data.remote.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

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
                    // Backend retorna { data: { execution: { ... } } }
                    val execData = (data["execution"] as? Map<String, Any>) ?: data
                    
                    val execution = try {
                        Execution(
                            id = (execData["id"] as? Number)?.toLong() ?: 0L,
                            assignmentId = (execData["assignmentId"] as? Number)?.toLong()
                                // Fallback: tenta pegar do objeto assignment.inner.id
                                ?: ((execData["assignment"] as? Map<*, *>)?.get("id") as? Number)?.toLong()
                                ?: 0L,
                            routeId = ((execData["route"] as? Map<*, *>)?.get("id") as? Number)?.toLong()
                                // Fallback: rota dentro de assignment
                                ?: (((execData["assignment"] as? Map<*, *>)?.get("route") as? Map<*, *>)?.get("id") as? Number)?.toLong()
                                ?: 0L,
                            routeName = (execData["route"] as? Map<*, *>)?.get("name") as? String
                                ?: (((execData["assignment"] as? Map<*, *>)?.get("route") as? Map<*, *>)?.get("name") as? String),
                            driverId = ((execData["driver"] as? Map<*, *>)?.get("id") as? Number)?.toLong()
                                ?: (((execData["assignment"] as? Map<*, *>)?.get("driver") as? Map<*, *>)?.get("id") as? Number)?.toLong()
                                ?: 0L,
                            vehicleId = ((execData["vehicle"] as? Map<*, *>)?.get("id") as? Number)?.toLong()
                                ?: (((execData["assignment"] as? Map<*, *>)?.get("vehicle") as? Map<*, *>)?.get("id") as? Number)?.toLong()
                                ?: 0L,
                            status = execData["status"] as? String ?: "IN_PROGRESS",
                            startTime = execData["startTime"] as? String,
                            endTime = execData["endTime"] as? String,
                            startLat = (execData["startLat"] as? Number)?.toDouble(),
                            startLng = (execData["startLng"] as? Number)?.toDouble(),
                            endLat = (execData["endLat"] as? Number)?.toDouble(),
                            endLng = (execData["endLng"] as? Number)?.toDouble()
                        )
                    } catch (e: Exception) {
                        Log.e("ExecutionRepository", "Erro ao mapear execution: ${e.message}", e)
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
                val errorBody = response.errorBody()?.string() ?: "Erro desconhecido"
                Log.e("ExecutionRepository", "Erro ao iniciar execução: ${response.code()} - $errorBody")
                
                // Tenta extrair a mensagem do JSON de erro do backend
                val errorMessage = try {
                    val json = JSONObject(errorBody)
                    val errorObj = json.optJSONObject("error")
                    errorObj?.optString("message") ?: json.optString("message") ?: errorBody
                } catch (e: Exception) {
                    // Se não conseguir parsear como JSON, usa o corpo do erro diretamente
                    errorBody
                }
                
                // Trata erro 409 (conflito - execução já existe hoje)
                if (response.code() == 409) {
                    val friendlyMessage = if (errorMessage.contains("already exists", ignoreCase = true) || 
                                             errorMessage.contains("EXECUTION_CONFLICT", ignoreCase = true) ||
                                             errorMessage.contains("já foi executada", ignoreCase = true)) {
                        "Esta rota já foi executada hoje. Não é possível iniciar uma nova execução no mesmo dia."
                    } else {
                        errorMessage
                    }
                    Result.failure(Exception(friendlyMessage))
                } else {
                    // Para outros erros (400, 404, etc), retorna a mensagem extraída do backend
                    Result.failure(Exception(errorMessage))
                }
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
                    // Backend retorna { data: { execution: { ... } } }
                    val execData = (data["execution"] as? Map<String, Any>) ?: data
                    
                    val execution = try {
                        Execution(
                            id = (execData["id"] as? Number)?.toLong() ?: 0L,
                            assignmentId = (execData["assignmentId"] as? Number)?.toLong()
                                ?: ((execData["assignment"] as? Map<*, *>)?.get("id") as? Number)?.toLong()
                                ?: 0L,
                            routeId = ((execData["route"] as? Map<*, *>)?.get("id") as? Number)?.toLong()
                                ?: (((execData["assignment"] as? Map<*, *>)?.get("route") as? Map<*, *>)?.get("id") as? Number)?.toLong()
                                ?: 0L,
                            routeName = (execData["route"] as? Map<*, *>)?.get("name") as? String
                                ?: (((execData["assignment"] as? Map<*, *>)?.get("route") as? Map<*, *>)?.get("name") as? String),
                            driverId = ((execData["driver"] as? Map<*, *>)?.get("id") as? Number)?.toLong()
                                ?: (((execData["assignment"] as? Map<*, *>)?.get("driver") as? Map<*, *>)?.get("id") as? Number)?.toLong()
                                ?: 0L,
                            vehicleId = ((execData["vehicle"] as? Map<*, *>)?.get("id") as? Number)?.toLong()
                                ?: (((execData["assignment"] as? Map<*, *>)?.get("vehicle") as? Map<*, *>)?.get("id") as? Number)?.toLong()
                                ?: 0L,
                            status = execData["status"] as? String ?: "COMPLETED",
                            startTime = execData["startTime"] as? String,
                            endTime = execData["endTime"] as? String,
                            startLat = (execData["startLat"] as? Number)?.toDouble(),
                            startLng = (execData["startLng"] as? Number)?.toDouble(),
                            endLat = (execData["endLat"] as? Number)?.toDouble(),
                            endLng = (execData["endLng"] as? Number)?.toDouble()
                        )
                    } catch (e: Exception) {
                        Log.e("ExecutionRepository", "Erro ao mapear execution: ${e.message}", e)
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
    
    suspend fun getExecutionsByAssignment(assignmentId: Long, status: String? = null): Result<List<Execution>> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.apiService.getExecutions(
                assignmentId = assignmentId,
                status = status
            )
            
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val data = body["data"] as? Map<String, Any>
                val executionsList = (data?.get("executions") as? List<Map<String, Any>>) ?: emptyList()
                
                val executions = executionsList.mapNotNull { execData ->
                    try {
                        // O backend retorna assignment dentro de execData
                        val assignmentData = execData["assignment"] as? Map<*, *>
                        
                        Execution(
                            id = (execData["id"] as? Number)?.toLong() ?: 0L,
                            assignmentId = (execData["assignmentId"] as? Number)?.toLong()
                                ?: (assignmentData?.get("id") as? Number)?.toLong()
                                ?: 0L,
                            routeId = ((assignmentData?.get("route") as? Map<*, *>)?.get("id") as? Number)?.toLong()
                                ?: 0L,
                            routeName = ((assignmentData?.get("route") as? Map<*, *>)?.get("name") as? String),
                            driverId = ((assignmentData?.get("driver") as? Map<*, *>)?.get("id") as? Number)?.toLong()
                                ?: 0L,
                            vehicleId = ((assignmentData?.get("vehicle") as? Map<*, *>)?.get("id") as? Number)?.toLong()
                                ?: 0L,
                            status = execData["status"] as? String ?: "UNKNOWN",
                            startTime = execData["startTime"] as? String,
                            endTime = execData["endTime"] as? String,
                            startLat = (execData["startLat"] as? Number)?.toDouble(),
                            startLng = (execData["startLng"] as? Number)?.toDouble(),
                            endLat = (execData["endLat"] as? Number)?.toDouble(),
                            endLng = (execData["endLng"] as? Number)?.toDouble()
                        )
                    } catch (e: Exception) {
                        Log.e("ExecutionRepository", "Erro ao mapear execution: ${e.message}", e)
                        null
                    }
                }
                
                Result.success(executions)
            } else {
                val errorMsg = response.errorBody()?.string() ?: "Erro desconhecido"
                Log.e("ExecutionRepository", "Erro ao buscar execuções: ${response.code()} - $errorMsg")
                Result.failure(Exception("Erro ao buscar execuções: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e("ExecutionRepository", "Exceção ao buscar execuções: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    suspend fun getMyCurrentExecution(): Result<Execution?> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.apiService.getMyCurrentExecution()
            
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                Log.d("ExecutionRepository", "Resposta my-current execution: $body")

                val data = body["data"] as? Map<String, Any>?

                // Backend retorna { data: { execution: { ... } } }
                // ou, em alguns casos, { data: { ... } }
                val execData = (data?.get("execution") as? Map<String, Any>) ?: data
                
                if (execData != null) {
                    val execution = try {
                        Log.d("ExecutionRepository", "Mapeando execution atual: $execData")
                        Execution(
                            id = (execData["id"] as? Number)?.toLong() ?: 0L,
                            assignmentId = (execData["assignmentId"] as? Number)?.toLong()
                                // Fallback: tenta pegar do objeto assignment.inner.id
                                ?: ((execData["assignment"] as? Map<*, *>)?.get("id") as? Number)?.toLong()
                                ?: 0L,
                            routeId = ((execData["route"] as? Map<*, *>)?.get("id") as? Number)?.toLong()
                                // Fallback: rota dentro de assignment
                                ?: (((execData["assignment"] as? Map<*, *>)?.get("route") as? Map<*, *>)?.get("id") as? Number)?.toLong()
                                ?: 0L,
                            routeName = (execData["route"] as? Map<*, *>)?.get("name") as? String
                                ?: (((execData["assignment"] as? Map<*, *>)?.get("route") as? Map<*, *>)?.get("name") as? String),
                            driverId = ((execData["driver"] as? Map<*, *>)?.get("id") as? Number)?.toLong()
                                ?: (((execData["assignment"] as? Map<*, *>)?.get("driver") as? Map<*, *>)?.get("id") as? Number)?.toLong()
                                ?: 0L,
                            vehicleId = ((execData["vehicle"] as? Map<*, *>)?.get("id") as? Number)?.toLong()
                                ?: (((execData["assignment"] as? Map<*, *>)?.get("vehicle") as? Map<*, *>)?.get("id") as? Number)?.toLong()
                                ?: 0L,
                            status = execData["status"] as? String ?: "UNKNOWN",
                            startTime = execData["startTime"] as? String,
                            endTime = execData["endTime"] as? String,
                            startLat = (execData["startLat"] as? Number)?.toDouble(),
                            startLng = (execData["startLng"] as? Number)?.toDouble(),
                            endLat = (execData["endLat"] as? Number)?.toDouble(),
                            endLng = (execData["endLng"] as? Number)?.toDouble()
                        )
                    } catch (e: Exception) {
                        Log.e("ExecutionRepository", "Erro ao mapear execution atual: ${e.message}", e)
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

