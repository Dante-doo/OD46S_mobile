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
                            vehiclePlate = (map["vehicle"] as? Map<*, *>)?.get("plate") as? String,
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
                
                // Adiciona a rota atual primeiro (se existir e não estiver duplicada)
                if (currentAssignment != null) {
                    val isDuplicate = assignments.any { it.id == currentAssignment.id }
                    if (!isDuplicate) {
                        allAssignments.add(currentAssignment)
                        Log.d("AssignmentRepository", "Adicionada rota atual (em execução)")
                    } else {
                        Log.d("AssignmentRepository", "Rota atual já está na lista de escalas ativas")
                    }
                }
                
                // Adiciona todas as escalas ativas disponíveis, marcando a atual se necessário
                assignments.forEach { assignment ->
                    if (currentAssignment != null && assignment.id == currentAssignment.id) {
                        // Marca como atual se for a rota atual
                        allAssignments.add(assignment.copy(isCurrent = true))
                    } else {
                        allAssignments.add(assignment)
                    }
                }
                
                Log.d("AssignmentRepository", "Total de ${allAssignments.size} escalas (atual + disponíveis)")
                
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
                            vehiclePlate = (assignmentData["vehicle"] as? Map<*, *>)?.get("plate") as? String,
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
}

