package br.edu.utfpr.coletapb.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import br.edu.utfpr.coletapb.data.model.GpsRecordLocal

@Dao
interface GpsDao {
    @Insert suspend fun insert(point: GpsRecordLocal): Long
    @Query("SELECT * FROM gps_records_local WHERE executionLocalId = :execId ORDER BY timestamp")
    suspend fun listByExecution(execId: Long): List<GpsRecordLocal>
}
