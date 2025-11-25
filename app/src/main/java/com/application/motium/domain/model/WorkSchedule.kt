package com.application.motium.domain.model

import kotlinx.datetime.Instant

/**
 * Modèle de domaine pour un créneau horaire professionnel.
 * Un utilisateur peut avoir plusieurs créneaux par jour de la semaine.
 */
data class WorkSchedule(
    val id: String,
    val userId: String,
    val dayOfWeek: Int, // 1-7 (ISO 8601: 1=Lundi, 7=Dimanche)
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val isActive: Boolean = true,
    val createdAt: Instant,
    val updatedAt: Instant
)

/**
 * Modes d'auto-tracking disponibles
 */
enum class TrackingMode {
    ALWAYS,            // Toujours actif
    WORK_HOURS_ONLY,   // Actif uniquement pendant les horaires pro
    DISABLED           // Désactivé
}

/**
 * Paramètres d'auto-tracking pour un utilisateur
 */
data class AutoTrackingSettings(
    val id: String,
    val userId: String,
    val trackingMode: TrackingMode,
    val minTripDistanceMeters: Int = 100,
    val minTripDurationSeconds: Int = 60,
    val createdAt: Instant,
    val updatedAt: Instant
)

/**
 * TimeSlot UI model (pour la compatibilité avec CalendarScreen)
 */
data class TimeSlot(
    val id: String,
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int
)

/**
 * Conversion de WorkSchedule vers TimeSlot (pour l'UI)
 */
fun WorkSchedule.toTimeSlot(): TimeSlot {
    return TimeSlot(
        id = id,
        startHour = startHour,
        startMinute = startMinute,
        endHour = endHour,
        endMinute = endMinute
    )
}
