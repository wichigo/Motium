package com.application.motium.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.application.motium.data.local.entities.LicenseEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LicenseDao {
    @Query("SELECT * FROM licenses WHERE proAccountId = :proAccountId")
    fun getLicensesByProAccount(proAccountId: String): Flow<List<LicenseEntity>>

    @Query("SELECT * FROM licenses WHERE linkedAccountId = :userId AND status = 'active' LIMIT 1")
    fun getActiveLicenseForUser(userId: String): Flow<LicenseEntity?>

    @Query("SELECT * FROM licenses WHERE proAccountId = :proAccountId AND linkedAccountId IS NULL AND (status = 'available' OR status = 'active')")
    fun getAvailableLicenses(proAccountId: String): Flow<List<LicenseEntity>>

    @Query("SELECT * FROM licenses WHERE id = :id")
    fun getById(id: String): Flow<LicenseEntity?>

    @Query("SELECT EXISTS(SELECT 1 FROM licenses WHERE linkedAccountId = :userId AND status = 'active')")
    fun hasActiveLicense(userId: String): Flow<Boolean>

    @Query("SELECT * FROM licenses WHERE id = :id")
    suspend fun getByIdOnce(id: String): LicenseEntity?

    @Query("SELECT * FROM licenses WHERE syncStatus != 'SYNCED'")
    suspend fun getPendingSync(): List<LicenseEntity>

    @Query("SELECT * FROM licenses WHERE proAccountId = :proAccountId")
    suspend fun getLicensesByProAccountOnce(proAccountId: String): List<LicenseEntity>

    @Upsert
    suspend fun upsert(license: LicenseEntity)

    @Upsert
    suspend fun upsertAll(licenses: List<LicenseEntity>)

    @Query("UPDATE licenses SET syncStatus = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: String)

    @Query("UPDATE licenses SET linkedAccountId = :userId, linkedAt = :linkedAt, status = 'active', syncStatus = 'PENDING_UPLOAD', localUpdatedAt = :now, version = version + 1 WHERE id = :licenseId")
    suspend fun assignLicense(licenseId: String, userId: String, linkedAt: Long, now: Long)

    @Query("UPDATE licenses SET linkedAccountId = NULL, linkedAt = NULL, syncStatus = 'PENDING_UPLOAD', localUpdatedAt = :now, version = version + 1 WHERE id = :licenseId")
    suspend fun unassignLicense(licenseId: String, now: Long)

    @Query("UPDATE licenses SET unlinkRequestedAt = :requestedAt, unlinkEffectiveAt = :effectiveAt, syncStatus = 'PENDING_UPLOAD', localUpdatedAt = :now, version = version + 1 WHERE id = :licenseId")
    suspend fun setUnlinkRequest(licenseId: String, requestedAt: Long, effectiveAt: Long, now: Long)

    @Query("UPDATE licenses SET unlinkRequestedAt = NULL, unlinkEffectiveAt = NULL, syncStatus = 'PENDING_UPLOAD', localUpdatedAt = :now, version = version + 1 WHERE id = :licenseId")
    suspend fun clearUnlinkRequest(licenseId: String, now: Long)

    @Query("UPDATE licenses SET status = :status, syncStatus = 'PENDING_UPLOAD', localUpdatedAt = :now, version = version + 1 WHERE id = :licenseId")
    suspend fun updateStatus(licenseId: String, status: String, now: Long)

    @Query("DELETE FROM licenses WHERE proAccountId = :proAccountId")
    suspend fun deleteByProAccount(proAccountId: String)

    @Query("DELETE FROM licenses WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM licenses")
    suspend fun deleteAll()
}
