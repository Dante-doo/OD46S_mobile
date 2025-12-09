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
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            
            // Verifica se tem internet (pode não ter VALIDATED imediatamente, mas ainda ter internet)
            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val hasValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val hasTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                              capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                              capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            
            // Considera online se tem internet E (validated OU tem transporte ativo)
            // Isso é mais tolerante - pode estar online mesmo sem VALIDATED ainda
            return hasInternet && (hasValidated || hasTransport)
        } catch (e: Exception) {
            Log.w("SyncRepository", "Erro ao verificar conectividade: ${e.message}", e)
            return false
        }
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
            
            // 2. Sincronizar registros GPS pendentes (OFFLINE)
            // IMPORTANTE: Ordena por timestamp para garantir ordem cronológica
            // Primeiro enviamos os pontos offline via batch
            val pendingGpsRecords = gpsDao.getPendingSync()
            
            if (pendingGpsRecords.isEmpty()) {
                Log.d("SyncRepository", "Nenhum registro GPS pendente para sincronizar")
            } else {
                Log.d("SyncRepository", "📤 Encontrados ${pendingGpsRecords.size} registros GPS OFFLINE pendentes para sincronizar via batch")
            }
            
            // Agrupar por executionId e manter ordem cronológica
            val recordsByExecution = pendingGpsRecords.groupBy { it.executionLocalId }
            
            for ((execLocalId, records) in recordsByExecution) {
                try {
                    // Buscar o backendId da execução
                    val execution = executionDao.getById(execLocalId)
                    val backendExecutionId = execution?.backendId
                    
                    if (backendExecutionId == null) {
                        Log.w("SyncRepository", "Execução local $execLocalId não tem backendId, pulando sincronização")
                        errors.add("Execução local $execLocalId não tem backendId")
                        continue
                    }
                    
                    // Garantir ordem cronológica (já vem ordenado do DAO, mas garantimos aqui também)
                    val sortedRecords = records.sortedBy { it.timestamp }
                    
                    Log.d("SyncRepository", "🔄 Sincronizando ${sortedRecords.size} registros GPS OFFLINE para execução $backendExecutionId (local: $execLocalId) via /api/v1/executions/$backendExecutionId/gps/batch")
                    
                    // Processar em lotes menores para evitar problemas com muitos registros
                    // Lote de 50 registros por vez para garantir estabilidade (backend aceita até 500)
                    val batchSize = 50
                    val batches = sortedRecords.chunked(batchSize)
                    
                    for ((batchIndex, batch) in batches.withIndex()) {
                        try {
                            Log.d("SyncRepository", "📦 Processando lote OFFLINE ${batchIndex + 1}/${batches.size} (${batch.size} registros)")
                            
                            // Converter para GpsRecordRequest mantendo ordem cronológica
                            // IMPORTANTE: Converte timestamp local para UTC antes de enviar
                            // IMPORTANTE: Usa formato simples sem milissegundos (yyyy-MM-ddTHH:mm:ss) para batch
                            // IMPORTANTE: Marca is_offline = true para indicar que foram salvos offline
                            val gpsRequests = batch.map { record ->
                                val timestampUtc = DateUtils.formatLocalToUtcSimple(Date(record.timestamp))
                                if (timestampUtc == null) {
                                    Log.w("SyncRepository", "⚠️ Erro ao converter timestamp do registro ${record.id}")
                                }
                                GpsRecordRequest(
                                    latitude = record.lat.toString(),
                                    longitude = record.lng.toString(),
                                    event_type = record.eventType,
                                    is_offline = true, // SEMPRE true pois são pontos offline
                                    is_automatic = true, // Assumimos que são automáticos
                                    gps_timestamp = timestampUtc ?: ""
                                )
                            }
                            
                            Log.d("SyncRepository", "📤 Enviando lote ${batchIndex + 1} via POST /api/v1/executions/$backendExecutionId/gps/batch")
                            
                            // Enviar em lote usando a rota batch
                            val result = gpsRepository.registerGpsBatch(backendExecutionId, gpsRequests)
                            
                            result.onSuccess { savedCount ->
                                if (savedCount > 0) {
                                    Log.d("SyncRepository", "✅ Lote OFFLINE ${batchIndex + 1} sincronizado com sucesso: $savedCount de ${batch.size} registros")
                                    
                                    // Marcar como sincronizados apenas os registros que foram enviados com sucesso
                                    // IMPORTANTE: Só marca se realmente foi sincronizado (savedCount > 0)
                                    if (savedCount == batch.size) {
                                        // Todos foram sincronizados
                                        batch.forEach { record ->
                                            val updatedRecord = record.copy(isOffline = false)
                                            gpsDao.update(updatedRecord)
                                            Log.d("SyncRepository", "   ✓ Registro ${record.id} marcado como sincronizado (lat=${record.lat}, lng=${record.lng})")
                                        }
                                    } else {
                                        // Apenas alguns foram sincronizados - não marca nenhum para tentar novamente
                                        Log.w("SyncRepository", "⚠️ Apenas $savedCount de ${batch.size} registros foram sincronizados. Não marcando como sincronizado para tentar novamente.")
                                    }
                                    syncedGpsRecords += savedCount
                                } else {
                                    Log.w("SyncRepository", "⚠️ Lote OFFLINE ${batchIndex + 1} não sincronizou nenhum registro (0 de ${batch.size}). Verifique os erros acima.")
                                    errors.add("Lote ${batchIndex + 1}: nenhum registro sincronizado")
                                    // Não marca como sincronizado, tentará novamente na próxima vez
                                }
                            }.onFailure { error ->
                                Log.e("SyncRepository", "❌ Erro ao sincronizar lote OFFLINE ${batchIndex + 1}: ${error.message}")
                                errors.add("Erro ao sincronizar GPS (lote ${batchIndex + 1}): ${error.message}")
                                // Não marca como sincronizado, tentará novamente na próxima vez
                            }
                        } catch (e: Exception) {
                            Log.e("SyncRepository", "❌ Exceção ao processar lote OFFLINE ${batchIndex + 1}: ${e.message}", e)
                            errors.add("GPS (lote ${batchIndex + 1}): ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SyncRepository", "❌ Erro ao sincronizar GPS para execução $execLocalId: ${e.message}", e)
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

