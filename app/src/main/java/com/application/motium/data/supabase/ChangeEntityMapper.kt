package com.application.motium.data.supabase

import com.application.motium.MotiumApplication
import com.application.motium.data.TripLocation
import com.application.motium.data.local.entities.*
import kotlinx.datetime.Instant
import kotlinx.serialization.json.*

/**
 * Maps JSONB data from get_changes RPC to Room entities.
 * Handles all entity types: TRIP, VEHICLE, EXPENSE, USER, etc.
 *
 * Each mapper safely handles missing/null fields and returns null on parse errors.
 */
object ChangeEntityMapper {

    private const val TAG = "ChangeEntityMapper"
    private val json = Json { ignoreUnknownKeys = true }

    // ==================== TRIP ====================

    fun mapToTripEntity(data: JsonObject, userId: String): TripEntity? {
        return try {
            val id = data.getString("id") ?: return null
            val startTimeStr = data.getString("start_time") ?: return null

            TripEntity(
                id = id,
                userId = userId,
                startTime = parseTimestamp(startTimeStr),
                endTime = data.getString("end_time")?.let { parseTimestamp(it) },
                locations = parseTraceGps(data["trace_gps"]),
                totalDistance = (data.getDouble("distance_km") ?: 0.0) * 1000, // km to m
                isValidated = data.getBoolean("is_validated") ?: false,
                vehicleId = data.getString("vehicle_id"),
                startAddress = data.getString("start_address"),
                endAddress = data.getString("end_address"),
                notes = data.getString("notes"),
                tripType = data.getString("type"),
                reimbursementAmount = data.getDouble("reimbursement_amount"),
                isWorkHomeTrip = data.getBoolean("is_work_home_trip") ?: false,
                createdAt = parseTimestamp(data.getString("created_at") ?: startTimeStr),
                updatedAt = parseTimestamp(data.getString("updated_at") ?: startTimeStr),
                matchedRouteCoordinates = data.getString("matched_route_coordinates"),
                syncStatus = SyncStatus.SYNCED.name,
                localUpdatedAt = System.currentTimeMillis(),
                serverUpdatedAt = parseTimestamp(data.getString("updated_at") ?: startTimeStr),
                version = data.getInt("version") ?: 1,
                deletedAt = data.getString("deleted_at")?.let { parseTimestamp(it) }
            )
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error mapping trip: ${e.message}", TAG, e)
            null
        }
    }

