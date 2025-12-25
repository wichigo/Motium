package com.application.motium.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.application.motium.data.local.dao.CompanyLinkDao
import com.application.motium.data.local.dao.ExpenseDao
import com.application.motium.data.local.dao.LicenseDao
import com.application.motium.data.local.dao.PendingFileUploadDao
import com.application.motium.data.local.dao.PendingOperationDao
import com.application.motium.data.local.dao.ProAccountDao
import com.application.motium.data.local.dao.StripeSubscriptionDao
import com.application.motium.data.local.dao.SyncMetadataDao
import com.application.motium.data.local.dao.TripDao
import com.application.motium.data.local.dao.UserDao
import com.application.motium.data.local.dao.VehicleDao
import com.application.motium.data.local.dao.WorkScheduleDao
import com.application.motium.data.local.entities.AutoTrackingSettingsEntity
import com.application.motium.data.local.entities.CompanyLinkEntity
import com.application.motium.data.local.entities.ExpenseEntity
import com.application.motium.data.local.entities.LicenseEntity
import com.application.motium.data.local.entities.PendingFileUploadEntity
import com.application.motium.data.local.entities.PendingOperationEntity
import com.application.motium.data.local.entities.ProAccountEntity
import com.application.motium.data.local.entities.StripeSubscriptionEntity
import com.application.motium.data.local.entities.SyncMetadataEntity
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
        CompanyLinkEntity::class,
        PendingOperationEntity::class,
        SyncMetadataEntity::class,
        LicenseEntity::class,
        ProAccountEntity::class,
        StripeSubscriptionEntity::class,
        PendingFileUploadEntity::class
    ],
    version = 17,  // v17: Added pending_file_uploads table for offline receipt uploads
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
    abstract fun pendingOperationDao(): PendingOperationDao
    abstract fun syncMetadataDao(): SyncMetadataDao
    abstract fun licenseDao(): LicenseDao
    abstract fun proAccountDao(): ProAccountDao
    abstract fun stripeSubscriptionDao(): StripeSubscriptionDao
    abstract fun pendingFileUploadDao(): PendingFileUploadDao

    companion object {
        private const val DATABASE_NAME = "motium_database"

        @Volatile
        private var INSTANCE: MotiumDatabase? = null

        /**
         * Migration from v11 to v12: Trial system
         * - Add trial fields: trialStartedAt, trialEndsAt
         * - Add verification fields: phoneVerified, verifiedPhone, deviceFingerprintId
         * - Note: monthlyTripCount is kept for compatibility but no longer used
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add trial fields
                db.execSQL("ALTER TABLE users ADD COLUMN trialStartedAt TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE users ADD COLUMN trialEndsAt TEXT DEFAULT NULL")
                // Add verification fields
                db.execSQL("ALTER TABLE users ADD COLUMN phoneVerified INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE users ADD COLUMN verifiedPhone TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE users ADD COLUMN deviceFingerprintId TEXT DEFAULT NULL")
            }
        }

        /**
         * Migration from v12 to v13: Schema sync with Supabase
         * - Add favoriteColors to users (JSON array stored as TEXT)
         * - Add invitationToken to company_links
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add favoriteColors to users (JSON array stored as TEXT)
                db.execSQL("ALTER TABLE users ADD COLUMN favoriteColors TEXT NOT NULL DEFAULT '[]'")
                // Add invitationToken to company_links
                db.execSQL("ALTER TABLE company_links ADD COLUMN invitationToken TEXT DEFAULT NULL")
            }
        }

        /**
         * Migration from v14 to v15: Offline-first delta sync architecture
         * - Create pending_operations table for sync queue
         * - Create sync_metadata table for delta sync timestamps
         * - Add sync status fields to all entities (trips, vehicles, expenses, users)
         * - Add indexes for efficient sync queries
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ==================== CREATE NEW TABLES ====================

                // pending_operations: Queue for offline operations
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS pending_operations (
                        id TEXT PRIMARY KEY NOT NULL,
                        entityType TEXT NOT NULL,
                        entityId TEXT NOT NULL,
                        action TEXT NOT NULL,
                        payload TEXT,
                        createdAt INTEGER NOT NULL,
                        retryCount INTEGER NOT NULL DEFAULT 0,
                        lastAttemptAt INTEGER,
                        lastError TEXT,
                        priority INTEGER NOT NULL DEFAULT 0
                    )
                """)

                // sync_metadata: Track last sync timestamps per entity type
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS sync_metadata (
                        entityType TEXT PRIMARY KEY NOT NULL,
                        lastSyncTimestamp INTEGER NOT NULL,
                        lastFullSyncTimestamp INTEGER NOT NULL,
                        syncInProgress INTEGER NOT NULL DEFAULT 0,
                        totalSynced INTEGER NOT NULL DEFAULT 0,
                        lastSyncError TEXT
                    )
                """)

                // ==================== ADD SYNC COLUMNS TO TRIPS ====================
                db.execSQL("ALTER TABLE trips ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'SYNCED'")
                db.execSQL("ALTER TABLE trips ADD COLUMN localUpdatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE trips ADD COLUMN serverUpdatedAt INTEGER")
                db.execSQL("ALTER TABLE trips ADD COLUMN version INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE trips ADD COLUMN deletedAt INTEGER")

                // ==================== ADD SYNC COLUMNS TO VEHICLES ====================
                db.execSQL("ALTER TABLE vehicles ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'SYNCED'")
                db.execSQL("ALTER TABLE vehicles ADD COLUMN localUpdatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE vehicles ADD COLUMN serverUpdatedAt INTEGER")
                db.execSQL("ALTER TABLE vehicles ADD COLUMN version INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE vehicles ADD COLUMN deletedAt INTEGER")

                // ==================== ADD SYNC COLUMNS TO EXPENSES ====================
                db.execSQL("ALTER TABLE expenses ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'SYNCED'")
                db.execSQL("ALTER TABLE expenses ADD COLUMN localUpdatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE expenses ADD COLUMN serverUpdatedAt INTEGER")
                db.execSQL("ALTER TABLE expenses ADD COLUMN version INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE expenses ADD COLUMN deletedAt INTEGER")

                // ==================== ADD SYNC COLUMNS TO USERS ====================
                db.execSQL("ALTER TABLE users ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'SYNCED'")
                db.execSQL("ALTER TABLE users ADD COLUMN localUpdatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE users ADD COLUMN serverUpdatedAt INTEGER")
                db.execSQL("ALTER TABLE users ADD COLUMN version INTEGER NOT NULL DEFAULT 1")

                // ==================== INITIALIZE DATA ====================
                // Initialize localUpdatedAt from existing updatedAt for trips
                db.execSQL("UPDATE trips SET localUpdatedAt = updatedAt WHERE localUpdatedAt = 0")

                // ==================== CREATE INDEXES ====================
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_trips_sync ON trips(syncStatus)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_trips_user_start ON trips(userId, startTime)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_vehicles_sync ON vehicles(syncStatus)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_vehicles_user ON vehicles(userId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_expenses_sync ON expenses(syncStatus)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_expenses_user_date ON expenses(userId, date)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_pending_entity ON pending_operations(entityType, entityId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_pending_created ON pending_operations(createdAt)")
            }
        }

        /**
         * Migration from v15 to v16: Licenses, ProAccounts, StripeSubscriptions
         * - Create licenses table for Pro license management
         * - Create pro_accounts table for company accounts
         * - Create stripe_subscriptions table for subscription tracking
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ==================== TABLE: licenses ====================
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS licenses (
                        id TEXT PRIMARY KEY NOT NULL,
                        proAccountId TEXT NOT NULL,
                        linkedAccountId TEXT,
                        linkedAt INTEGER,
                        isLifetime INTEGER NOT NULL DEFAULT 0,
                        priceMonthlyHt REAL NOT NULL DEFAULT 5.0,
                        vatRate REAL NOT NULL DEFAULT 0.20,
                        status TEXT NOT NULL DEFAULT 'pending',
                        startDate INTEGER,
                        endDate INTEGER,
                        unlinkRequestedAt INTEGER,
                        unlinkEffectiveAt INTEGER,
                        billingStartsAt INTEGER,
                        stripeSubscriptionId TEXT,
                        stripeSubscriptionItemId TEXT,
                        stripePriceId TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        syncStatus TEXT NOT NULL DEFAULT 'SYNCED',
                        localUpdatedAt INTEGER NOT NULL DEFAULT 0,
                        serverUpdatedAt INTEGER,
                        version INTEGER NOT NULL DEFAULT 1
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_licenses_proAccountId ON licenses(proAccountId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_licenses_linkedAccountId ON licenses(linkedAccountId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_licenses_syncStatus ON licenses(syncStatus)")

                // ==================== TABLE: pro_accounts ====================
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS pro_accounts (
                        id TEXT PRIMARY KEY NOT NULL,
                        userId TEXT NOT NULL,
                        companyName TEXT NOT NULL,
                        siret TEXT,
                        vatNumber TEXT,
                        legalForm TEXT,
                        billingAddress TEXT,
                        billingEmail TEXT,
                        billingDay INTEGER NOT NULL DEFAULT 5,
                        departments TEXT NOT NULL DEFAULT '[]',
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        syncStatus TEXT NOT NULL DEFAULT 'SYNCED',
                        localUpdatedAt INTEGER NOT NULL DEFAULT 0,
                        serverUpdatedAt INTEGER,
                        version INTEGER NOT NULL DEFAULT 1
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pro_accounts_userId ON pro_accounts(userId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pro_accounts_syncStatus ON pro_accounts(syncStatus)")

                // ==================== TABLE: stripe_subscriptions ====================
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS stripe_subscriptions (
                        id TEXT PRIMARY KEY NOT NULL,
                        oduserId TEXT,
                        proAccountId TEXT,
                        stripeSubscriptionId TEXT NOT NULL,
                        stripeCustomerId TEXT NOT NULL,
                        stripePriceId TEXT,
                        stripeProductId TEXT,
                        subscriptionType TEXT NOT NULL,
                        status TEXT NOT NULL,
                        quantity INTEGER NOT NULL DEFAULT 1,
                        currency TEXT NOT NULL DEFAULT 'eur',
                        unitAmountCents INTEGER,
                        currentPeriodStart INTEGER,
                        currentPeriodEnd INTEGER,
                        cancelAtPeriodEnd INTEGER NOT NULL DEFAULT 0,
                        canceledAt INTEGER,
                        endedAt INTEGER,
                        metadata TEXT NOT NULL DEFAULT '{}',
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        syncStatus TEXT NOT NULL DEFAULT 'SYNCED',
                        localUpdatedAt INTEGER NOT NULL DEFAULT 0,
                        serverUpdatedAt INTEGER,
                        version INTEGER NOT NULL DEFAULT 1
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_stripe_subscriptions_oduserId ON stripe_subscriptions(oduserId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_stripe_subscriptions_proAccountId ON stripe_subscriptions(proAccountId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_stripe_subscriptions_syncStatus ON stripe_subscriptions(syncStatus)")
            }
        }

        /**
         * Migration from v16 to v17: Offline-first file uploads
         * - Create pending_file_uploads table for expense receipt uploads
         * - Enables saving expenses with photos offline
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ==================== TABLE: pending_file_uploads ====================
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS pending_file_uploads (
                        id TEXT PRIMARY KEY NOT NULL,
                        expenseId TEXT NOT NULL,
                        localUri TEXT NOT NULL,
                        uploadedUrl TEXT,
                        status TEXT NOT NULL DEFAULT 'PENDING',
                        createdAt INTEGER NOT NULL,
                        retryCount INTEGER NOT NULL DEFAULT 0,
                        lastAttemptAt INTEGER,
                        lastError TEXT
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_file_uploads_expenseId ON pending_file_uploads(expenseId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_file_uploads_status ON pending_file_uploads(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_file_uploads_createdAt ON pending_file_uploads(createdAt)")
            }
        }

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
            db.pendingOperationDao().deleteAll()
            db.syncMetadataDao().deleteAll()
            db.licenseDao().deleteAll()
            db.proAccountDao().deleteAll()
            db.stripeSubscriptionDao().deleteAll()
            db.pendingFileUploadDao().deleteAll()
        }
    }
}
