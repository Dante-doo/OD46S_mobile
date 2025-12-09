package br.edu.utfpr.coletapb.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import br.edu.utfpr.coletapb.data.model.RouteEntity

@Dao
interface RouteDao {

    @Query("SELECT * FROM routes ORDER BY name ASC")
    suspend fun getAllRoutes(): List<RouteEntity>

    @Query("SELECT * FROM routes WHERE id = :id")
    suspend fun getById(id: Long): RouteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllRoutes(routes: List<RouteEntity>)

    @Query("DELETE FROM routes")
    suspend fun clearAllRoutes()
}