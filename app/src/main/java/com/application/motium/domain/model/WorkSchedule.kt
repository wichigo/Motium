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
    val isOvernight: Boolean = false, // Indique si le créneau traverse minuit (ex: 22:00->02:00)
    val isActive: Boolean = true,
    val createdAt: Instant,
    val updatedAt: Instant
)

/**
 * Modes d'auto-tracking disponibles
 */
enum class TrackingMode {
    ALWAYS,            // Toujours actif - suivi permanent de tous les trajets
    WORK_HOURS_ONLY,   // Pro - Actif uniquement pendant les horaires professionnels
    DISABLED           // Jamais - Désactivé (contrôle manuel par l'utilisateur)
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
    val endMinute: Int,
    val isOvernight: Boolean = false
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
        endMinute = endMinute,
        isOvernight = isOvernight
    )
}
