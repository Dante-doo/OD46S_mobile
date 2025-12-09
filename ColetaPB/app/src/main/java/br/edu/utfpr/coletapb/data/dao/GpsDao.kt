package br.edu.utfpr.coletapb.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import br.edu.utfpr.coletapb.data.model.GpsRecordLocal

@Dao
interface GpsDao {
    @Insert suspend fun insert(point: GpsRecordLocal): Long
    @Update suspend fun update(point: GpsRecordLocal)
    @Query("SELECT * FROM gps_records_local WHERE executionLocalId = :execId ORDER BY timestamp")
    suspend fun listByExecution(execId: Long): List<GpsRecordLocal>
    
    @Query("SELECT * FROM gps_records_local WHERE executionLocalId = :execId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestByExecution(execId: Long): GpsRecordLocal?
    
    @Query("SELECT * FROM gps_records_local WHERE isOffline = 1 ORDER BY timestamp ASC")
    suspend fun getPendingSync(): List<GpsRecordLocal>
    
    @Query("SELECT COUNT(*) FROM gps_records_local WHERE isOffline = 1")
    suspend fun getPendingSyncCount(): Int
}
