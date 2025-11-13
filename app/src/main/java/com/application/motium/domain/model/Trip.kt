package com.application.motium.domain.model

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.text.SimpleDateFormat
import java.util.*

data class Trip(
    val id: String,
    val userId: String,
    val vehicleId: String?,
    val startTime: Instant,
    val endTime: Instant?,
    val startLatitude: Double,
    val startLongitude: Double,
    val endLatitude: Double?,
    val endLongitude: Double?,
    val startAddress: String?,
    val endAddress: String?,
    val distanceKm: Double,
    val durationMs: Long,
    val type: TripType,
    val isValidated: Boolean = false,
    val cost: Double,
    val tracePoints: List<LocationPoint>?,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class LocationPoint(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Instant,
    val accuracy: Float?
)

enum class TripType(val displayName: String) {
    PROFESSIONAL("Professionnel"),
    PERSONAL("Privé")
}

// Extension functions for Trip formatting
fun Trip.getFormattedStartTime(): String {
    val localDateTime = startTime.toLocalDateTime(TimeZone.currentSystemDefault())
    return String.format("%02d:%02d", localDateTime.hour, localDateTime.minute)
}

fun Trip.getFormattedDistance(): String {
    return String.format("%.1f km", distanceKm)
}

fun Trip.getFormattedDuration(): String {
    val minutes = durationMs / (1000 * 60)
    return "$minutes min"
}

fun Trip.getRouteDescription(): String {
    val start = startAddress ?: "Lieu de départ"
    val end = endAddress ?: "Lieu d'arrivée"
    return "$start → $end"
}