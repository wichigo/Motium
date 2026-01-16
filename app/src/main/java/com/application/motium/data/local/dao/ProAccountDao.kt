package com.application.motium.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.application.motium.data.local.entities.ProAccountEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProAccountDao {
    @Query("SELECT * FROM pro_accounts WHERE userId = :userId LIMIT 1")
    fun getByUserId(userId: String): Flow<ProAccountEntity?>

    @Query("SELECT * FROM pro_accounts WHERE id = :id")
    fun getById(id: String): Flow<ProAccountEntity?>

    @Query("SELECT EXISTS(SELECT 1 FROM pro_accounts WHERE userId = :userId)")
    fun hasProAccount(userId: String): Flow<Boolean>

    @Query("SELECT * FROM pro_accounts WHERE userId = :userId LIMIT 1")
    suspend fun getByUserIdOnce(userId: String): ProAccountEntity?

    @Query("SELECT * FROM pro_accounts WHERE id = :id")
    suspend fun getByIdOnce(id: String): ProAccountEntity?

    @Query("SELECT * FROM pro_accounts WHERE syncStatus != 'SYNCED'")
    suspend fun getPendingSync(): List<ProAccountEntity>

    @Upsert
    suspend fun upsert(proAccount: ProAccountEntity)

    @Query("UPDATE pro_accounts SET syncStatus = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: String)

    @Query("DELETE FROM pro_accounts WHERE userId = :userId")
    suspend fun deleteByUserId(userId: String)

    @Query("DELETE FROM pro_accounts")
    suspend fun deleteAll()
}
