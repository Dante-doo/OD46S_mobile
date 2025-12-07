package br.edu.utfpr.coletapb.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import br.edu.utfpr.coletapb.data.AppDatabase
import br.edu.utfpr.coletapb.data.dao.ExecutionDao
import br.edu.utfpr.coletapb.data.dao.GpsDao
import br.edu.utfpr.coletapb.data.local.SharedPreferencesHelper
import br.edu.utfpr.coletapb.data.model.ExecutionLocal
import br.edu.utfpr.coletapb.data.model.GpsRecordLocal
import br.edu.utfpr.coletapb.data.model.GpsRecordRequest
import br.edu.utfpr.coletapb.utils.DateUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date

class SyncRepository(private val context: Context, private val prefsHelper: SharedPreferencesHelper) {
    
    private val db = AppDatabase.getDatabase(context)
    private val executionDao = db.executionDao()
    private val gpsDao = db.gpsDao()
    private val gpsRepository = GpsRepository(prefsHelper)
    private val executionRepository = ExecutionRepository(prefsHelper)
    
    fun isOnline(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    
    suspend fun syncPendingData(): SyncResult = withContext(Dispatchers.IO) {
        if (!isOnline()) {
            return@withContext SyncResult(0, 0, "Sem conexão com a internet")
        }
        
        var syncedExecutions = 0
        var syncedGpsRecords = 0
        val errors = mutableListOf<String>()
        
        try {
            // 1. Sincronizar execuções pendentes
            val pendingExecutions = executionDao.getPendingSync()
            for (execution in pendingExecutions) {
                try {
                    if (execution.backendId == null) {
                        // Criar nova execução no backend
                        val result = executionRepository.startExecution(
                            assignmentId = execution.routeId, // TODO: usar assignmentId real
                            startLat = execution.startLat,
                            startLng = execution.startLng
                        )
                        
                        result.onSuccess { backendExecution ->
                            // Atualizar com o ID do backend
                            executionDao.update(
                                execution.copy(
                                    backendId = backendExecution.id,
                                    status = "IN_PROGRESS"
                                )
                            )
                            syncedExecutions++
                        }.onFailure { error ->
                            errors.add("Erro ao criar execução: ${error.message}")
                        }
                    } else {
                        // Atualizar execução existente
                        if (execution.status == "COMPLETED") {
                            val result = executionRepository.completeExecution(
                                executionId = execution.backendId,
                                endLat = execution.endLat,
                                endLng = execution.endLng
                            )
                            
                            result.onSuccess {
                                syncedExecutions++
                            }.onFailure { error ->
                                errors.add("Erro ao finalizar execução: ${error.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SyncRepository", "Erro ao sincronizar execução ${execution.localId}: ${e.message}")
                    errors.add("Execução ${execution.localId}: ${e.message}")
                }
            }
            
            // 2. Sincronizar registros GPS pendentes
            val pendingGpsRecords = gpsDao.getPendingSync()
            
            // Agrupar por executionId
            val recordsByExecution = pendingGpsRecords.groupBy { it.executionLocalId }
            
            for ((execLocalId, records) in recordsByExecution) {
                try {
                    // Buscar o backendId da execução
                    val execution = executionDao.getById(execLocalId)
                    val backendExecutionId = execution?.backendId
                    
                    if (backendExecutionId == null) {
                        errors.add("Execução local $execLocalId não tem backendId")
                        continue
                    }
                    
                    // Converter para GpsRecordRequest
                    // IMPORTANTE: Converte timestamp local para UTC antes de enviar
                    val gpsRequests = records.map { record ->
                        val timestampUtc = DateUtils.formatLocalToUtc(Date(record.timestamp))
                        GpsRecordRequest(
                            latitude = record.lat.toString(),
                            longitude = record.lng.toString(),
                            event_type = record.eventType,
                            is_offline = true,
                            gps_timestamp = timestampUtc ?: ""
                        )
                    }
                    
                    // Enviar em lote
                    val result = gpsRepository.registerGpsBatch(backendExecutionId, gpsRequests)
                    
                    result.onSuccess { savedCount ->
                        // Marcar como sincronizados
                        records.forEach { record ->
                            val updatedRecord = record.copy(isOffline = false)
                            gpsDao.update(updatedRecord)
                        }
                        syncedGpsRecords += savedCount
                    }.onFailure { error ->
                        errors.add("Erro ao sincronizar GPS: ${error.message}")
                    }
                } catch (e: Exception) {
                    Log.e("SyncRepository", "Erro ao sincronizar GPS: ${e.message}")
                    errors.add("GPS: ${e.message}")
                }
            }
            
            SyncResult(
                syncedExecutions = syncedExecutions,
                syncedGpsRecords = syncedGpsRecords,
                errorMessage = if (errors.isNotEmpty()) errors.joinToString("; ") else null
            )
        } catch (e: Exception) {
            Log.e("SyncRepository", "Erro geral na sincronização: ${e.message}", e)
            SyncResult(0, 0, "Erro geral: ${e.message}")
        }
    }
    
    data class SyncResult(
        val syncedExecutions: Int,
        val syncedGpsRecords: Int,
        val errorMessage: String? = null
    )
}

