package br.edu.utfpr.coletapb.data.repository

import android.util.Log
import br.edu.utfpr.coletapb.data.local.SharedPreferencesHelper
import br.edu.utfpr.coletapb.data.model.Assignment
import br.edu.utfpr.coletapb.data.remote.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AssignmentRepository(private val prefsHelper: SharedPreferencesHelper) {
    
    suspend fun getMyAssignments(): Result<List<Assignment>> = withContext(Dispatchers.IO) {
        try {
            // Cenário 1: Busca a rota atual (se o motorista já iniciou uma rota)
            val currentAssignmentResult = getMyCurrentAssignment()
            val currentAssignment = currentAssignmentResult.getOrNull()
            
            // Cenário 2: Busca todas as escalas ativas disponíveis para o motorista iniciar
            val driverId = prefsHelper.getDriverId().takeIf { it > 0 }
            Log.d("AssignmentRepository", "Buscando escalas ativas com driverId: $driverId")
            val response = RetrofitClient.apiService.getAssignments(
                driverId = driverId,
                status = "ACTIVE"
            )
            
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                Log.d("AssignmentRepository", "Resposta completa: $body")
                
                // A estrutura é: { "success": true, "data": { "assignments": [...], "pagination": {...} } }
                val data = body["data"] as? Map<String, Any>
                val assignmentsList = (data?.get("assignments") as? List<Map<String, Any>>) 
                    ?: emptyList()
                
                Log.d("AssignmentRepository", "Encontradas ${assignmentsList.size} escalas na resposta")
                
                val assignments = assignmentsList.mapNotNull { map ->
                    try {
                        Log.d("AssignmentRepository", "Mapeando assignment: $map")
                        Assignment(
                            id = (map["id"] as? Number)?.toLong() ?: 0L,
                            routeId = ((map["route"] as? Map<*, *>)?.get("id") as? Number)?.toLong() ?: 0L,
                            routeName = (map["route"] as? Map<*, *>)?.get("name") as? String,
                            driverId = ((map["driver"] as? Map<*, *>)?.get("id") as? Number)?.toLong() ?: 0L,
                            driverName = (map["driver"] as? Map<*, *>)?.get("name") as? String,
                            vehicleId = ((map["vehicle"] as? Map<*, *>)?.get("id") as? Number)?.toLong() ?: 0L,
                            vehiclePlate = (map["vehicle"] as? Map<*, *>)?.get("licensePlate") as? String
                                ?: (map["vehicle"] as? Map<*, *>)?.get("plate") as? String, // Fallback para "plate"
                            status = map["status"] as? String ?: "UNKNOWN",
                            startDate = map["startDate"] as? String,
                            endDate = map["endDate"] as? String,
                            frequency = map["frequency"] as? String,
                            isCurrent = false // Escalas ativas não são a rota atual
                        )
                    } catch (e: Exception) {
                        Log.e("AssignmentRepository", "Erro ao mapear assignment: ${e.message}", e)
                        null
                    }
                }
                
                Log.d("AssignmentRepository", "Mapeadas ${assignments.size} escalas ativas com sucesso")
                
                // Combina a rota atual (se existir) com as escalas ativas disponíveis
                val allAssignments = mutableListOf<Assignment>()
                
                // Cria um conjunto de IDs já adicionados para evitar duplicatas
                val addedIds = mutableSetOf<Long>()
                
                // Se há rota atual, verifica se ela já está na lista de escalas ativas
                if (currentAssignment != null) {
                    val existingAssignment = assignments.find { it.id == currentAssignment.id }
                    if (existingAssignment != null) {
                        // Se já existe na lista, marca como atual e adiciona
                        allAssignments.add(existingAssignment.copy(isCurrent = true))
                        addedIds.add(existingAssignment.id)
                        Log.d("AssignmentRepository", "Rota atual encontrada na lista e marcada como atual")
                    } else {
                        // Se não existe, adiciona como atual
                        allAssignments.add(currentAssignment)
                        addedIds.add(currentAssignment.id)
                        Log.d("AssignmentRepository", "Adicionada rota atual (não estava na lista de escalas ativas)")
                    }
                }
                
                // Adiciona todas as escalas ativas que ainda não foram adicionadas
                assignments.forEach { assignment ->
                    if (!addedIds.contains(assignment.id)) {
                        allAssignments.add(assignment)
                        addedIds.add(assignment.id)
                    }
                }
                
                Log.d("AssignmentRepository", "Total de ${allAssignments.size} escalas (sem duplicatas)")
                
                Result.success(allAssignments)
            } else {
                val errorMsg = response.errorBody()?.string() ?: "Erro desconhecido"
                Log.e("AssignmentRepository", "Erro ao buscar assignments: ${response.code()} - $errorMsg")
                Result.failure(Exception("Erro ao buscar escalas: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e("AssignmentRepository", "Exceção ao buscar assignments: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    suspend fun getMyCurrentAssignment(): Result<Assignment?> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.apiService.getMyCurrentAssignment()
            
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                Log.d("AssignmentRepository", "Resposta my-current: $body")
                
                // A estrutura pode ser: { "success": true, "data": { "assignment": {...} } }
                // ou: { "success": true, "data": {...} }
                val data = body["data"] as? Map<String, Any>?
                
                // Tenta primeiro "assignment" dentro de "data", depois "data" diretamente
                val assignmentData = (data?.get("assignment") as? Map<String, Any>) 
                    ?: data
                
                if (assignmentData != null) {
                    val assignment = try {
                        Log.d("AssignmentRepository", "Mapeando assignment atual: $assignmentData")
                        Assignment(
                            id = (assignmentData["id"] as? Number)?.toLong() ?: 0L,
                            routeId = ((assignmentData["route"] as? Map<*, *>)?.get("id") as? Number)?.toLong() ?: 0L,
                            routeName = (assignmentData["route"] as? Map<*, *>)?.get("name") as? String,
                            driverId = ((assignmentData["driver"] as? Map<*, *>)?.get("id") as? Number)?.toLong() ?: 0L,
                            driverName = (assignmentData["driver"] as? Map<*, *>)?.get("name") as? String,
                            vehicleId = ((assignmentData["vehicle"] as? Map<*, *>)?.get("id") as? Number)?.toLong() ?: 0L,
                            vehiclePlate = (assignmentData["vehicle"] as? Map<*, *>)?.get("licensePlate") as? String
                                ?: (assignmentData["vehicle"] as? Map<*, *>)?.get("plate") as? String, // Fallback para "plate"
                            status = assignmentData["status"] as? String ?: "UNKNOWN",
                            startDate = assignmentData["startDate"] as? String,
                            endDate = assignmentData["endDate"] as? String,
                            frequency = assignmentData["frequency"] as? String,
                            isCurrent = true // Rota retornada por /my-current é a rota atual
                        )
                    } catch (e: Exception) {
                        Log.e("AssignmentRepository", "Erro ao mapear assignment atual: ${e.message}", e)
                        null
                    }
                    Result.success(assignment)
                } else {
                    Result.success(null)
                }
            } else {
                if (response.code() == 404) {
                    // Não há assignment atual - isso é normal
                    Log.d("AssignmentRepository", "Nenhuma escala atual encontrada (404)")
                    Result.success(null)
                } else {
                    val errorMsg = response.errorBody()?.string() ?: "Erro desconhecido"
                    Log.e("AssignmentRepository", "Erro ao buscar assignment atual: ${response.code()} - $errorMsg")
                    Result.failure(Exception("Erro ao buscar escala atual: ${response.code()}"))
                }
            }
        } catch (e: Exception) {
            Log.e("AssignmentRepository", "Exceção ao buscar assignment atual: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * Busca todas as assignments (para ADMIN)
     */
    suspend fun getAllAssignments(status: String? = null): Result<List<Assignment>> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.apiService.getAssignments(
                driverId = null, // ADMIN não filtra por driver
                status = status
            )
            
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val data = body["data"] as? Map<String, Any>
                val assignmentsList = (data?.get("assignments") as? List<Map<String, Any>>) 
                    ?: emptyList()
                
                val assignments = assignmentsList.mapNotNull { map ->
                    try {
                        Assignment(
                            id = (map["id"] as? Number)?.toLong() ?: 0L,
                            routeId = ((map["route"] as? Map<*, *>)?.get("id") as? Number)?.toLong() ?: 0L,
                            routeName = (map["route"] as? Map<*, *>)?.get("name") as? String,
                            driverId = ((map["driver"] as? Map<*, *>)?.get("id") as? Number)?.toLong() ?: 0L,
                            driverName = (map["driver"] as? Map<*, *>)?.get("name") as? String,
                            vehicleId = ((map["vehicle"] as? Map<*, *>)?.get("id") as? Number)?.toLong() ?: 0L,
                            vehiclePlate = (map["vehicle"] as? Map<*, *>)?.get("licensePlate") as? String
                                ?: (map["vehicle"] as? Map<*, *>)?.get("plate") as? String, // Fallback para "plate"
                            status = map["status"] as? String ?: "UNKNOWN",
                            startDate = map["startDate"] as? String,
                            endDate = map["endDate"] as? String,
                            frequency = map["frequency"] as? String,
                            isCurrent = false
                        )
                    } catch (e: Exception) {
                        Log.e("AssignmentRepository", "Erro ao mapear assignment: ${e.message}", e)
                        null
                    }
                }
                
                Result.success(assignments)
            } else {
                val errorMsg = response.errorBody()?.string() ?: "Erro desconhecido"
                Log.e("AssignmentRepository", "Erro ao buscar assignments: ${response.code()} - $errorMsg")
                Result.failure(Exception("Erro ao buscar escalas: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e("AssignmentRepository", "Exceção ao buscar assignments: ${e.message}", e)
            Result.failure(e)
        }
    }
}

