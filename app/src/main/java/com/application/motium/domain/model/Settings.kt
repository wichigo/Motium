package com.application.motium.domain.model

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalTime

data class Settings(
    val id: String,
    val userId: String,
    val autoTrackingEnabled: Boolean = false,
    val tripDetectionConfig: TripDetectionConfig,
    val gpsConfig: GpsConfig,
    val workingHours: WorkingHours,
    val defaultVehicleId: String?,
    val defaultTripType: TripType = TripType.PERSONAL,
    val notificationsEnabled: Boolean = true,
    val exportFormat: ExportFormat = ExportFormat.PDF,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class TripDetectionConfig(
    val minSpeedStartTripKmh: Double = 5.0,
    val minSpeedStopTripKmh: Double = 2.0,
    val minDurationStartTripMs: Long = 120000L, // 2 minutes
    val minDurationStopTripMs: Long = 240000L // 4 minutes
)

data class GpsConfig(
    val updateIntervalMs: Long = 5000L, // 5 seconds
    val accuracyThresholdM: Float = 20f
)

data class WorkingHours(
    val start: LocalTime,
    val end: LocalTime,
    val workingDays: Set<Int> // 1-7 (Monday-Sunday)
)

enum class ExportFormat(val displayName: String, val extension: String) {
    PDF("PDF", ".pdf"),
    CSV("CSV", ".csv")
}