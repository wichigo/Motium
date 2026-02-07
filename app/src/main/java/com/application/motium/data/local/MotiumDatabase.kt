package com.application.motium.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.application.motium.data.local.dao.CompanyLinkDao
import com.application.motium.data.local.dao.ConsentDao
import com.application.motium.data.local.dao.ExpenseDao
import com.application.motium.data.local.dao.LicenseDao
import com.application.motium.data.local.dao.LinkedUserDao
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
import com.application.motium.data.local.entities.ConsentEntity
import com.application.motium.data.local.entities.ExpenseEntity
import com.application.motium.data.local.entities.LicenseEntity
import com.application.motium.data.local.entities.LinkedUserEntity
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
        PendingFileUploadEntity::class,
        ConsentEntity::class,
        LinkedUserEntity::class
    ],
    version = 28,  // v28: Add profilePhotoUrl to users for profile photo support
    exportSchema = true  // Export schema JSON for CI validation of migrations
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
    abstract fun consentDao(): ConsentDao
    abstract fun linkedUserDao(): LinkedUserDao

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
         * Migration from v13 to v14: No-op migration (bridge)
         * This migration exists to ensure a smooth upgrade path from v13 to v14.
         * Version 14 was originally skipped in development, so this bridges the gap.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No-op: This migration exists to bridge v13 -> v14 for users on v13
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
         * Migration from v17 to v18: Offline-first GDPR consent management
         * - Create consents table for user consent tracking
         * - Enables offline consent management with sync
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ==================== TABLE: consents ====================
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS consents (
                        id TEXT PRIMARY KEY NOT NULL,
                        userId TEXT NOT NULL,
                        consentType TEXT NOT NULL,
                        granted INTEGER NOT NULL,
                        version INTEGER NOT NULL,
                        grantedAt INTEGER,
                        revokedAt INTEGER,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        syncStatus TEXT NOT NULL DEFAULT 'SYNCED',
                        localUpdatedAt INTEGER NOT NULL,
                        serverUpdatedAt INTEGER
                    )
                """)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_consents_userId_consentType ON consents(userId, consentType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_consents_syncStatus ON consents(syncStatus)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_consents_userId ON consents(userId)")
            }
        }

        /**
         * Migration from v18 to v19: Remove phone verification columns
         * - Remove phoneVerified column from users table
         * - Remove verifiedPhone column from users table
         * - Phone verification is no longer required for account creation
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // SQLite < 3.35 doesn't support DROP COLUMN - recreate table
                // Create new users table without phone verification columns
                // NOTE: Do NOT use DEFAULT clauses - Room expects 'undefined' for columns
                // without @ColumnInfo(defaultValue=...) annotation
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS users_new (
                        id TEXT PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL,
                        email TEXT NOT NULL,
                        role TEXT NOT NULL,
                        subscriptionType TEXT NOT NULL,
                        subscriptionExpiresAt TEXT,
                        trialStartedAt TEXT,
                        trialEndsAt TEXT,
                        stripeCustomerId TEXT,
                        stripeSubscriptionId TEXT,
                        phoneNumber TEXT NOT NULL,
                        address TEXT NOT NULL,
                        deviceFingerprintId TEXT,
                        considerFullDistance INTEGER NOT NULL,
                        favoriteColors TEXT NOT NULL,
                        createdAt TEXT NOT NULL,
                        updatedAt TEXT NOT NULL,
                        lastSyncedAt INTEGER,
                        isLocallyConnected INTEGER NOT NULL,
                        syncStatus TEXT NOT NULL,
                        localUpdatedAt INTEGER NOT NULL,
                        serverUpdatedAt INTEGER,
                        version INTEGER NOT NULL
                    )
                """)

                // Copy data from old table, excluding phoneVerified and verifiedPhone
                db.execSQL("""
                    INSERT INTO users_new SELECT
                        id, name, email, role, subscriptionType, subscriptionExpiresAt,
                        trialStartedAt, trialEndsAt, stripeCustomerId, stripeSubscriptionId,
                        phoneNumber, address, deviceFingerprintId, considerFullDistance,
                        favoriteColors, createdAt, updatedAt, lastSyncedAt, isLocallyConnected,
                        syncStatus, localUpdatedAt, serverUpdatedAt, version
                    FROM users
                """)

                // Drop old table and rename new one
                db.execSQL("DROP TABLE users")
                db.execSQL("ALTER TABLE users_new RENAME TO users")

                // Recreate index
                db.execSQL("CREATE INDEX IF NOT EXISTS index_users_email ON users(email)")
            }
        }

        /**
         * Migration from v19 to v20: Invitation flow improvements
         * - Add invitation_email column to company_links (email of invited person)
         * - Add invitation_expires_at column to company_links
         * - Make userId nullable (null until invitation is accepted)
         * - Enables inviting users who don't have an account yet
         */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add new columns to company_links for invitation flow
                db.execSQL("ALTER TABLE company_links ADD COLUMN invitationEmail TEXT")
                db.execSQL("ALTER TABLE company_links ADD COLUMN invitationExpiresAt TEXT")

                // Note: SQLite doesn't support changing NOT NULL to NULL for existing columns
                // The userId column is already TEXT which can store NULL values
                // Room will handle the nullable type mapping correctly
            }
        }

        /**
         * Migration from v20 to v21: Standardize sync pattern across all entities
         * - Add modern sync fields to CompanyLinkEntity, WorkScheduleEntity, AutoTrackingSettingsEntity
         * - Add deletedAt to LicenseEntity for consistency
         * - IMPORTANT: Recreate trips table to remove legacy columns (lastSyncedAt, needsSync)
         *   Room does NOT ignore extra columns - it requires exact schema match
         */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ==================== TRIPS - RECREATE TO REMOVE LEGACY COLUMNS ====================
                // Room requires exact schema match, so we must remove lastSyncedAt and needsSync
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS trips_new (
                        id TEXT PRIMARY KEY NOT NULL,
                        userId TEXT NOT NULL,
                        startTime INTEGER NOT NULL,
                        endTime INTEGER,
                        locations TEXT NOT NULL,
                        totalDistance REAL NOT NULL,
                        isValidated INTEGER NOT NULL,
                        vehicleId TEXT,
                        startAddress TEXT,
                        endAddress TEXT,
                        notes TEXT,
                        tripType TEXT,
                        reimbursementAmount REAL,
                        isWorkHomeTrip INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        matchedRouteCoordinates TEXT,
                        syncStatus TEXT NOT NULL,
                        localUpdatedAt INTEGER NOT NULL,
                        serverUpdatedAt INTEGER,
                        version INTEGER NOT NULL,
                        deletedAt INTEGER
                    )
                """)

                // Copy data from old table, excluding lastSyncedAt and needsSync
                db.execSQL("""
                    INSERT INTO trips_new (
                        id, userId, startTime, endTime, locations, totalDistance,
                        isValidated, vehicleId, startAddress, endAddress, notes, tripType,
                        reimbursementAmount, isWorkHomeTrip, createdAt, updatedAt,
                        matchedRouteCoordinates, syncStatus, localUpdatedAt, serverUpdatedAt,
                        version, deletedAt
                    )
                    SELECT
                        id, userId, startTime, endTime, locations, totalDistance,
                        isValidated, vehicleId, startAddress, endAddress, notes, tripType,
                        reimbursementAmount, isWorkHomeTrip, createdAt, updatedAt,
                        matchedRouteCoordinates, syncStatus, localUpdatedAt, serverUpdatedAt,
                        version, deletedAt
                    FROM trips
                """)

                // Drop old table and rename new one
                db.execSQL("DROP TABLE trips")
                db.execSQL("ALTER TABLE trips_new RENAME TO trips")

                // Recreate indexes for trips
                db.execSQL("CREATE INDEX IF NOT EXISTS index_trips_syncStatus ON trips(syncStatus)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_trips_userId_startTime ON trips(userId, startTime)")

                // ==================== VEHICLES - RECREATE TO REMOVE LEGACY COLUMNS ====================
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS vehicles_new (
                        id TEXT PRIMARY KEY NOT NULL,
                        userId TEXT NOT NULL,
                        name TEXT NOT NULL,
                        type TEXT NOT NULL,
                        licensePlate TEXT,
                        power TEXT,
                        fuelType TEXT,
                        mileageRate REAL NOT NULL,
                        isDefault INTEGER NOT NULL,
                        totalMileagePerso REAL NOT NULL,
                        totalMileagePro REAL NOT NULL,
                        totalMileageWorkHome REAL NOT NULL,
                        createdAt TEXT NOT NULL,
                        updatedAt TEXT NOT NULL,
                        syncStatus TEXT NOT NULL,
                        localUpdatedAt INTEGER NOT NULL,
                        serverUpdatedAt INTEGER,
                        version INTEGER NOT NULL,
                        deletedAt INTEGER
                    )
                """)

                db.execSQL("""
                    INSERT INTO vehicles_new (
                        id, userId, name, type, licensePlate, power, fuelType,
                        mileageRate, isDefault, totalMileagePerso, totalMileagePro,
                        totalMileageWorkHome, createdAt, updatedAt, syncStatus,
                        localUpdatedAt, serverUpdatedAt, version, deletedAt
                    )
                    SELECT
                        id, userId, name, type, licensePlate, power, fuelType,
                        mileageRate, isDefault, totalMileagePerso, totalMileagePro,
                        totalMileageWorkHome, createdAt, updatedAt, syncStatus,
                        localUpdatedAt, serverUpdatedAt, version, deletedAt
                    FROM vehicles
                """)

                db.execSQL("DROP TABLE vehicles")
                db.execSQL("ALTER TABLE vehicles_new RENAME TO vehicles")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_vehicles_syncStatus ON vehicles(syncStatus)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_vehicles_userId ON vehicles(userId)")

                // ==================== EXPENSES - RECREATE TO REMOVE LEGACY COLUMNS ====================
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS expenses_new (
                        id TEXT PRIMARY KEY NOT NULL,
                        userId TEXT NOT NULL,
                        date TEXT NOT NULL,
                        type TEXT NOT NULL,
                        amount REAL NOT NULL,
                        amountHT REAL,
                        note TEXT NOT NULL,
                        photoUri TEXT,
                        createdAt TEXT NOT NULL,
                        updatedAt TEXT NOT NULL,
                        syncStatus TEXT NOT NULL,
                        localUpdatedAt INTEGER NOT NULL,
                        serverUpdatedAt INTEGER,
                        version INTEGER NOT NULL,
                        deletedAt INTEGER
                    )
                """)

                db.execSQL("""
                    INSERT INTO expenses_new (
                        id, userId, date, type, amount, amountHT, note, photoUri,
                        createdAt, updatedAt, syncStatus, localUpdatedAt,
                        serverUpdatedAt, version, deletedAt
                    )
                    SELECT
                        id, userId, date, type, amount, amountHT, note, photoUri,
                        createdAt, updatedAt, syncStatus, localUpdatedAt,
                        serverUpdatedAt, version, deletedAt
                    FROM expenses
                """)

                db.execSQL("DROP TABLE expenses")
                db.execSQL("ALTER TABLE expenses_new RENAME TO expenses")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_syncStatus ON expenses(syncStatus)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_userId_date ON expenses(userId, date)")

                // ==================== WORK_SCHEDULES - RECREATE TO REMOVE LEGACY COLUMNS ====================
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS work_schedules_new (
                        id TEXT PRIMARY KEY NOT NULL,
                        userId TEXT NOT NULL,
                        dayOfWeek INTEGER NOT NULL,
                        startHour INTEGER NOT NULL,
                        startMinute INTEGER NOT NULL,
                        endHour INTEGER NOT NULL,
                        endMinute INTEGER NOT NULL,
                        isOvernight INTEGER NOT NULL,
                        isActive INTEGER NOT NULL,
                        createdAt TEXT NOT NULL,
                        updatedAt TEXT NOT NULL,
                        syncStatus TEXT NOT NULL DEFAULT 'SYNCED',
                        localUpdatedAt INTEGER NOT NULL DEFAULT 0,
                        serverUpdatedAt INTEGER,
                        version INTEGER NOT NULL DEFAULT 1,
                        deletedAt INTEGER
                    )
                """)

                db.execSQL("""
                    INSERT INTO work_schedules_new (
                        id, userId, dayOfWeek, startHour, startMinute, endHour, endMinute,
                        isOvernight, isActive, createdAt, updatedAt, syncStatus, localUpdatedAt,
                        serverUpdatedAt, version, deletedAt
                    )
                    SELECT
                        id, userId, dayOfWeek, startHour, startMinute, endHour, endMinute,
                        isOvernight, isActive, createdAt, updatedAt, 'SYNCED',
                        strftime('%s', 'now') * 1000, NULL, 1, NULL
                    FROM work_schedules
                """)

                db.execSQL("DROP TABLE work_schedules")
                db.execSQL("ALTER TABLE work_schedules_new RENAME TO work_schedules")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_work_schedules_syncStatus ON work_schedules(syncStatus)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_work_schedules_userId ON work_schedules(userId)")

                // ==================== AUTO_TRACKING_SETTINGS - RECREATE ====================
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS auto_tracking_settings_new (
                        id TEXT PRIMARY KEY NOT NULL,
                        userId TEXT NOT NULL,
                        trackingMode TEXT NOT NULL,
                        minTripDistanceMeters INTEGER NOT NULL,
                        minTripDurationSeconds INTEGER NOT NULL,
                        createdAt TEXT NOT NULL,
                        updatedAt TEXT NOT NULL,
                        syncStatus TEXT NOT NULL DEFAULT 'SYNCED',
                        localUpdatedAt INTEGER NOT NULL DEFAULT 0,
                        serverUpdatedAt INTEGER,
                        version INTEGER NOT NULL DEFAULT 1,
                        deletedAt INTEGER
                    )
                """)

                db.execSQL("""
                    INSERT INTO auto_tracking_settings_new (
                        id, userId, trackingMode, minTripDistanceMeters, minTripDurationSeconds,
                        createdAt, updatedAt, syncStatus, localUpdatedAt, serverUpdatedAt,
                        version, deletedAt
                    )
                    SELECT
                        id, userId, trackingMode, minTripDistanceMeters, minTripDurationSeconds,
                        createdAt, updatedAt, 'SYNCED', strftime('%s', 'now') * 1000, NULL, 1, NULL
                    FROM auto_tracking_settings
                """)

                db.execSQL("DROP TABLE auto_tracking_settings")
                db.execSQL("ALTER TABLE auto_tracking_settings_new RENAME TO auto_tracking_settings")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_auto_tracking_settings_syncStatus ON auto_tracking_settings(syncStatus)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_auto_tracking_settings_userId ON auto_tracking_settings(userId)")

                // ==================== COMPANY_LINKS - RECREATE ====================
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS company_links_new (
                        id TEXT PRIMARY KEY NOT NULL,
                        userId TEXT,
                        linkedProAccountId TEXT NOT NULL,
                        companyName TEXT NOT NULL,
                        department TEXT,
                        status TEXT NOT NULL,
                        shareProfessionalTrips INTEGER NOT NULL,
                        sharePersonalTrips INTEGER NOT NULL,
                        sharePersonalInfo INTEGER NOT NULL,
                        shareExpenses INTEGER NOT NULL,
                        invitationToken TEXT,
                        invitationEmail TEXT,
                        invitationExpiresAt TEXT,
                        linkedAt TEXT,
                        linkedActivatedAt TEXT,
                        unlinkedAt TEXT,
                        createdAt TEXT NOT NULL,
                        updatedAt TEXT NOT NULL,
                        syncStatus TEXT NOT NULL DEFAULT 'SYNCED',
                        localUpdatedAt INTEGER NOT NULL DEFAULT 0,
                        serverUpdatedAt INTEGER,
                        version INTEGER NOT NULL DEFAULT 1,
                        deletedAt INTEGER
                    )
                """)

                db.execSQL("""
                    INSERT INTO company_links_new (
                        id, userId, linkedProAccountId, companyName, department, status,
                        shareProfessionalTrips, sharePersonalTrips, sharePersonalInfo, shareExpenses,
                        invitationToken, invitationEmail, invitationExpiresAt, linkedAt,
                        linkedActivatedAt, unlinkedAt, createdAt, updatedAt,
                        syncStatus, localUpdatedAt, serverUpdatedAt, version, deletedAt
                    )
                    SELECT
                        id, userId, linkedProAccountId, companyName, department, status,
                        shareProfessionalTrips, sharePersonalTrips, sharePersonalInfo, shareExpenses,
                        invitationToken, invitationEmail, invitationExpiresAt, linkedAt,
                        linkedActivatedAt, unlinkedAt, createdAt, updatedAt,
                        'SYNCED', strftime('%s', 'now') * 1000, NULL, 1, NULL
                    FROM company_links
                """)

                db.execSQL("DROP TABLE company_links")
                db.execSQL("ALTER TABLE company_links_new RENAME TO company_links")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_company_links_userId ON company_links(userId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_company_links_linkedProAccountId ON company_links(linkedProAccountId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_company_links_syncStatus ON company_links(syncStatus)")

                // ==================== LICENSES ====================
                // Add deletedAt for consistency (other sync fields already exist)
                db.execSQL("ALTER TABLE licenses ADD COLUMN deletedAt INTEGER")
            }
        }

        /**
         * Migration from v21 to v22: Add LinkedUserEntity for offline-first Pro linked users
         * - Create linked_users table to cache linked users (employees linked to Pro accounts)
         * - Enables offline access to LinkedAccountsScreen
         */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ==================== TABLE: linked_users ====================
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS linked_users (
                        linkId TEXT PRIMARY KEY NOT NULL,
                        userId TEXT,
                        proAccountId TEXT NOT NULL,
                        userName TEXT,
                        userEmail TEXT NOT NULL,
                        userPhone TEXT,
                        department TEXT,
                        linkStatus TEXT NOT NULL,
                        shareProfessionalTrips INTEGER NOT NULL DEFAULT 1,
                        sharePersonalTrips INTEGER NOT NULL DEFAULT 0,
                        sharePersonalInfo INTEGER NOT NULL DEFAULT 1,
                        shareExpenses INTEGER NOT NULL DEFAULT 0,
                        invitedAt INTEGER,
                        linkActivatedAt INTEGER,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        syncStatus TEXT NOT NULL DEFAULT 'SYNCED',
                        version INTEGER NOT NULL DEFAULT 1
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_linked_users_proAccountId ON linked_users(proAccountId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_linked_users_userId ON linked_users(userId)")
            }
        }

        /**
         * Migration from v22 to v23: Add Pro Account enhancements
         * - Add billingAnchorDay to pro_accounts (unified renewal date)
         * - Add status to pro_accounts (trial, active, expired, suspended)
         * - Add trialEndsAt to pro_accounts
         */
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add new columns to pro_accounts
                db.execSQL("ALTER TABLE pro_accounts ADD COLUMN billingAnchorDay INTEGER")
                db.execSQL("ALTER TABLE pro_accounts ADD COLUMN status TEXT NOT NULL DEFAULT 'trial'")
                db.execSQL("ALTER TABLE pro_accounts ADD COLUMN trialEndsAt INTEGER")
            }
        }

        /**
         * Migration from v23 to v24: Supabase schema parity for licenses and pro_accounts
         * - Add stripeSubscriptionRef to licenses (FK to stripe_subscriptions.id)
         * - Add stripePaymentIntentId to licenses (for lifetime payments)
         * - Add pausedAt to licenses (DEPRECATED - column kept for backward compatibility but no longer used)
         * - Add stripeSubscriptionId to pro_accounts (main Stripe subscription ID)
         */
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add Stripe reference fields to licenses
                db.execSQL("ALTER TABLE licenses ADD COLUMN stripeSubscriptionRef TEXT")
                db.execSQL("ALTER TABLE licenses ADD COLUMN stripePaymentIntentId TEXT")
                db.execSQL("ALTER TABLE licenses ADD COLUMN pausedAt INTEGER")
                // Add Stripe subscription ID to pro_accounts
                db.execSQL("ALTER TABLE pro_accounts ADD COLUMN stripeSubscriptionId TEXT")
            }
        }

        /**
         * Migration from v24 to v25: Idempotency key for sync operations
         * - Add idempotencyKey column to pending_operations for server-side deduplication
         * - Enables safe retry of sync operations without duplicate effects
         * - Generate idempotency keys for existing operations using entityType:entityId:action:createdAt
         */
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add idempotencyKey column with default empty string (will be populated next)
                db.execSQL("ALTER TABLE pending_operations ADD COLUMN idempotencyKey TEXT NOT NULL DEFAULT ''")

                // Populate idempotencyKey for any existing operations
                // Format: "entityType:entityId:action:createdAt"
                db.execSQL("""
                    UPDATE pending_operations
                    SET idempotencyKey = entityType || ':' || entityId || ':' || action || ':' || createdAt
                    WHERE idempotencyKey = ''
                """)

                // Create unique index on idempotencyKey
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_pending_operations_idempotencyKey ON pending_operations(idempotencyKey)")
            }
        }

        /**
         * Migration from v25 to v26: Battery optimization indexes
         * - Add indexes on localUpdatedAt for efficient delta sync queries
         * - Add index on trips.vehicleId for annual mileage calculations
         * - These indexes reduce full table scans during sync operations
         */
        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add localUpdatedAt indexes for delta sync queries
                db.execSQL("CREATE INDEX IF NOT EXISTS index_trips_localUpdatedAt ON trips(localUpdatedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_trips_vehicleId ON trips(vehicleId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_vehicles_localUpdatedAt ON vehicles(localUpdatedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_licenses_localUpdatedAt ON licenses(localUpdatedAt)")
            }
        }

        /**
         * Migration from v26 to v27: Add cancelAtPeriodEnd to users
         * - Add cancelAtPeriodEnd column to track if subscription is pending cancellation
         * - Enables "Annuler la résiliation" feature in subscription management
         */
        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add cancelAtPeriodEnd column to users table
                // Default to 0 (false) for existing users
                db.execSQL("ALTER TABLE users ADD COLUMN cancelAtPeriodEnd INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Migration from v27 to v28: Add profile photo URL to users
         * - Add profilePhotoUrl column to store Settings avatar URL
         */
        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE users ADD COLUMN profilePhotoUrl TEXT")
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
                // Add migrations for smooth schema updates
                // IMPORTANT: All migrations must be present for production builds
                // Never use fallbackToDestructiveMigration() as it destroys user data
                .addMigrations(
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,  // Bridge migration (no-op)
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                    MIGRATION_19_20,
                    MIGRATION_20_21,
                    MIGRATION_21_22,
                    MIGRATION_22_23,
                    MIGRATION_23_24,
                    MIGRATION_24_25,
                    MIGRATION_25_26,
                    MIGRATION_26_27,
                    MIGRATION_27_28
                )
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
            db.consentDao().deleteAll()
            db.linkedUserDao().deleteAll()
        }
    }
}
