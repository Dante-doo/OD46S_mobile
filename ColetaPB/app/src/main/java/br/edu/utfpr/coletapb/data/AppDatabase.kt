package br.edu.utfpr.coletapb.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import br.edu.utfpr.coletapb.data.dao.ExecutionDao
import br.edu.utfpr.coletapb.data.dao.GpsDao
import br.edu.utfpr.coletapb.data.model.ExecutionLocal
import br.edu.utfpr.coletapb.data.model.GpsRecordLocal

@Database(
    entities = [
        RouteEntity::class,
        CollectionPointEntity::class,
        ExecutionLocal::class,     // NOVO
        GpsRecordLocal::class      // NOVO
    ],
    version = 2,                   // <— suba a versão
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun routeDao(): RouteDao
    abstract fun executionDao(): ExecutionDao   // NOVO
    abstract fun gpsDao(): GpsDao               // NOVO

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "coleta_pb_database"
                )
                    .fallbackToDestructiveMigration() // ok para desenvolvimento
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
