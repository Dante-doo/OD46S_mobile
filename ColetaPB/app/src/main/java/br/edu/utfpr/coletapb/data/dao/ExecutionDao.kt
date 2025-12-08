package br.edu.utfpr.coletapb.data.dao

import androidx.room.*
import br.edu.utfpr.coletapb.data.model.ExecutionLocal

@Dao
interface ExecutionDao {
    @Insert
    suspend fun insert(exec: ExecutionLocal): Long

    @Update
    suspend fun update(exec: ExecutionLocal)

    // Busca por ID local (corrigido para usar localId se necessário na query, mas o parâmetro é o que importa)
    @Query("SELECT * FROM executions_local WHERE localId = :id")
    suspend fun getById(id: Long): ExecutionLocal?

    // --- MÉTODOS QUE FALTAVAM ---

    // Busca a última execução que ainda está IN_PROGRESS
    @Query("SELECT * FROM executions_local WHERE status = 'IN_PROGRESS' ORDER BY localId DESC LIMIT 1")
    suspend fun getActiveExecution(): ExecutionLocal?

    // Busca pelo ID do servidor para evitar duplicidade
    @Query("SELECT * FROM executions_local WHERE serverExecutionId = :serverId LIMIT 1")
    suspend fun getByServerId(serverId: Long): ExecutionLocal?
}