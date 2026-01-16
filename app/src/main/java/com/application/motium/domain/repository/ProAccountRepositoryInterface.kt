package com.application.motium.domain.repository

import com.application.motium.domain.model.ProAccount
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for offline-first Pro account management.
 * Uses local Room database as source of truth with background sync to Supabase.
 */
interface ProAccountRepositoryInterface {
    /**
     * Get Pro account for a user (reactive Flow).
     */
    fun getProAccountForUser(userId: String): Flow<ProAccount?>

    /**
     * Get Pro account by ID (reactive Flow).
     */
    fun getProAccountById(id: String): Flow<ProAccount?>

    /**
     * Check if user has a Pro account.
     */
    fun hasProAccount(userId: String): Flow<Boolean>

    /**
     * Get Pro account for a user (once, not reactive).
     */
    suspend fun getProAccountForUserOnce(userId: String): ProAccount?

    /**
     * Save/update a Pro account (offline-first with sync).
     */
    suspend fun saveProAccount(proAccount: ProAccount)
}
