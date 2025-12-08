package br.edu.utfpr.coletapb.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
// Imports dos DAOs
import br.edu.utfpr.coletapb.data.dao.ExecutionDao
import br.edu.utfpr.coletapb.data.dao.GpsDao
import br.edu.utfpr.coletapb.data.dao.RouteDao
// Imports das Entidades (Models) <--- AQUI ESTAVA O PROBLEMA
import br.edu.utfpr.coletapb.data.model.ExecutionLocal
import br.edu.utfpr.coletapb.data.model.GpsRecordLocal
import br.edu.utfpr.coletapb.data.model.RouteEntity
import br.edu.utfpr.coletapb.data.model.CollectionPointEntity

@Database(
    entities = [
        RouteEntity::class,
        CollectionPointEntity::class,
        ExecutionLocal::class,
        GpsRecordLocal::class
    ],
    version = 3, // Mantém a versão 3 (ou aumenta se der erro de schema)
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun routeDao(): RouteDao
    abstract fun executionDao(): ExecutionDao
    abstract fun gpsDao(): GpsDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "coleta_pb_database"
                )
                    .fallbackToDestructiveMigration() // Recria o banco se mudar versão
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}