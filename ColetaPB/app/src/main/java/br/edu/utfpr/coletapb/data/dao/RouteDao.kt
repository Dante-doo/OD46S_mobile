package br.edu.utfpr.coletapb.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RouteDao {

    // Insere uma lista de rotas. Se uma rota já existir, ela será substituída.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllRoutes(routes: List<RouteEntity>)

    // Insere uma lista de pontos de coleta.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllCollectionPoints(points: List<CollectionPointEntity>)

    // Busca todas as rotas salvas no banco de dados.
    @Query("SELECT * FROM routes")
    suspend fun getAllRoutes(): List<RouteEntity>

    // Busca todos os pontos de coleta de uma rota específica.
    @Query("SELECT * FROM collection_points WHERE routeId = :routeId ORDER BY sequence_order ASC")
    suspend fun getCollectionPointsForRoute(routeId: Int): List<CollectionPointEntity>

    // Limpa todas as rotas e pontos (útil antes de uma nova sincronização)
    @Query("DELETE FROM routes")
    suspend fun clearAllRoutes()

    @Query("DELETE FROM collection_points")
    suspend fun clearAllCollectionPoints()
}