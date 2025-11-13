package com.application.motium.domain.repository

import com.application.motium.domain.model.Trip
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

interface TripRepository {
    fun getAllTripsForUser(userId: String): Flow<List<Trip>>
    fun getTripsByDateRange(userId: String, startDate: Instant, endDate: Instant): Flow<List<Trip>>
    suspend fun getTripById(tripId: String): Trip?
    suspend fun getActiveTrip(userId: String): Trip?
    suspend fun getTripCountForMonth(userId: String, monthStart: Instant, monthEnd: Instant): Int
    suspend fun getTotalDistanceByDateRange(userId: String, startDate: Instant, endDate: Instant): Double
    suspend fun getTotalCostByDateRange(userId: String, startDate: Instant, endDate: Instant): Double
    suspend fun insertTrip(trip: Trip)
    suspend fun updateTrip(trip: Trip)
    suspend fun deleteTrip(trip: Trip)
    suspend fun deleteAllTripsForUser(userId: String)
}