package com.application.motium.domain.repository

import android.location.Location
import kotlinx.coroutines.flow.Flow

interface LocationRepository {
    fun getLocationUpdates(): Flow<Location>
    suspend fun getCurrentLocation(): Location?
    fun startLocationTracking()
    fun stopLocationTracking()
    suspend fun geocodeAddress(address: String): Pair<Double, Double>?
    suspend fun reverseGeocode(latitude: Double, longitude: Double): String?
}