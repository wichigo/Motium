package com.application.motium.domain.repository

import com.application.motium.domain.model.Settings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    fun getSettingsForUser(userId: String): Flow<Settings?>
    suspend fun getSettingsForUserSync(userId: String): Settings?
    suspend fun insertSettings(settings: Settings)
    suspend fun updateSettings(settings: Settings)
    suspend fun updateAutoTrackingEnabled(userId: String, enabled: Boolean)
    suspend fun updateDefaultVehicle(userId: String, vehicleId: String?)
    suspend fun deleteSettings(settings: Settings)
    suspend fun deleteSettingsForUser(userId: String)
}