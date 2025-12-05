package com.application.motium.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.application.motium.data.local.dao.CompanyLinkDao
import com.application.motium.data.local.dao.ExpenseDao
import com.application.motium.data.local.dao.TripDao
import com.application.motium.data.local.dao.UserDao
import com.application.motium.data.local.dao.VehicleDao
import com.application.motium.data.local.dao.WorkScheduleDao
import com.application.motium.data.local.entities.AutoTrackingSettingsEntity
import com.application.motium.data.local.entities.CompanyLinkEntity
import com.application.motium.data.local.entities.ExpenseEntity
import com.application.motium.data.local.entities.TripConverters
import com.application.motium.data.local.entities.TripEntity
import com.application.motium.data.local.entities.UserEntity
import com.application.motium.data.local.entities.VehicleEntity
import com.application.motium.data.local.entities.WorkScheduleEntity

/**
 * Main Room database for Motium app.
 * Stores users, trips, and vehicles locally for offline functionality.
 *
 * This database enables:
 * - Offline-first architecture
 * - User session persistence across app restarts
 * - Trip and vehicle data available without internet
 * - Automatic sync when connection is restored
 */
@Database(
    entities = [
        UserEntity::class,
        TripEntity::class,
        VehicleEntity::class,
        ExpenseEntity::class,
        WorkScheduleEntity::class,
        AutoTrackingSettingsEntity::class,
        CompanyLinkEntity::class
    ],
    version = 4,
    exportSchema = false
)
@TypeConverters(TripConverters::class)
abstract class MotiumDatabase : RoomDatabase() {

    abstract fun userDao(): UserDao
    abstract fun tripDao(): TripDao
    abstract fun vehicleDao(): VehicleDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun workScheduleDao(): WorkScheduleDao
    abstract fun companyLinkDao(): CompanyLinkDao

    companion object {
        private const val DATABASE_NAME = "motium_database"

        @Volatile
        private var INSTANCE: MotiumDatabase? = null

        /**
         * Get singleton instance of the database.
         * Uses double-check locking for thread safety.
         */
        fun getInstance(context: Context): MotiumDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): MotiumDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                MotiumDatabase::class.java,
                DATABASE_NAME
            )
                // NOTE: fallbackToDestructiveMigration() est OK en développement
                // Cela permet à Room de recréer la base si le schéma change
                // ⚠️ AVANT LA PRODUCTION: Retirer cette ligne et écrire des migrations propres
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        }

        /**
         * Clear all data from the database.
         * Used during complete logout.
         */
        suspend fun clearAllData(context: Context) {
            val db = getInstance(context)
            db.userDao().deleteAllUsers()
            db.tripDao().deleteAllTrips()
            db.vehicleDao().deleteAllVehicles()
            db.expenseDao().deleteAllExpenses()
            db.workScheduleDao().deleteAllWorkSchedules()
            db.workScheduleDao().deleteAllAutoTrackingSettings()
            db.companyLinkDao().deleteAllCompanyLinks()
        }
    }
}
