package com.application.motium.utils

import java.util.Calendar

/**
 * Utilitaires pour la conversion des jours de la semaine entre Android et ISO 8601
 *
 * Android Calendar:
 * - SUNDAY = 1
 * - MONDAY = 2
 * - TUESDAY = 3
 * - WEDNESDAY = 4
 * - THURSDAY = 5
 * - FRIDAY = 6
 * - SATURDAY = 7
 *
 * PostgreSQL ISO 8601:
 * - MONDAY = 1
 * - TUESDAY = 2
 * - WEDNESDAY = 3
 * - THURSDAY = 4
 * - FRIDAY = 5
 * - SATURDAY = 6
 * - SUNDAY = 7
 */
object CalendarUtils {

    /**
     * Convertit un jour Android Calendar en jour ISO 8601
     *
     * @param androidDay Jour Android (Calendar.SUNDAY = 1, Calendar.MONDAY = 2, etc.)
     * @return Jour ISO 8601 (1 = Lundi, 7 = Dimanche)
     */
    fun androidDayToIsoDay(androidDay: Int): Int {
        return when (androidDay) {
            Calendar.SUNDAY -> 7
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            else -> 1 // Default to Monday
        }
    }

    /**
     * Convertit un jour ISO 8601 en jour Android Calendar
     *
     * @param isoDay Jour ISO 8601 (1 = Lundi, 7 = Dimanche)
     * @return Jour Android (Calendar.MONDAY, Calendar.TUESDAY, etc.)
     */
    fun isoDayToAndroidDay(isoDay: Int): Int {
        return when (isoDay) {
            1 -> Calendar.MONDAY
            2 -> Calendar.TUESDAY
            3 -> Calendar.WEDNESDAY
            4 -> Calendar.THURSDAY
            5 -> Calendar.FRIDAY
            6 -> Calendar.SATURDAY
            7 -> Calendar.SUNDAY
            else -> Calendar.MONDAY // Default to Monday
        }
    }

    /**
     * Obtient le jour ISO 8601 actuel
     *
     * @return Jour ISO 8601 (1-7)
     */
    fun getCurrentIsoDay(): Int {
        val calendar = Calendar.getInstance()
        return androidDayToIsoDay(calendar.get(Calendar.DAY_OF_WEEK))
    }

    /**
     * Formate une heure pour l'affichage (ex: 9:05, 14:30)
     */
    fun formatTime(hour: Int, minute: Int): String {
        return String.format("%02d:%02d", hour, minute)
    }

    /**
     * Obtient le nom du jour en français pour un jour ISO
     */
    fun getIsoDayName(isoDay: Int): String {
        return when (isoDay) {
            1 -> "Lundi"
            2 -> "Mardi"
            3 -> "Mercredi"
            4 -> "Jeudi"
            5 -> "Vendredi"
            6 -> "Samedi"
            7 -> "Dimanche"
            else -> "Inconnu"
        }
    }

    /**
     * Obtient le nom du jour en français pour un jour Android
     */
    fun getAndroidDayName(androidDay: Int): String {
        val isoDay = androidDayToIsoDay(androidDay)
        return getIsoDayName(isoDay)
    }
}