    private fun parseTraceGps(element: JsonElement?): List<TripLocation> {
        if (element == null || element is JsonNull) return emptyList()
        return try {
            val content = when (element) {
                is JsonPrimitive -> element.content
                is JsonArray -> element.toString()
                else -> element.toString()
            }
            if (content.isBlank() || content == "{}" || content == "null") return emptyList()

            json.decodeFromString<List<TripRemoteDataSource.GpsPoint>>(content).map { point ->
                TripLocation(
                    latitude = point.latitude,
                    longitude = point.longitude,
                    accuracy = point.accuracy ?: 10f,
                    timestamp = point.timestamp
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ==================== VEHICLE ====================

    fun mapToVehicleEntity(data: JsonObject, userId: String): VehicleEntity? {
        return try {
            val id = data.getString("id") ?: return null
            val updatedAt = data.getString("updated_at") ?: return null

            VehicleEntity(
                id = id,
                userId = userId,
                name = data.getString("name") ?: "Unknown",
                type = data.getString("type") ?: "CAR",
                licensePlate = data.getString("license_plate"),
                power = data.getString("power"),
                fuelType = data.getString("fuel_type"),
                mileageRate = data.getDouble("mileage_rate") ?: 0.0,
                isDefault = data.getBoolean("is_default") ?: false,
                totalMileagePerso = data.getDouble("total_mileage_perso") ?: 0.0,
                totalMileagePro = data.getDouble("total_mileage_pro") ?: 0.0,
                totalMileageWorkHome = data.getDouble("total_mileage_work_home") ?: 0.0,
                createdAt = data.getString("created_at") ?: updatedAt,
                updatedAt = updatedAt,
                syncStatus = SyncStatus.SYNCED.name,
                localUpdatedAt = System.currentTimeMillis(),
                serverUpdatedAt = parseTimestamp(updatedAt),
                version = data.getInt("version") ?: 1,
                deletedAt = data.getString("deleted_at")?.let { parseTimestamp(it) }
            )
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error mapping vehicle: ${e.message}", TAG, e)
            null
        }
    }

    // ==================== EXPENSE ====================

    fun mapToExpenseEntity(data: JsonObject, userId: String): ExpenseEntity? {
        return try {
            val id = data.getString("id") ?: return null
            val updatedAt = data.getString("updated_at") ?: return null

            ExpenseEntity(
                id = id,
                userId = userId,
                date = data.getString("date") ?: "",
                type = data.getString("type") ?: "OTHER",
                amount = data.getDouble("amount") ?: 0.0,
                amountHT = data.getDouble("amount_ht"),
                note = data.getString("note") ?: "",
                photoUri = data.getString("photo_uri"),
                createdAt = data.getString("created_at") ?: updatedAt,
                updatedAt = updatedAt,
                syncStatus = SyncStatus.SYNCED.name,
                localUpdatedAt = System.currentTimeMillis(),
                serverUpdatedAt = parseTimestamp(updatedAt),
                version = data.getInt("version") ?: 1,
                deletedAt = data.getString("deleted_at")?.let { parseTimestamp(it) }
            )
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error mapping expense: ${e.message}", TAG, e)
            null
        }
    }

    // ==================== USER ====================

    fun mapToUserEntity(data: JsonObject): UserEntity? {
        return try {
            val id = data.getString("id") ?: return null
            val updatedAt = data.getString("updated_at") ?: return null

            UserEntity(
                id = id,
                name = data.getString("name") ?: "",
                email = data.getString("email") ?: "",
                role = data.getString("role") ?: "INDIVIDUAL",
                subscriptionType = data.getString("subscription_type") ?: "FREE",
                subscriptionExpiresAt = data.getString("subscription_expires_at"),
                trialStartedAt = data.getString("trial_started_at"),
                trialEndsAt = data.getString("trial_ends_at"),
                stripeCustomerId = data.getString("stripe_customer_id"),
                stripeSubscriptionId = data.getString("stripe_subscription_id"),
                phoneNumber = data.getString("phone_number") ?: "",
                address = data.getString("address") ?: "",
                deviceFingerprintId = data.getString("device_fingerprint_id"),
                considerFullDistance = data.getBoolean("consider_full_distance") ?: false,
                favoriteColors = data["favorite_colors"]?.toString() ?: "[]",
                createdAt = data.getString("created_at") ?: updatedAt,
                updatedAt = updatedAt,
                lastSyncedAt = System.currentTimeMillis(),
                isLocallyConnected = true,
                syncStatus = SyncStatus.SYNCED.name,
                localUpdatedAt = System.currentTimeMillis(),
                serverUpdatedAt = parseTimestamp(updatedAt),
                version = 1
            )
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error mapping user: ${e.message}", TAG, e)
            null
        }
    }

    // ==================== LICENSE ====================

    fun mapToLicenseEntity(data: JsonObject): LicenseEntity? {
        return try {
            val id = data.getString("id") ?: return null
            val updatedAt = data.getString("updated_at") ?: return null

            LicenseEntity(
                id = id,
                proAccountId = data.getString("pro_account_id") ?: return null,
                linkedAccountId = data.getString("linked_account_id"),
                linkedAt = data.getString("linked_at")?.let { parseTimestamp(it) },
                isLifetime = data.getBoolean("is_lifetime") ?: false,
                priceMonthlyHt = data.getDouble("price_monthly_ht") ?: 5.0,
                vatRate = data.getDouble("vat_rate") ?: 0.20,
                status = data.getString("status") ?: "pending",
                startDate = data.getString("start_date")?.let { parseTimestamp(it) },
                endDate = data.getString("end_date")?.let { parseTimestamp(it) },
                unlinkRequestedAt = data.getString("unlink_requested_at")?.let { parseTimestamp(it) },
                unlinkEffectiveAt = data.getString("unlink_effective_at")?.let { parseTimestamp(it) },
                billingStartsAt = data.getString("billing_starts_at")?.let { parseTimestamp(it) },
                stripeSubscriptionId = data.getString("stripe_subscription_id"),
                stripeSubscriptionItemId = data.getString("stripe_subscription_item_id"),
                stripePriceId = data.getString("stripe_price_id"),
                createdAt = parseTimestamp(data.getString("created_at") ?: updatedAt),
                updatedAt = parseTimestamp(updatedAt),
                syncStatus = SyncStatus.SYNCED.name,
                localUpdatedAt = System.currentTimeMillis(),
                serverUpdatedAt = parseTimestamp(updatedAt),
                version = 1
            )
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error mapping license: ${e.message}", TAG, e)
            null
        }
    }

    // ==================== PRO_ACCOUNT ====================

    fun mapToProAccountEntity(data: JsonObject): ProAccountEntity? {
        return try {
            val id = data.getString("id") ?: return null
            val updatedAt = data.getString("updated_at") ?: return null

            ProAccountEntity(
                id = id,
                userId = data.getString("user_id") ?: return null,
                companyName = data.getString("company_name") ?: "",
                siret = data.getString("siret"),
                vatNumber = data.getString("vat_number"),
                legalForm = data.getString("legal_form"),
                billingAddress = data.getString("billing_address"),
                billingEmail = data.getString("billing_email"),
                billingDay = data.getInt("billing_day") ?: 5,
                departments = data["departments"]?.toString() ?: "[]",
                createdAt = parseTimestamp(data.getString("created_at") ?: updatedAt),
                updatedAt = parseTimestamp(updatedAt),
                syncStatus = SyncStatus.SYNCED.name,
                localUpdatedAt = System.currentTimeMillis(),
                serverUpdatedAt = parseTimestamp(updatedAt),
                version = 1
            )
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error mapping pro account: ${e.message}", TAG, e)
            null
        }
    }

    // ==================== COMPANY_LINK ====================

    fun mapToCompanyLinkEntity(data: JsonObject, userId: String): CompanyLinkEntity? {
        return try {
            val id = data.getString("id") ?: return null
            val updatedAt = data.getString("updated_at") ?: return null

            CompanyLinkEntity(
                id = id,
                userId = userId,
                linkedProAccountId = data.getString("pro_account_id") ?: return null,
                companyName = data.getString("company_name") ?: "",
                department = data.getString("department"),
                status = data.getString("status") ?: "pending",
                shareProfessionalTrips = data.getBoolean("share_professional_trips") ?: true,
                sharePersonalTrips = data.getBoolean("share_personal_trips") ?: false,
                sharePersonalInfo = data.getBoolean("share_personal_info") ?: true,
                shareExpenses = data.getBoolean("share_expenses") ?: false,
                invitationToken = data.getString("invitation_token"),
                invitationEmail = data.getString("invitation_email"),
                invitationExpiresAt = data.getString("invitation_expires_at"),
                linkedAt = data.getString("linked_at"),
                linkedActivatedAt = data.getString("linked_activated_at"),
                unlinkedAt = data.getString("unlinked_at"),
                createdAt = data.getString("created_at") ?: updatedAt,
                updatedAt = updatedAt,
                syncStatus = SyncStatus.SYNCED.name,
                localUpdatedAt = System.currentTimeMillis(),
                serverUpdatedAt = parseTimestamp(updatedAt),
                version = data.getInt("version") ?: 1,
                deletedAt = data.getString("deleted_at")?.let { parseTimestamp(it) }
            )
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error mapping company link: ${e.message}", TAG, e)
            null
        }
    }

    // ==================== CONSENT ====================

    fun mapToConsentEntity(data: JsonObject, userId: String): ConsentEntity? {
        return try {
            val id = data.getString("id") ?: return null

            ConsentEntity(
                id = id,
                userId = userId,
                consentType = data.getString("consent_type") ?: return null,
                granted = data.getBoolean("granted") ?: false,
                version = data.getInt("consent_version")?.toInt()
                    ?: data.getString("consent_version")?.toIntOrNull()
                    ?: 1,
                grantedAt = data.getString("granted_at")?.let { parseTimestamp(it) },
                revokedAt = data.getString("revoked_at")?.let { parseTimestamp(it) },
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                syncStatus = SyncStatus.SYNCED.name,
                localUpdatedAt = System.currentTimeMillis(),
                serverUpdatedAt = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error mapping consent: ${e.message}", TAG, e)
            null
        }
    }

    // ==================== WORK_SCHEDULE ====================

    fun mapToWorkScheduleEntity(data: JsonObject, userId: String): WorkScheduleEntity? {
        return try {
            val id = data.getString("id") ?: return null
            val updatedAt = data.getString("updated_at") ?: return null

            WorkScheduleEntity(
                id = id,
                userId = userId,
                dayOfWeek = data.getInt("day_of_week") ?: return null,
                startHour = data.getInt("start_hour") ?: 9,
                startMinute = data.getInt("start_minute") ?: 0,
                endHour = data.getInt("end_hour") ?: 18,
                endMinute = data.getInt("end_minute") ?: 0,
                isOvernight = data.getBoolean("is_overnight") ?: false,
                isActive = data.getBoolean("is_active") ?: true,
                createdAt = data.getString("created_at") ?: updatedAt,
                updatedAt = updatedAt,
                syncStatus = SyncStatus.SYNCED.name,
                localUpdatedAt = System.currentTimeMillis(),
                serverUpdatedAt = parseTimestamp(updatedAt),
                version = data.getInt("version") ?: 1,
                deletedAt = data.getString("deleted_at")?.let { parseTimestamp(it) }
            )
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error mapping work schedule: ${e.message}", TAG, e)
            null
        }
    }

    // ==================== AUTO_TRACKING_SETTINGS ====================

    fun mapToAutoTrackingSettingsEntity(data: JsonObject, userId: String): AutoTrackingSettingsEntity? {
        return try {
            val id = data.getString("id") ?: return null
            val updatedAt = data.getString("updated_at") ?: return null

            AutoTrackingSettingsEntity(
                id = id,
                userId = userId,
                trackingMode = data.getString("tracking_mode") ?: "MANUAL",
                minTripDistanceMeters = data.getInt("min_trip_distance_meters") ?: 500,
                minTripDurationSeconds = data.getInt("min_trip_duration_seconds") ?: 120,
                createdAt = data.getString("created_at") ?: updatedAt,
                updatedAt = updatedAt,
                syncStatus = SyncStatus.SYNCED.name,
                localUpdatedAt = System.currentTimeMillis(),
                serverUpdatedAt = parseTimestamp(updatedAt),
                version = data.getInt("version") ?: 1,
                deletedAt = data.getString("deleted_at")?.let { parseTimestamp(it) }
            )
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error mapping auto tracking settings: ${e.message}", TAG, e)
            null
        }
    }

    // ==================== UTILITIES ====================

    /**
     * Parse various PostgreSQL timestamp formats to epoch milliseconds.
     */
    private fun parseTimestamp(timestamp: String): Long {
        return try {
            val normalized = timestamp.trim()
                .replace(" ", "T")
                .substringBefore("+")  // Remove +00:00 timezone
                .let { if (it.endsWith("Z")) it else "${it}Z" }
            Instant.parse(normalized).toEpochMilliseconds()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    // Extension functions for safe JSON parsing
    private fun JsonObject.getString(key: String): String? =
        this[key]?.let { if (it is JsonNull) null else it.jsonPrimitive.contentOrNull }

    private fun JsonObject.getDouble(key: String): Double? =
        this[key]?.let { if (it is JsonNull) null else it.jsonPrimitive.doubleOrNull }

    private fun JsonObject.getInt(key: String): Int? =
        this[key]?.let { if (it is JsonNull) null else it.jsonPrimitive.intOrNull }

    private fun JsonObject.getBoolean(key: String): Boolean? =
        this[key]?.let { if (it is JsonNull) null else it.jsonPrimitive.booleanOrNull }
}
