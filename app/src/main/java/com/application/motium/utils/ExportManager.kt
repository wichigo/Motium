package com.application.motium.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.application.motium.MotiumApplication
import com.application.motium.data.Trip
import com.application.motium.domain.model.Trip as DomainTrip
import com.application.motium.data.ExpenseRepository
import com.application.motium.data.VehicleRepository
import com.application.motium.service.SupabaseStorageService
import com.application.motium.domain.model.Expense
import com.application.motium.domain.model.Vehicle
import com.application.motium.domain.model.VehicleType
import com.application.motium.domain.model.VehiclePower
import com.application.motium.domain.model.TripType
import com.itextpdf.kernel.colors.Color
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.borders.Border
import com.itextpdf.layout.borders.SolidBorder
import com.itextpdf.layout.element.Cell as PdfCell
import com.itextpdf.layout.element.Image
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.Table as PdfTable
import com.itextpdf.layout.properties.TextAlignment
import com.itextpdf.layout.properties.UnitValue
import kotlinx.coroutines.runBlocking
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.VerticalAlignment
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFColor
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Données combinées d'un trajet avec ses notes de frais
 */
data class TripWithExpenses(
    val trip: Trip,
    val expenses: List<Expense>
)

/**
 * Trajets catégorisés par type pour l'export
 */
data class CategorizedTrips(
    val professional: List<TripWithExpenses>,
    val personalWorkHome: List<TripWithExpenses>,
    val personalOther: List<TripWithExpenses>
)

/**
 * Statistiques d'une catégorie de trajets
 */
data class CategoryStats(
    val tripCount: Int,
    val totalKm: Double,
    val totalIndemnities: Double
)

/**
 * Données d'export Pro - structure complète pour export entreprise
 */
data class ProExportData(
    val companyName: String,
    val siret: String?,
    val vatNumber: String?,
    val legalForm: String?,
    val billingAddress: String?,
    val employees: List<EmployeeExportData>,
    val startDate: Long,
    val endDate: Long,
    val includeExpenses: Boolean,
    val includePhotos: Boolean = false
)

/**
 * Données d'un employé pour l'export Pro
 */
data class EmployeeExportData(
    val userId: String,
    val displayName: String,
    val email: String,
    val department: String? = null,
    val trips: List<DomainTrip>,
    val expenses: List<Expense>,
    val vehicle: Vehicle?
)

/**
 * Résumé par département pour l'export Pro
 */
data class DepartmentSummary(
    val departmentName: String,
    val employees: List<EmployeeSummary>
)

/**
 * Résumé d'un employé pour le résumé par département
 */
data class EmployeeSummary(
    val displayName: String,
    val email: String,
    val department: String?,
    val proTripCount: Int,
    val persoTripCount: Int,
    val proIndemnities: Double,
    val persoIndemnities: Double,
    val expensesTotal: Double
)

class ExportManager(private val context: Context) {

    companion object {
        private const val EXPORT_FOLDER = "Motium_Exports"
        private const val DEFAULT_MILEAGE_RATE = 0.50 // €/km - Taux par défaut si pas de véhicule

        // Couleurs Motium (palette cohérente avec l'app)
        private val MOTIUM_GREEN = DeviceRgb(16, 185, 129) // #10B981 - Vert Motium principal
        private val MOTIUM_GREEN_LIGHT = DeviceRgb(209, 250, 229) // #D1FAE5 - Vert clair pour backgrounds
        private val MOTIUM_PRIMARY = DeviceRgb(59, 130, 246) // #3B82F6 - Bleu principal
        private val MOTIUM_PRIMARY_LIGHT = DeviceRgb(219, 234, 254) // #DBEAFE - Bleu clair
        private val GRAY_LIGHT = DeviceRgb(249, 250, 251) // #F9FAFB - Gris très clair
        private val GRAY_MEDIUM = DeviceRgb(229, 231, 235) // #E5E7EB - Gris moyen pour bordures
        private val GRAY_DARK = DeviceRgb(107, 114, 128) // #6B7280 - Gris foncé pour texte secondaire
        private val TEXT_PRIMARY = DeviceRgb(17, 24, 39) // #111827 - Texte principal

        // Couleurs pour catégories Pro export
        private val PRO_TRIP_COLOR = DeviceRgb(59, 130, 246) // #3B82F6 - Bleu pour trajets pro
        private val PRO_TRIP_LIGHT = DeviceRgb(219, 234, 254) // #DBEAFE - Bleu clair
        private val PERSO_TRIP_COLOR = DeviceRgb(34, 197, 94) // #22C55E - Vert pour trajets perso
        private val PERSO_TRIP_LIGHT = DeviceRgb(220, 252, 231) // #DCFCE7 - Vert clair
        private val EXPENSE_COLOR = DeviceRgb(249, 115, 22) // #F97316 - Orange pour dépenses
        private val EXPENSE_LIGHT = DeviceRgb(255, 237, 213) // #FFEDD5 - Orange clair
        private val DEPT_COLOR = DeviceRgb(139, 92, 246) // #8B5CF6 - Violet pour départements
        private val DEPT_LIGHT = DeviceRgb(237, 233, 254) // #EDE9FE - Violet clair
    }

    private val expenseRepository = ExpenseRepository.getInstance(context)
    private val storageService = SupabaseStorageService.getInstance(context)
    private val vehicleRepository = VehicleRepository.getInstance(context)

    /**
     * Cache des véhicules pour l'export en cours
     */
    private var vehiclesCache: Map<String, Vehicle> = emptyMap()

    /**
     * Charge les véhicules pour tous les trajets à exporter
     */
    private suspend fun loadVehiclesForTrips(trips: List<Trip>, userId: String): Map<String, Vehicle> {
        val vehicleIds = trips.mapNotNull { it.vehicleId }.filter { it.isNotBlank() }.distinct()
        MotiumApplication.logger.i("Export: Loading vehicles for ${vehicleIds.size} unique vehicle IDs, userId=$userId", "ExportManager")
        MotiumApplication.logger.i("Export: Vehicle IDs in trips: $vehicleIds", "ExportManager")

        if (vehicleIds.isEmpty()) {
            MotiumApplication.logger.w("Export: No vehicle IDs found in trips", "ExportManager")
            return emptyMap()
        }

        val vehicles = vehicleRepository.getAllVehiclesForUser(userId)
        MotiumApplication.logger.i("Export: Loaded ${vehicles.size} vehicles from repository", "ExportManager")
        vehicles.forEach { v ->
            MotiumApplication.logger.i("Export: Vehicle loaded: id=${v.id}, name=${v.name}", "ExportManager")
        }

        val vehicleMap = vehicles.associateBy { it.id }

        // Check which vehicle IDs from trips are missing
        val missingIds = vehicleIds.filter { it !in vehicleMap }
        if (missingIds.isNotEmpty()) {
            MotiumApplication.logger.w("Export: Missing vehicles for IDs: $missingIds", "ExportManager")
        }

        return vehicleMap
    }

    /**
     * Récupère l'indemnité pour un trajet - utilise la valeur stockée si disponible
     * @param trip Le trajet
     * @param vehiclesMap Map des véhicules par ID (utilisé pour fallback)
     * @return Indemnité en euros
     */
    private fun calculateTripIndemnity(trip: Trip, vehiclesMap: Map<String, Vehicle>): Double {
        // PRIORITÉ: Utiliser le montant de remboursement pré-calculé si disponible
        trip.reimbursementAmount?.let { storedAmount ->
            if (storedAmount > 0) {
                return storedAmount
            }
        }

        // FALLBACK pour les anciens trajets sans montant pré-calculé
        val distanceKm = trip.totalDistance / 1000.0
        val vehicleId = trip.vehicleId

        // Si pas de véhicule associé, utiliser le taux par défaut
        if (vehicleId.isNullOrBlank()) {
            return distanceKm * DEFAULT_MILEAGE_RATE
        }

        val vehicle = vehiclesMap[vehicleId]
        if (vehicle == null) {
            return distanceKm * DEFAULT_MILEAGE_RATE
        }

        // Déterminer le type de trajet
        val tripType = when (trip.tripType) {
            "PROFESSIONAL" -> TripType.PROFESSIONAL
            "PERSONAL" -> TripType.PERSONAL
            else -> TripType.PROFESSIONAL // Par défaut pro pour les indemnités
        }

        // Utiliser le calcul progressif avec le barème fiscal
        return TripCalculator.calculateMileageCost(distanceKm, vehicle, tripType)
    }

    /**
     * Obtient le taux effectif moyen pour l'affichage dans le résumé
     */
    private fun getAverageRate(trips: List<Trip>, vehiclesMap: Map<String, Vehicle>): Double {
        val totalKm = trips.sumOf { it.totalDistance / 1000.0 }
        if (totalKm == 0.0) return DEFAULT_MILEAGE_RATE

        val totalIndemnities = trips.sumOf { calculateTripIndemnity(it, vehiclesMap) }
        return totalIndemnities / totalKm
    }

    /**
     * Crée les résumés par département pour l'export Pro
     * Groupe les employés par département et calcule les totaux pro/perso
     */
    private fun buildDepartmentSummaries(employees: List<EmployeeExportData>): List<DepartmentSummary> {
        // Créer les résumés d'employés avec calculs pro/perso
        val employeeSummaries = employees.map { emp ->
            val proTrips = emp.trips.filter { it.type == TripType.PROFESSIONAL }
            val persoTrips = emp.trips.filter { it.type == TripType.PERSONAL }

            EmployeeSummary(
                displayName = emp.displayName,
                email = emp.email,
                department = emp.department,
                proTripCount = proTrips.size,
                persoTripCount = persoTrips.size,
                proIndemnities = proTrips.sumOf { it.reimbursementAmount ?: (it.distanceKm * DEFAULT_MILEAGE_RATE) },
                persoIndemnities = persoTrips.sumOf { it.reimbursementAmount ?: (it.distanceKm * DEFAULT_MILEAGE_RATE) },
                expensesTotal = emp.expenses.sumOf { it.amount }
            )
        }

        // Grouper par département
        val grouped = employeeSummaries.groupBy { it.department ?: "Sans département" }

        // Trier les départements alphabétiquement, "Sans département" à la fin
        return grouped.entries
            .sortedWith(compareBy {
                if (it.key == "Sans département") "zzz" else it.key.lowercase()
            })
            .map { (deptName, emps) ->
                DepartmentSummary(
                    departmentName = deptName,
                    employees = emps.sortedBy { it.displayName.lowercase() }
                )
            }
    }

    /**
     * Charge les expenses pour une liste de trips par date (optimisé)
     */
    private suspend fun loadExpensesForTrips(trips: List<Trip>): List<TripWithExpenses> {
        if (trips.isEmpty()) {
            return emptyList()
        }

        // Obtenir les dates min/max des trips
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val startDate = dateFormat.format(Date(trips.minOf { it.startTime }))
        val endDate = dateFormat.format(Date(trips.maxOf { it.endTime ?: it.startTime }))

        // Charger toutes les expenses dans la plage de dates
        val allExpenses = expenseRepository.getExpensesBetweenDates(startDate, endDate)

        // Grouper par date
        val expensesByDate = allExpenses.groupBy { it.date }

        // Mapper les trips avec leurs expenses (par date du trip)
        return trips.map { trip ->
            val tripDate = dateFormat.format(Date(trip.startTime))
            val expenses = expensesByDate[tripDate] ?: emptyList()
            TripWithExpenses(trip, expenses)
        }
    }

    /**
     * Catégorise les trajets par type pour l'export structuré
     */
    private fun categorizeTrips(tripsWithExpenses: List<TripWithExpenses>): CategorizedTrips {
        val professional = tripsWithExpenses.filter {
            it.trip.tripType == "PROFESSIONAL"
        }
        val personalWorkHome = tripsWithExpenses.filter {
            it.trip.tripType == "PERSONAL" && it.trip.isWorkHomeTrip
        }
        val personalOther = tripsWithExpenses.filter {
            it.trip.tripType == "PERSONAL" && !it.trip.isWorkHomeTrip
        }
        return CategorizedTrips(professional, personalWorkHome, personalOther)
    }

    /**
     * Calcule les statistiques pour une catégorie de trajets
     */
    private fun calculateCategoryStats(
        tripsWithExpenses: List<TripWithExpenses>,
        vehiclesMap: Map<String, Vehicle>
    ): CategoryStats {
        val trips = tripsWithExpenses.map { it.trip }
        val tripCount = trips.size
        val totalKm = trips.sumOf { it.totalDistance / 1000.0 }
        val totalIndemnities = trips.sumOf { calculateTripIndemnity(it, vehiclesMap) }
        return CategoryStats(tripCount, totalKm, totalIndemnities)
    }

    /**
     * Export trips to CSV format with accounting style
     */
    fun exportToCSV(
        trips: List<Trip>,
        startDate: Long,
        endDate: Long,
        expenseMode: String = "trips_only",
        includePhotos: Boolean = false,
        onSuccess: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            MotiumApplication.logger.i("Export CSV: Starting with ${trips.size} trips", "ExportManager")

            // Load expenses and vehicles
            val tripsWithExpenses: List<TripWithExpenses>
            val vehiclesMap: Map<String, Vehicle>

            runBlocking {
                tripsWithExpenses = loadExpensesForTrips(trips)
                val firstTrip = trips.firstOrNull()
                MotiumApplication.logger.i("Export CSV: First trip userId='${firstTrip?.userId}', vehicleId='${firstTrip?.vehicleId}'", "ExportManager")
                val userId = firstTrip?.userId ?: ""
                vehiclesMap = if (userId.isNotBlank()) {
                    loadVehiclesForTrips(trips, userId)
                } else {
                    MotiumApplication.logger.w("Export CSV: Skipping vehicle load - userId is blank!", "ExportManager")
                    emptyMap()
                }
                MotiumApplication.logger.i("Export CSV: Loaded ${vehiclesMap.size} vehicles", "ExportManager")
            }

            val exportDir = File(context.getExternalFilesDir(null), EXPORT_FOLDER)
            if (!exportDir.exists()) {
                exportDir.mkdirs()
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "Motium_Facture_${timestamp}.csv"
            val csvFile = File(exportDir, fileName)

            val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            val csvContent = buildString {
                // En-tête de la facture
                appendLine("MOTIUM - NOTE DE FRAIS PROFESSIONNELS")
                appendLine("Période du ${dateFormat.format(Date(startDate))} au ${dateFormat.format(Date(endDate))}")
                appendLine()

                // Résumé selon le mode
                when (expenseMode) {
                    "trips_only" -> {
                        val totalKm = trips.sumOf { it.totalDistance / 1000.0 }
                        val totalIndemnities = trips.sumOf { calculateTripIndemnity(it, vehiclesMap) }
                        val avgRate = getAverageRate(trips, vehiclesMap)

                        appendLine("RÉSUMÉ")
                        appendLine("Nombre de trajets,${trips.size}")
                        appendLine("Distance totale (km),${String.format("%.2f", totalKm)}")
                        appendLine("Indemnités kilométriques (barème progressif),${String.format("%.2f", totalIndemnities)}€")
                        appendLine("Taux moyen,${String.format("%.3f", avgRate)}€/km")
                        appendLine("TOTAL,${String.format("%.2f", totalIndemnities)}€")
                        appendLine()
                    }
                    "trips_with_expenses" -> {
                        val totalKm = trips.sumOf { it.totalDistance / 1000.0 }
                        val totalIndemnities = trips.sumOf { calculateTripIndemnity(it, vehiclesMap) }
                        val totalExpenses = tripsWithExpenses.flatMap { it.expenses }.sumOf { it.amount }
                        val grandTotal = totalIndemnities + totalExpenses
                        val avgRate = getAverageRate(trips, vehiclesMap)

                        appendLine("RÉSUMÉ")
                        appendLine("Nombre de trajets,${trips.size}")
                        appendLine("Distance totale (km),${String.format("%.2f", totalKm)}")
                        appendLine("Indemnités kilométriques (barème progressif),${String.format("%.2f", totalIndemnities)}€")
                        appendLine("Taux moyen,${String.format("%.3f", avgRate)}€/km")
                        appendLine("Frais annexes,${String.format("%.2f", totalExpenses)}€")
                        appendLine("TOTAL GÉNÉRAL,${String.format("%.2f", grandTotal)}€")
                        appendLine()
                    }
                    "expenses_only" -> {
                        val totalExpenses = tripsWithExpenses.flatMap { it.expenses }.sumOf { it.amount }

                        appendLine("RÉSUMÉ")
                        appendLine("Nombre de notes de frais,${tripsWithExpenses.flatMap { it.expenses }.size}")
                        appendLine("Frais totaux,${String.format("%.2f", totalExpenses)}€")
                        appendLine("TOTAL,${String.format("%.2f", totalExpenses)}€")
                        appendLine()
                    }
                }

                // Détail des trajets par catégorie
                if (expenseMode == "trips_only" || expenseMode == "trips_with_expenses") {
                    val categorized = categorizeTrips(tripsWithExpenses)
                    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

                    // Helper: get vehicle display name
                    fun getVehicleLabel(vehicleId: String?): String {
                        if (vehicleId.isNullOrBlank()) return "Sans véhicule"
                        val vehicle = vehiclesMap[vehicleId] ?: return "Véhicule inconnu"
                        val powerLabel = vehicle.power?.cv ?: ""
                        return "${vehicle.name} ($powerLabel ${vehicle.type.displayName})"
                    }

                    // Fonction helper pour écrire une section de trajets CSV avec sous-sections véhicules
                    fun writeCsvTripSection(
                        sectionTitle: String,
                        categoryTrips: List<TripWithExpenses>,
                        includeExpenses: Boolean
                    ) {
                        if (categoryTrips.isEmpty()) return

                        val stats = calculateCategoryStats(categoryTrips, vehiclesMap)
                        appendLine(sectionTitle)
                        appendLine("${stats.tripCount} trajets - ${String.format("%.2f", stats.totalKm)} km - ${String.format("%.2f", stats.totalIndemnities)}€")
                        appendLine()

                        if (includeExpenses) {
                            appendLine("Date,Heure,Départ,Arrivée,Distance (km),Indemnités (€),Notes de frais (€)")
                        } else {
                            appendLine("Date,Heure,Départ,Arrivée,Distance (km),Indemnités (€)")
                        }

                        // Group trips by vehicle
                        val tripsByVehicle = categoryTrips.groupBy { it.trip.vehicleId ?: "" }
                        tripsByVehicle.forEach { (vehicleId, vehicleTrips) ->
                            val vehicleLabel = getVehicleLabel(vehicleId.ifBlank { null })
                            val vehicleStats = calculateCategoryStats(vehicleTrips, vehiclesMap)
                            appendLine("--- $vehicleLabel (${vehicleStats.tripCount} trajets - ${String.format("%.1f", vehicleStats.totalKm)} km) ---")

                            vehicleTrips.sortedBy { it.trip.startTime }.forEach { tripWithExpenses ->
                                val trip = tripWithExpenses.trip
                                val tripDate = dateFormat.format(Date(trip.startTime))
                                val tripTime = timeFormat.format(Date(trip.startTime))
                                val distanceKm = trip.totalDistance / 1000.0
                                val indemnity = calculateTripIndemnity(trip, vehiclesMap)
                                val startAddr = trip.startAddress?.replace(",", " ") ?: "Non géocodé"
                                val endAddr = trip.endAddress?.replace(",", " ") ?: "Non géocodé"

                                if (includeExpenses) {
                                    val expensesTotal = tripWithExpenses.expenses.sumOf { it.amount }
                                    appendLine("$tripDate,$tripTime,\"$startAddr\",\"$endAddr\",${String.format("%.2f", distanceKm)},${String.format("%.2f", indemnity)},${String.format("%.2f", expensesTotal)}")
                                } else {
                                    appendLine("$tripDate,$tripTime,\"$startAddr\",\"$endAddr\",${String.format("%.2f", distanceKm)},${String.format("%.2f", indemnity)}")
                                }
                            }
                        }

                        // Sous-total de la section
                        if (includeExpenses) {
                            val totalExpenses = categoryTrips.flatMap { it.expenses }.sumOf { it.amount }
                            appendLine("SOUS-TOTAL $sectionTitle,,,,${String.format("%.2f", stats.totalKm)},${String.format("%.2f", stats.totalIndemnities)},${String.format("%.2f", totalExpenses)}")
                        } else {
                            appendLine("SOUS-TOTAL $sectionTitle,,,,${String.format("%.2f", stats.totalKm)},${String.format("%.2f", stats.totalIndemnities)}")
                        }
                        appendLine()
                    }

                    val includeExpensesColumn = expenseMode == "trips_with_expenses"

                    // Section Professionnels
                    writeCsvTripSection("TRAJETS PROFESSIONNELS", categorized.professional, includeExpensesColumn)

                    // Section Maison-Travail
                    writeCsvTripSection("TRAJETS PERSONNELS - MAISON-TRAVAIL", categorized.personalWorkHome, includeExpensesColumn)

                    // Section Autres Personnels
                    writeCsvTripSection("TRAJETS PERSONNELS - AUTRES", categorized.personalOther, includeExpensesColumn)
                }

                // Détail des notes de frais
                if (expenseMode != "trips_only" && tripsWithExpenses.any { it.expenses.isNotEmpty() }) {
                    appendLine("DÉTAIL DES NOTES DE FRAIS")
                    appendLine("Date,Type,Montant TTC (€),Montant HT (€),Note")

                    tripsWithExpenses.sortedBy { it.trip.startTime }.forEach { tripWithExpenses ->
                        tripWithExpenses.expenses.forEach { expense ->
                            val expenseDate = dateFormat.format(Date(tripWithExpenses.trip.startTime))
                            val note = expense.note.replace(",", " ")
                            val amountHT = expense.amountHT ?: 0.0
                            appendLine("$expenseDate,${expense.getExpenseTypeLabel()},${String.format("%.2f", expense.amount)},${String.format("%.2f", amountHT)},\"$note\"")
                        }
                    }
                }
            }

            csvFile.writeText(csvContent, Charsets.UTF_8)

            MotiumApplication.logger.i("CSV export successful: ${csvFile.absolutePath}", "ExportManager")
            onSuccess(csvFile)

        } catch (e: Exception) {
            val error = "Failed to export CSV: ${e.message}"
            MotiumApplication.logger.e(error, "ExportManager", e)
            onError(error)
        }
    }

    /**
     * Export trips to PDF format with professional invoice style
     */
    fun exportToPDF(
        trips: List<Trip>,
        startDate: Long,
        endDate: Long,
        expenseMode: String = "trips_only",
        includePhotos: Boolean = false,
        onSuccess: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            MotiumApplication.logger.i("Export PDF: Starting with ${trips.size} trips", "ExportManager")

            // Load expenses and vehicles
            val tripsWithExpenses: List<TripWithExpenses>
            val vehiclesMap: Map<String, Vehicle>

            runBlocking {
                tripsWithExpenses = loadExpensesForTrips(trips)
                val firstTrip = trips.firstOrNull()
                MotiumApplication.logger.i("Export PDF: First trip userId='${firstTrip?.userId}', vehicleId='${firstTrip?.vehicleId}'", "ExportManager")
                val userId = firstTrip?.userId ?: ""
                vehiclesMap = if (userId.isNotBlank()) {
                    loadVehiclesForTrips(trips, userId)
                } else {
                    MotiumApplication.logger.w("Export PDF: Skipping vehicle load - userId is blank!", "ExportManager")
                    emptyMap()
                }
                MotiumApplication.logger.i("Export PDF: Loaded ${vehiclesMap.size} vehicles", "ExportManager")
            }

            // Store in cache for use in helper methods
            vehiclesCache = vehiclesMap

            val exportDir = File(context.getExternalFilesDir(null), EXPORT_FOLDER)
            if (!exportDir.exists()) {
                exportDir.mkdirs()
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "Motium_Facture_${timestamp}.pdf"
            val pdfFile = File(exportDir, fileName)

            val pdfWriter = PdfWriter(pdfFile)
            val pdfDoc = PdfDocument(pdfWriter)
            val document = Document(pdfDoc)

            // En-tête avec style Motium
            addPdfHeader(document, startDate, endDate)

            // Résumé
            addPdfSummary(document, trips, tripsWithExpenses, expenseMode)

            // Détail des trajets par catégorie (si trips_only ou trips_with_expenses)
            if (expenseMode == "trips_only" || expenseMode == "trips_with_expenses") {
                val categorized = categorizeTrips(tripsWithExpenses)

                // Section Professionnels
                if (categorized.professional.isNotEmpty()) {
                    addPdfCategorySection(
                        document = document,
                        title = "TRAJETS PROFESSIONNELS",
                        icon = "💼",
                        color = MOTIUM_PRIMARY,
                        trips = categorized.professional,
                        expenseMode = expenseMode
                    )
                }

                // Section Maison-Travail
                if (categorized.personalWorkHome.isNotEmpty()) {
                    addPdfCategorySection(
                        document = document,
                        title = "TRAJETS PERSONNELS - MAISON-TRAVAIL",
                        icon = "🏠",
                        color = MOTIUM_GREEN,
                        trips = categorized.personalWorkHome,
                        expenseMode = expenseMode
                    )
                }

                // Section Autres Personnels
                if (categorized.personalOther.isNotEmpty()) {
                    addPdfCategorySection(
                        document = document,
                        title = "TRAJETS PERSONNELS - AUTRES",
                        icon = "🚗",
                        color = GRAY_DARK,
                        trips = categorized.personalOther,
                        expenseMode = expenseMode
                    )
                }
            }

            // Détail des notes de frais (si trips_with_expenses ou expenses_only)
            if (expenseMode != "trips_only" && tripsWithExpenses.any { it.expenses.isNotEmpty() }) {
                addPdfExpensesTable(document, tripsWithExpenses, includePhotos)
            }

            // Pied de page
            addPdfFooter(document)

            document.close()

            MotiumApplication.logger.i("PDF export successful: ${pdfFile.absolutePath}", "ExportManager")
            onSuccess(pdfFile)

        } catch (e: Exception) {
            val error = "Failed to export PDF: ${e.message}"
            MotiumApplication.logger.e(error, "ExportManager", e)
            onError(error)
        }
    }

    private fun addPdfHeader(document: Document, startDate: Long, endDate: Long) {
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

        // En-tête avec fond coloré et style professionnel
        val headerTable = PdfTable(UnitValue.createPercentArray(floatArrayOf(100f)))
            .setWidth(UnitValue.createPercentValue(100f))
            .setMarginBottom(25f)

        // Cellule principale de l'en-tête
        val headerCell = PdfCell()
            .setBackgroundColor(MOTIUM_GREEN_LIGHT)
            .setBorder(SolidBorder(MOTIUM_GREEN, 2f))
            .setPadding(20f)

        // Logo et titre
        val title = Paragraph("MOTIUM")
            .setFontSize(32f)
            .setBold()
            .setFontColor(MOTIUM_GREEN)
            .setTextAlignment(TextAlignment.CENTER)
            .setMarginBottom(5f)
        headerCell.add(title)

        val subtitle = Paragraph("Note de Frais Professionnels")
            .setFontSize(14f)
            .setFontColor(TEXT_PRIMARY)
            .setTextAlignment(TextAlignment.CENTER)
            .setMarginBottom(15f)
        headerCell.add(subtitle)

        // Ligne de séparation stylisée
        val divider = PdfTable(UnitValue.createPercentArray(floatArrayOf(35f, 30f, 35f)))
            .setWidth(UnitValue.createPercentValue(60f))
            .setMarginBottom(15f)
            .setHorizontalAlignment(com.itextpdf.layout.properties.HorizontalAlignment.CENTER)

        divider.addCell(PdfCell().setBorder(null).add(Paragraph("")))
        divider.addCell(PdfCell()
            .setBackgroundColor(MOTIUM_GREEN)
            .setHeight(3f)
            .setBorder(null))
        divider.addCell(PdfCell().setBorder(null).add(Paragraph("")))

        headerCell.add(divider)

        // Période avec icône visuelle
        val period = Paragraph("📅  Période: ${dateFormat.format(Date(startDate))} au ${dateFormat.format(Date(endDate))}")
            .setFontSize(11f)
            .setFontColor(GRAY_DARK)
            .setTextAlignment(TextAlignment.CENTER)
        headerCell.add(period)

        // Date de génération
        val generatedAt = Paragraph("Généré le ${SimpleDateFormat("dd/MM/yyyy à HH:mm", Locale.getDefault()).format(Date())}")
            .setFontSize(9f)
            .setFontColor(GRAY_DARK)
            .setTextAlignment(TextAlignment.CENTER)
            .setMarginTop(5f)
        headerCell.add(generatedAt)

        headerTable.addCell(headerCell)
        document.add(headerTable)
    }

    private fun addPdfSummary(document: Document, trips: List<Trip>, tripsWithExpenses: List<TripWithExpenses>, expenseMode: String) {
        // Titre avec icône
        val summaryTitle = Paragraph("📊  RÉSUMÉ")
            .setFontSize(14f)
            .setBold()
            .setFontColor(MOTIUM_PRIMARY)
            .setMarginBottom(12f)
        document.add(summaryTitle)

        val summaryTable = PdfTable(UnitValue.createPercentArray(floatArrayOf(65f, 35f)))
            .setWidth(UnitValue.createPercentValue(100f))
            .setMarginBottom(25f)
            .setBorder(SolidBorder(GRAY_MEDIUM, 1f))

        when (expenseMode) {
            "trips_only" -> {
                val totalKm = trips.sumOf { it.totalDistance / 1000.0 }

                // Calcul des indemnités par type
                val proTrips = trips.filter { it.tripType == "PROFESSIONAL" }
                val persoTrips = trips.filter { it.tripType == "PERSONAL" }
                val proIndemnities = proTrips.sumOf { calculateTripIndemnity(it, vehiclesCache) }
                val persoIndemnities = persoTrips.sumOf { calculateTripIndemnity(it, vehiclesCache) }
                val totalIndemnities = proIndemnities + persoIndemnities

                addSummaryRow(summaryTable, "Nombre de trajets", trips.size.toString())
                addSummaryRow(summaryTable, "Distance totale", "${String.format("%.2f", totalKm)} km")
                addSummaryRow(summaryTable, "Indemnités pro", "${String.format("%.2f", proIndemnities)} €")
                addSummaryRow(summaryTable, "Indemnités perso", "${String.format("%.2f", persoIndemnities)} €")

                // Total en vert
                summaryTable.addCell(
                    PdfCell().add(Paragraph("TOTAL").setBold())
                        .setBackgroundColor(MOTIUM_GREEN)
                        .setFontColor(com.itextpdf.kernel.colors.ColorConstants.WHITE)
                        .setPadding(8f)
                        .setBorder(null)
                )
                summaryTable.addCell(
                    PdfCell().add(Paragraph("${String.format("%.2f", totalIndemnities)} €").setBold())
                        .setBackgroundColor(MOTIUM_GREEN)
                        .setFontColor(com.itextpdf.kernel.colors.ColorConstants.WHITE)
                        .setTextAlignment(TextAlignment.RIGHT)
                        .setPadding(8f)
                        .setBorder(null)
                )
            }
            "trips_with_expenses" -> {
                val totalKm = trips.sumOf { it.totalDistance / 1000.0 }

                // Calcul des indemnités par type
                val proTrips = trips.filter { it.tripType == "PROFESSIONAL" }
                val persoTrips = trips.filter { it.tripType == "PERSONAL" }
                val proIndemnities = proTrips.sumOf { calculateTripIndemnity(it, vehiclesCache) }
                val persoIndemnities = persoTrips.sumOf { calculateTripIndemnity(it, vehiclesCache) }
                val totalIndemnities = proIndemnities + persoIndemnities

                val totalExpenses = tripsWithExpenses.flatMap { it.expenses }.sumOf { it.amount }
                val grandTotal = totalIndemnities + totalExpenses

                addSummaryRow(summaryTable, "Nombre de trajets", trips.size.toString())
                addSummaryRow(summaryTable, "Distance totale", "${String.format("%.2f", totalKm)} km")
                addSummaryRow(summaryTable, "Indemnités pro", "${String.format("%.2f", proIndemnities)} €")
                addSummaryRow(summaryTable, "Indemnités perso", "${String.format("%.2f", persoIndemnities)} €")
                addSummaryRow(summaryTable, "Frais annexes", "${String.format("%.2f", totalExpenses)} €")

                // Total en vert
                summaryTable.addCell(
                    PdfCell().add(Paragraph("TOTAL GÉNÉRAL").setBold())
                        .setBackgroundColor(MOTIUM_GREEN)
                        .setFontColor(com.itextpdf.kernel.colors.ColorConstants.WHITE)
                        .setPadding(8f)
                        .setBorder(null)
                )
                summaryTable.addCell(
                    PdfCell().add(Paragraph("${String.format("%.2f", grandTotal)} €").setBold())
                        .setBackgroundColor(MOTIUM_GREEN)
                        .setFontColor(com.itextpdf.kernel.colors.ColorConstants.WHITE)
                        .setTextAlignment(TextAlignment.RIGHT)
                        .setPadding(8f)
                        .setBorder(null)
                )
            }
            "expenses_only" -> {
                val totalExpenses = tripsWithExpenses.flatMap { it.expenses }.sumOf { it.amount }

                addSummaryRow(summaryTable, "Nombre de notes de frais", tripsWithExpenses.flatMap { it.expenses }.size.toString())
                addSummaryRow(summaryTable, "Frais totaux", "${String.format("%.2f", totalExpenses)} €")

                // Total en vert
                summaryTable.addCell(
                    PdfCell().add(Paragraph("TOTAL").setBold())
                        .setBackgroundColor(MOTIUM_GREEN)
                        .setFontColor(com.itextpdf.kernel.colors.ColorConstants.WHITE)
                        .setPadding(8f)
                        .setBorder(null)
                )
                summaryTable.addCell(
                    PdfCell().add(Paragraph("${String.format("%.2f", totalExpenses)} €").setBold())
                        .setBackgroundColor(MOTIUM_GREEN)
                        .setFontColor(com.itextpdf.kernel.colors.ColorConstants.WHITE)
                        .setTextAlignment(TextAlignment.RIGHT)
                        .setPadding(8f)
                        .setBorder(null)
                )
            }
        }

        document.add(summaryTable)
    }

    private fun addSummaryRow(table: PdfTable, label: String, value: String) {
        table.addCell(
            PdfCell().add(Paragraph(label).setFontColor(TEXT_PRIMARY))
                .setBackgroundColor(GRAY_LIGHT)
                .setPadding(10f)
                .setBorderBottom(SolidBorder(GRAY_MEDIUM, 0.5f))
                .setBorderTop(null)
                .setBorderLeft(null)
                .setBorderRight(null)
        )
        table.addCell(
            PdfCell().add(Paragraph(value).setFontColor(TEXT_PRIMARY).setBold())
                .setTextAlignment(TextAlignment.RIGHT)
                .setBackgroundColor(GRAY_LIGHT)
                .setPadding(10f)
                .setBorderBottom(SolidBorder(GRAY_MEDIUM, 0.5f))
                .setBorderTop(null)
                .setBorderLeft(null)
                .setBorderRight(null)
        )
    }

    private fun addPdfTripsTable(document: Document, tripsWithExpenses: List<TripWithExpenses>, expenseMode: String) {
        val tripsTitle = Paragraph("🚗  DÉTAIL DES TRAJETS")
            .setFontSize(14f)
            .setBold()
            .setFontColor(MOTIUM_PRIMARY)
            .setMarginTop(20f)
            .setMarginBottom(12f)
        document.add(tripsTitle)

        // Adapter les colonnes selon le mode
        val tripsTable = if (expenseMode == "trips_only") {
            PdfTable(UnitValue.createPercentArray(floatArrayOf(15f, 10f, 28f, 28f, 10f, 9f)))
                .setWidth(UnitValue.createPercentValue(100f))
                .setFontSize(9f)
        } else {
            PdfTable(UnitValue.createPercentArray(floatArrayOf(12f, 8f, 25f, 25f, 10f, 10f, 10f)))
                .setWidth(UnitValue.createPercentValue(100f))
                .setFontSize(9f)
        }

        // Headers
        addTableHeader(tripsTable, "Date")
        addTableHeader(tripsTable, "Heure")
        addTableHeader(tripsTable, "Départ")
        addTableHeader(tripsTable, "Arrivée")
        addTableHeader(tripsTable, "Km")
        addTableHeader(tripsTable, "Indemnités")
        if (expenseMode == "trips_with_expenses") {
            addTableHeader(tripsTable, "Frais")
        }

        val dateFormat = SimpleDateFormat("dd/MM/yy", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        tripsWithExpenses.sortedBy { it.trip.startTime }.forEach { tripWithExpenses ->
            val trip = tripWithExpenses.trip
            val distanceKm = trip.totalDistance / 1000.0
            val indemnity = calculateTripIndemnity(trip, vehiclesCache)

            addTableCell(tripsTable, dateFormat.format(Date(trip.startTime)))
            addTableCell(tripsTable, timeFormat.format(Date(trip.startTime)))
            addTableCell(tripsTable, trip.startAddress?.take(35) ?: "Non géocodé")
            addTableCell(tripsTable, trip.endAddress?.take(35) ?: "Non géocodé")
            addTableCell(tripsTable, String.format("%.1f", distanceKm))
            addTableCell(tripsTable, String.format("%.2f€", indemnity))

            if (expenseMode == "trips_with_expenses") {
                val expensesTotal = tripWithExpenses.expenses.sumOf { it.amount }
                addTableCell(tripsTable, String.format("%.2f€", expensesTotal))
            }
        }

        document.add(tripsTable)
    }

    /**
     * Ajoute une section catégorisée de trajets au PDF avec titre coloré et sous-total
     */
    private fun addPdfCategorySection(
        document: Document,
        title: String,
        icon: String,
        color: Color,
        trips: List<TripWithExpenses>,
        expenseMode: String
    ) {
        if (trips.isEmpty()) return

        val stats = calculateCategoryStats(trips, vehiclesCache)
        val avgRate = if (stats.totalKm > 0) stats.totalIndemnities / stats.totalKm else 0.0

        // Titre de la section avec statistiques
        val sectionTitle = Paragraph("$icon  $title")
            .setFontSize(13f)
            .setBold()
            .setFontColor(color)
            .setMarginTop(20f)
            .setMarginBottom(5f)
        document.add(sectionTitle)

        // Sous-titre avec statistiques et taux
        val statsText = Paragraph("${stats.tripCount} trajets — ${String.format("%.1f", stats.totalKm)} km — ${String.format("%.2f", stats.totalIndemnities)} € — Taux: ${String.format("%.3f", avgRate)} €/km")
            .setFontSize(10f)
            .setFontColor(GRAY_DARK)
            .setMarginBottom(10f)
        document.add(statsText)

        val dateFormat = SimpleDateFormat("dd/MM/yy", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        // Helper: get vehicle display name
        fun getVehicleLabel(vehicleId: String?): String {
            if (vehicleId.isNullOrBlank()) return "Sans véhicule"
            val vehicle = vehiclesCache[vehicleId] ?: return "Véhicule inconnu"
            val powerLabel = vehicle.power?.cv ?: ""
            return "${vehicle.name} ($powerLabel ${vehicle.type.displayName})"
        }

        // Group trips by vehicle
        val tripsByVehicle = trips.groupBy { it.trip.vehicleId ?: "" }

        tripsByVehicle.forEach { (vehicleId, vehicleTrips) ->
            val vehicleLabel = getVehicleLabel(vehicleId.ifBlank { null })
            val vehicleStats = calculateCategoryStats(vehicleTrips, vehiclesCache)

            // Vehicle sub-header
            val vehicleTitle = Paragraph("🚗 $vehicleLabel (${vehicleStats.tripCount} trajets — ${String.format("%.1f", vehicleStats.totalKm)} km)")
                .setFontSize(10f)
                .setItalic()
                .setFontColor(GRAY_DARK)
                .setMarginTop(8f)
                .setMarginBottom(5f)
            document.add(vehicleTitle)

            // Tableau des trajets pour ce véhicule
            val tripsTable = if (expenseMode == "trips_only") {
                PdfTable(UnitValue.createPercentArray(floatArrayOf(15f, 10f, 28f, 28f, 10f, 9f)))
                    .setWidth(UnitValue.createPercentValue(100f))
                    .setFontSize(9f)
            } else {
                PdfTable(UnitValue.createPercentArray(floatArrayOf(12f, 8f, 25f, 25f, 10f, 10f, 10f)))
                    .setWidth(UnitValue.createPercentValue(100f))
                    .setFontSize(9f)
            }

            // Headers avec couleur de la catégorie
            addTableHeaderWithColor(tripsTable, "Date", color)
            addTableHeaderWithColor(tripsTable, "Heure", color)
            addTableHeaderWithColor(tripsTable, "Départ", color)
            addTableHeaderWithColor(tripsTable, "Arrivée", color)
            addTableHeaderWithColor(tripsTable, "Km", color)
            addTableHeaderWithColor(tripsTable, "Indemn.", color)
            if (expenseMode == "trips_with_expenses") {
                addTableHeaderWithColor(tripsTable, "Frais", color)
            }

            vehicleTrips.sortedBy { it.trip.startTime }.forEach { tripWithExpenses ->
                val trip = tripWithExpenses.trip
                val distanceKm = trip.totalDistance / 1000.0
                val indemnity = calculateTripIndemnity(trip, vehiclesCache)

                addTableCell(tripsTable, dateFormat.format(Date(trip.startTime)))
                addTableCell(tripsTable, timeFormat.format(Date(trip.startTime)))
                addTableCell(tripsTable, trip.startAddress?.take(35) ?: "Non géocodé")
                addTableCell(tripsTable, trip.endAddress?.take(35) ?: "Non géocodé")
                addTableCell(tripsTable, String.format("%.1f", distanceKm))
                addTableCell(tripsTable, String.format("%.2f€", indemnity))

                if (expenseMode == "trips_with_expenses") {
                    val expensesTotal = tripWithExpenses.expenses.sumOf { it.amount }
                    addTableCell(tripsTable, String.format("%.2f€", expensesTotal))
                }
            }

            document.add(tripsTable)
        }

        // Ligne de sous-total de la catégorie (après tous les véhicules)
        val subtotalTable = if (expenseMode == "trips_only") {
            PdfTable(UnitValue.createPercentArray(floatArrayOf(15f, 10f, 28f, 28f, 10f, 9f)))
                .setWidth(UnitValue.createPercentValue(100f))
                .setFontSize(9f)
        } else {
            PdfTable(UnitValue.createPercentArray(floatArrayOf(12f, 8f, 25f, 25f, 10f, 10f, 10f)))
                .setWidth(UnitValue.createPercentValue(100f))
                .setFontSize(9f)
        }

        val subtotalCells = if (expenseMode == "trips_only") 6 else 7
        for (i in 0 until subtotalCells) {
            val cellContent = when (i) {
                0 -> "SOUS-TOTAL $title"
                subtotalCells - 2 -> String.format("%.1f km", stats.totalKm)
                subtotalCells - 1 -> String.format("%.2f €", stats.totalIndemnities)
                else -> ""
            }
            subtotalTable.addCell(
                PdfCell().add(Paragraph(cellContent).setBold().setFontSize(8f))
                    .setBackgroundColor(GRAY_LIGHT)
                    .setPadding(6f)
                    .setBorderBottom(SolidBorder(color, 1.5f))
                    .setBorderTop(SolidBorder(GRAY_MEDIUM, 0.5f))
                    .setBorderLeft(null)
                    .setBorderRight(null)
            )
        }

        document.add(subtotalTable)
    }

    /**
     * Ajoute un header de tableau avec une couleur personnalisée
     */
    private fun addTableHeaderWithColor(table: PdfTable, text: String, color: Color) {
        table.addHeaderCell(
            PdfCell().add(Paragraph(text).setBold().setFontSize(9f))
                .setBackgroundColor(color)
                .setFontColor(com.itextpdf.kernel.colors.ColorConstants.WHITE)
                .setPadding(8f)
                .setTextAlignment(TextAlignment.CENTER)
                .setBorder(SolidBorder(color, 1f))
        )
    }

    private fun addPdfExpensesTable(document: Document, tripsWithExpenses: List<TripWithExpenses>, includePhotos: Boolean) {
        val expensesTitle = Paragraph("🧾  DÉTAIL DES NOTES DE FRAIS")
            .setFontSize(14f)
            .setBold()
            .setFontColor(MOTIUM_PRIMARY)
            .setMarginTop(20f)
            .setMarginBottom(12f)
        document.add(expensesTitle)

        if (includePhotos) {
            // Format avec photos - Une note de frais par section avec style carte
            val dateFormat = SimpleDateFormat("dd/MM/yy", Locale.getDefault())

            tripsWithExpenses.sortedBy { it.trip.startTime }.forEach { tripWithExpenses ->
                tripWithExpenses.expenses.forEach { expense ->
                    // Conteneur principal avec bordure stylisée
                    val containerTable = PdfTable(UnitValue.createPercentArray(floatArrayOf(100f)))
                        .setWidth(UnitValue.createPercentValue(100f))
                        .setMarginBottom(15f)

                    val containerCell = PdfCell()
                        .setBorder(SolidBorder(GRAY_MEDIUM, 1f))
                        .setPadding(0f)

                    // En-tête de la carte avec type et montant
                    val headerTable = PdfTable(UnitValue.createPercentArray(floatArrayOf(60f, 40f)))
                        .setWidth(UnitValue.createPercentValue(100f))

                    headerTable.addCell(
                        PdfCell().add(Paragraph(expense.getExpenseTypeLabel()).setBold().setFontSize(11f).setFontColor(com.itextpdf.kernel.colors.ColorConstants.WHITE))
                            .setBackgroundColor(MOTIUM_PRIMARY)
                            .setPadding(10f)
                            .setBorder(null)
                    )
                    headerTable.addCell(
                        PdfCell().add(Paragraph(String.format("%.2f €", expense.amount)).setBold().setFontSize(11f).setFontColor(com.itextpdf.kernel.colors.ColorConstants.WHITE))
                            .setBackgroundColor(MOTIUM_PRIMARY)
                            .setPadding(10f)
                            .setTextAlignment(TextAlignment.RIGHT)
                            .setBorder(null)
                    )
                    containerCell.add(headerTable)

                    // Détails de la dépense
                    val detailsTable = PdfTable(UnitValue.createPercentArray(floatArrayOf(30f, 70f)))
                        .setWidth(UnitValue.createPercentValue(100f))

                    // Date
                    detailsTable.addCell(
                        PdfCell().add(Paragraph("📅 Date").setFontSize(9f).setFontColor(GRAY_DARK))
                            .setBackgroundColor(GRAY_LIGHT)
                            .setPadding(8f)
                            .setBorder(null)
                    )
                    detailsTable.addCell(
                        PdfCell().add(Paragraph(dateFormat.format(Date(tripWithExpenses.trip.startTime))).setFontSize(9f).setFontColor(TEXT_PRIMARY))
                            .setPadding(8f)
                            .setBorder(null)
                    )

                    // Note
                    detailsTable.addCell(
                        PdfCell().add(Paragraph("📝 Note").setFontSize(9f).setFontColor(GRAY_DARK))
                            .setBackgroundColor(GRAY_LIGHT)
                            .setPadding(8f)
                            .setBorder(null)
                    )
                    detailsTable.addCell(
                        PdfCell().add(Paragraph(expense.note.ifEmpty { "—" }).setFontSize(9f).setFontColor(TEXT_PRIMARY))
                            .setPadding(8f)
                            .setBorder(null)
                    )

                    containerCell.add(detailsTable)
                    containerTable.addCell(containerCell)
                    document.add(containerTable)

                    // Ajouter la photo si elle existe
                    if (expense.photoUri != null) {
                        try {
                            // L'URI peut être soit un chemin local soit une URL Supabase
                            val imageData = when {
                                expense.photoUri.startsWith("content://") || expense.photoUri.startsWith("file://") -> {
                                    // C'est un URI local, on peut le charger directement
                                    val uri = android.net.Uri.parse(expense.photoUri)
                                    val inputStream = context.contentResolver.openInputStream(uri)
                                    inputStream?.use { stream ->
                                        stream.readBytes()
                                    }
                                }
                                expense.photoUri.startsWith("http://") || expense.photoUri.startsWith("https://") -> {
                                    // C'est une URL Supabase, utiliser le service authentifié
                                    try {
                                        MotiumApplication.logger.i("Downloading photo from Supabase: ${expense.photoUri}", "ExportManager")
                                        val result = runBlocking {
                                            storageService.downloadReceiptPhoto(expense.photoUri)
                                        }
                                        if (result.isSuccess) {
                                            result.getOrNull()
                                        } else {
                                            MotiumApplication.logger.e("Error downloading photo: ${result.exceptionOrNull()?.message}", "ExportManager")
                                            null
                                        }
                                    } catch (e: Exception) {
                                        MotiumApplication.logger.e("Error downloading photo: ${e.message}", "ExportManager", e)
                                        null
                                    }
                                }
                                else -> null
                            }

                            if (imageData != null) {
                                // Conteneur pour la photo avec bordure stylisée
                                val photoContainer = PdfTable(UnitValue.createPercentArray(floatArrayOf(100f)))
                                    .setWidth(UnitValue.createPercentValue(50f))
                                    .setMarginTop(10f)
                                    .setMarginBottom(15f)
                                    .setHorizontalAlignment(com.itextpdf.layout.properties.HorizontalAlignment.CENTER)

                                val photoCell = PdfCell()
                                    .setBorder(SolidBorder(GRAY_MEDIUM, 1f))
                                    .setPadding(5f)
                                    .setBackgroundColor(GRAY_LIGHT)

                                val imageFile = com.itextpdf.io.image.ImageDataFactory.create(imageData)
                                val image = com.itextpdf.layout.element.Image(imageFile)
                                    .setWidth(UnitValue.createPercentValue(100f))
                                photoCell.add(image)

                                // Label sous la photo
                                photoCell.add(Paragraph("📷 Justificatif")
                                    .setFontSize(8f)
                                    .setFontColor(GRAY_DARK)
                                    .setTextAlignment(TextAlignment.CENTER)
                                    .setMarginTop(5f))

                                photoContainer.addCell(photoCell)
                                document.add(photoContainer)
                            } else {
                                // Message stylisé si pas de photo
                                val noPhotoContainer = PdfTable(UnitValue.createPercentArray(floatArrayOf(100f)))
                                    .setWidth(UnitValue.createPercentValue(50f))
                                    .setMarginTop(5f)
                                    .setMarginBottom(10f)
                                    .setHorizontalAlignment(com.itextpdf.layout.properties.HorizontalAlignment.CENTER)

                                noPhotoContainer.addCell(
                                    PdfCell().add(Paragraph("📷 Photo non disponible")
                                        .setFontSize(9f)
                                        .setFontColor(GRAY_DARK)
                                        .setTextAlignment(TextAlignment.CENTER))
                                        .setBackgroundColor(GRAY_LIGHT)
                                        .setPadding(10f)
                                        .setBorder(SolidBorder(GRAY_MEDIUM, 1f))
                                )
                                document.add(noPhotoContainer)
                            }
                        } catch (e: Exception) {
                            MotiumApplication.logger.e("Failed to add photo to PDF: ${e.message}", "ExportManager", e)
                            val errorContainer = PdfTable(UnitValue.createPercentArray(floatArrayOf(100f)))
                                .setWidth(UnitValue.createPercentValue(50f))
                                .setMarginTop(5f)
                                .setMarginBottom(10f)
                                .setHorizontalAlignment(com.itextpdf.layout.properties.HorizontalAlignment.CENTER)

                            errorContainer.addCell(
                                PdfCell().add(Paragraph("⚠️ Erreur de chargement")
                                    .setFontSize(9f)
                                    .setFontColor(GRAY_DARK)
                                    .setTextAlignment(TextAlignment.CENTER))
                                    .setBackgroundColor(GRAY_LIGHT)
                                    .setPadding(10f)
                                    .setBorder(SolidBorder(GRAY_MEDIUM, 1f))
                            )
                            document.add(errorContainer)
                        }
                    }
                }
            }
        } else {
            // Format tabulaire sans photos
            val expensesTable = PdfTable(UnitValue.createPercentArray(floatArrayOf(15f, 20f, 15f, 50f)))
                .setWidth(UnitValue.createPercentValue(100f))
                .setFontSize(9f)

            // Headers
            addTableHeader(expensesTable, "Date")
            addTableHeader(expensesTable, "Type")
            addTableHeader(expensesTable, "Montant")
            addTableHeader(expensesTable, "Note")

            val dateFormat = SimpleDateFormat("dd/MM/yy", Locale.getDefault())

            tripsWithExpenses.sortedBy { it.trip.startTime }.forEach { tripWithExpenses ->
                tripWithExpenses.expenses.forEach { expense ->
                    addTableCell(expensesTable, dateFormat.format(Date(tripWithExpenses.trip.startTime)))
                    addTableCell(expensesTable, expense.getExpenseTypeLabel())
                    addTableCell(expensesTable, String.format("%.2f€", expense.amount))
                    addTableCell(expensesTable, expense.note.ifEmpty { "-" })
                }
            }

            document.add(expensesTable)
        }
    }

    private fun addTableHeader(table: PdfTable, text: String) {
        table.addHeaderCell(
            PdfCell().add(Paragraph(text).setBold().setFontSize(9f))
                .setBackgroundColor(MOTIUM_PRIMARY)
                .setFontColor(com.itextpdf.kernel.colors.ColorConstants.WHITE)
                .setPadding(8f)
                .setTextAlignment(TextAlignment.CENTER)
                .setBorder(SolidBorder(MOTIUM_PRIMARY, 1f))
        )
    }

    private fun addTableCell(table: PdfTable, text: String) {
        table.addCell(
            PdfCell().add(Paragraph(text).setFontSize(8f).setFontColor(TEXT_PRIMARY))
                .setPadding(6f)
                .setBorderBottom(SolidBorder(GRAY_MEDIUM, 0.5f))
                .setBorderTop(null)
                .setBorderLeft(null)
                .setBorderRight(null)
        )
    }

    private fun addPdfFooter(document: Document) {
        document.add(Paragraph("\n\n"))

        // Ligne de séparation avant le footer
        val footerDivider = PdfTable(UnitValue.createPercentArray(floatArrayOf(100f)))
            .setWidth(UnitValue.createPercentValue(100f))
            .setMarginBottom(10f)
        footerDivider.addCell(
            PdfCell()
                .setBackgroundColor(MOTIUM_GREEN)
                .setHeight(2f)
                .setBorder(null)
        )
        document.add(footerDivider)

        // Texte du footer
        val footerText = Paragraph("Motium — Suivi de mobilité professionnelle")
            .setFontSize(9f)
            .setFontColor(MOTIUM_GREEN)
            .setBold()
            .setTextAlignment(TextAlignment.CENTER)
        document.add(footerText)

        val footerLegal = Paragraph("Document justificatif pour remboursement de frais kilométriques")
            .setFontSize(8f)
            .setFontColor(GRAY_DARK)
            .setTextAlignment(TextAlignment.CENTER)
            .setMarginTop(2f)
        document.add(footerLegal)
    }

    /**
     * Export trips to Excel format with Motium styling
     */
    fun exportToExcel(
        trips: List<Trip>,
        startDate: Long,
        endDate: Long,
        expenseMode: String = "trips_only",
        includePhotos: Boolean = false,
        onSuccess: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            MotiumApplication.logger.i("Export Excel: Starting with ${trips.size} trips", "ExportManager")

            // Load expenses and vehicles
            val tripsWithExpenses: List<TripWithExpenses>
            val vehiclesMap: Map<String, Vehicle>

            runBlocking {
                tripsWithExpenses = loadExpensesForTrips(trips)
                val firstTrip = trips.firstOrNull()
                MotiumApplication.logger.i("Export Excel: First trip userId='${firstTrip?.userId}', vehicleId='${firstTrip?.vehicleId}'", "ExportManager")
                val userId = firstTrip?.userId ?: ""
                vehiclesMap = if (userId.isNotBlank()) {
                    loadVehiclesForTrips(trips, userId)
                } else {
                    MotiumApplication.logger.w("Export Excel: Skipping vehicle load - userId is blank!", "ExportManager")
                    emptyMap()
                }
                MotiumApplication.logger.i("Export Excel: Loaded ${vehiclesMap.size} vehicles", "ExportManager")
            }

            // Store in cache for use in helper methods
            vehiclesCache = vehiclesMap

            val exportDir = File(context.getExternalFilesDir(null), EXPORT_FOLDER)
            if (!exportDir.exists()) {
                exportDir.mkdirs()
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "Motium_Facture_${timestamp}.xlsx"
            val xlsxFile = File(exportDir, fileName)

            val workbook = XSSFWorkbook()

            // Create styles
            val styles = createExcelStyles(workbook)

            // Sheet 1: Résumé
            createSummarySheet(workbook, trips, tripsWithExpenses, startDate, endDate, expenseMode, styles)

            // Sheet 2: Détail des trajets (si trips_only ou trips_with_expenses)
            if (expenseMode == "trips_only" || expenseMode == "trips_with_expenses") {
                createTripsSheet(workbook, tripsWithExpenses, expenseMode, styles)
            }

            // Sheet 3: Détail des notes de frais (si trips_with_expenses ou expenses_only)
            if (expenseMode != "trips_only" && tripsWithExpenses.any { it.expenses.isNotEmpty() }) {
                createExpensesSheet(workbook, tripsWithExpenses, styles, includePhotos)
            }

            // Write to file
            FileOutputStream(xlsxFile).use { outputStream ->
                workbook.write(outputStream)
            }
            workbook.close()

            MotiumApplication.logger.i("Excel export successful: ${xlsxFile.absolutePath}", "ExportManager")
            onSuccess(xlsxFile)

        } catch (e: Exception) {
            val error = "Failed to export Excel: ${e.message}"
            MotiumApplication.logger.e(error, "ExportManager", e)
            onError(error)
        }
    }

    private data class ExcelStyles(
        val titleStyle: XSSFCellStyle,
        val subtitleStyle: XSSFCellStyle,
        val headerStyle: XSSFCellStyle,
        val dataStyle: XSSFCellStyle,
        val currencyStyle: XSSFCellStyle,
        val totalLabelStyle: XSSFCellStyle,
        val totalValueStyle: XSSFCellStyle,
        val dateStyle: XSSFCellStyle
    )

    private fun createExcelStyles(workbook: XSSFWorkbook): ExcelStyles {
        // Couleurs Motium
        val motiumGreen = XSSFColor(byteArrayOf(16, 185.toByte(), 129.toByte()), null)
        val motiumBlue = XSSFColor(byteArrayOf(59, 130.toByte(), 246.toByte()), null)
        val grayLight = XSSFColor(byteArrayOf(243.toByte(), 244.toByte(), 246.toByte()), null)
        val white = XSSFColor(byteArrayOf(255.toByte(), 255.toByte(), 255.toByte()), null)

        // Font styles
        val titleFont = workbook.createFont().apply {
            fontHeightInPoints = 20
            bold = true
            setColor(motiumGreen)
        }

        val subtitleFont = workbook.createFont().apply {
            fontHeightInPoints = 12
            bold = false
        }

        val headerFont = workbook.createFont().apply {
            fontHeightInPoints = 11
            bold = true
            color = IndexedColors.WHITE.index
        }

        val dataFont = workbook.createFont().apply {
            fontHeightInPoints = 10
        }

        val totalFont = workbook.createFont().apply {
            fontHeightInPoints = 11
            bold = true
            color = IndexedColors.WHITE.index
        }

        // Cell styles
        val titleStyle = (workbook.createCellStyle() as XSSFCellStyle).apply {
            setFont(titleFont)
            alignment = HorizontalAlignment.CENTER
        }

        val subtitleStyle = (workbook.createCellStyle() as XSSFCellStyle).apply {
            setFont(subtitleFont)
            alignment = HorizontalAlignment.CENTER
        }

        val headerStyle = (workbook.createCellStyle() as XSSFCellStyle).apply {
            setFont(headerFont)
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
            setFillForegroundColor(motiumBlue)
            fillPattern = FillPatternType.SOLID_FOREGROUND
            borderBottom = BorderStyle.THIN
            borderTop = BorderStyle.THIN
            borderLeft = BorderStyle.THIN
            borderRight = BorderStyle.THIN
        }

        val dataStyle = (workbook.createCellStyle() as XSSFCellStyle).apply {
            setFont(dataFont)
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
            borderBottom = BorderStyle.THIN
            borderTop = BorderStyle.THIN
            borderLeft = BorderStyle.THIN
            borderRight = BorderStyle.THIN
        }

        val currencyStyle = (workbook.createCellStyle() as XSSFCellStyle).apply {
            setFont(dataFont)
            alignment = HorizontalAlignment.RIGHT
            verticalAlignment = VerticalAlignment.CENTER
            dataFormat = workbook.createDataFormat().getFormat("#,##0.00 €")
            borderBottom = BorderStyle.THIN
            borderTop = BorderStyle.THIN
            borderLeft = BorderStyle.THIN
            borderRight = BorderStyle.THIN
        }

        val totalLabelStyle = (workbook.createCellStyle() as XSSFCellStyle).apply {
            setFont(totalFont)
            alignment = HorizontalAlignment.LEFT
            verticalAlignment = VerticalAlignment.CENTER
            setFillForegroundColor(motiumGreen)
            fillPattern = FillPatternType.SOLID_FOREGROUND
            borderBottom = BorderStyle.THIN
            borderTop = BorderStyle.THIN
            borderLeft = BorderStyle.THIN
            borderRight = BorderStyle.THIN
        }

        val totalValueStyle = (workbook.createCellStyle() as XSSFCellStyle).apply {
            setFont(totalFont)
            alignment = HorizontalAlignment.RIGHT
            verticalAlignment = VerticalAlignment.CENTER
            setFillForegroundColor(motiumGreen)
            fillPattern = FillPatternType.SOLID_FOREGROUND
            dataFormat = workbook.createDataFormat().getFormat("#,##0.00 €")
            borderBottom = BorderStyle.THIN
            borderTop = BorderStyle.THIN
            borderLeft = BorderStyle.THIN
            borderRight = BorderStyle.THIN
        }

        val dateStyle = (workbook.createCellStyle() as XSSFCellStyle).apply {
            setFont(dataFont)
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
            dataFormat = workbook.createDataFormat().getFormat("dd/MM/yyyy")
            borderBottom = BorderStyle.THIN
            borderTop = BorderStyle.THIN
            borderLeft = BorderStyle.THIN
            borderRight = BorderStyle.THIN
        }

        return ExcelStyles(
            titleStyle = titleStyle,
            subtitleStyle = subtitleStyle,
            headerStyle = headerStyle,
            dataStyle = dataStyle,
            currencyStyle = currencyStyle,
            totalLabelStyle = totalLabelStyle,
            totalValueStyle = totalValueStyle,
            dateStyle = dateStyle
        )
    }

    private fun createSummarySheet(
        workbook: XSSFWorkbook,
        trips: List<Trip>,
        tripsWithExpenses: List<TripWithExpenses>,
        startDate: Long,
        endDate: Long,
        expenseMode: String,
        styles: ExcelStyles
    ) {
        val sheet = workbook.createSheet("Résumé")
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        var rowNum = 0

        // Title
        val titleRow = sheet.createRow(rowNum++)
        val titleCell = titleRow.createCell(0)
        titleCell.setCellValue("MOTIUM - Note de Frais Professionnels")
        titleCell.cellStyle = styles.titleStyle
        sheet.addMergedRegion(CellRangeAddress(0, 0, 0, 3))

        // Period
        rowNum++
        val periodRow = sheet.createRow(rowNum++)
        val periodCell = periodRow.createCell(0)
        periodCell.setCellValue("Période: ${dateFormat.format(Date(startDate))} - ${dateFormat.format(Date(endDate))}")
        periodCell.cellStyle = styles.subtitleStyle
        sheet.addMergedRegion(CellRangeAddress(rowNum - 1, rowNum - 1, 0, 3))

        rowNum += 2

        // Summary data based on mode
        when (expenseMode) {
            "trips_only" -> {
                val totalKm = trips.sumOf { it.totalDistance / 1000.0 }
                val totalIndemnities = trips.sumOf { calculateTripIndemnity(it, vehiclesCache) }
                val avgRate = getAverageRate(trips, vehiclesCache)

                addSummaryRow(sheet, rowNum++, "Nombre de trajets", trips.size.toString(), styles)
                addSummaryRow(sheet, rowNum++, "Distance totale", String.format("%.2f km", totalKm), styles)
                addSummaryRow(sheet, rowNum++, "Indemnités (barème progressif)", String.format("%.2f €", totalIndemnities), styles)
                addSummaryRow(sheet, rowNum++, "Taux moyen", String.format("%.3f €/km", avgRate), styles)
                rowNum++
                addTotalRow(sheet, rowNum++, "TOTAL", totalIndemnities, styles)
            }
            "trips_with_expenses" -> {
                val totalKm = trips.sumOf { it.totalDistance / 1000.0 }
                val totalIndemnities = trips.sumOf { calculateTripIndemnity(it, vehiclesCache) }
                val totalExpenses = tripsWithExpenses.flatMap { it.expenses }.sumOf { it.amount }
                val grandTotal = totalIndemnities + totalExpenses
                val avgRate = getAverageRate(trips, vehiclesCache)

                addSummaryRow(sheet, rowNum++, "Nombre de trajets", trips.size.toString(), styles)
                addSummaryRow(sheet, rowNum++, "Distance totale", String.format("%.2f km", totalKm), styles)
                addSummaryRow(sheet, rowNum++, "Indemnités (barème progressif)", String.format("%.2f €", totalIndemnities), styles)
                addSummaryRow(sheet, rowNum++, "Taux moyen", String.format("%.3f €/km", avgRate), styles)
                addSummaryRow(sheet, rowNum++, "Frais annexes", String.format("%.2f €", totalExpenses), styles)
                rowNum++
                addTotalRow(sheet, rowNum++, "TOTAL GÉNÉRAL", grandTotal, styles)
            }
            "expenses_only" -> {
                val totalExpenses = tripsWithExpenses.flatMap { it.expenses }.sumOf { it.amount }
                val expenseCount = tripsWithExpenses.flatMap { it.expenses }.size

                addSummaryRow(sheet, rowNum++, "Nombre de notes de frais", expenseCount.toString(), styles)
                addSummaryRow(sheet, rowNum++, "Frais totaux", String.format("%.2f €", totalExpenses), styles)
                rowNum++
                addTotalRow(sheet, rowNum++, "TOTAL", totalExpenses, styles)
            }
        }

        // Footer
        rowNum += 2
        val footerRow = sheet.createRow(rowNum)
        val footerCell = footerRow.createCell(0)
        footerCell.setCellValue("Document généré automatiquement par Motium le ${SimpleDateFormat("dd/MM/yyyy à HH:mm", Locale.getDefault()).format(Date())}")

        // Auto-size columns
        for (i in 0..3) {
            sheet.setColumnWidth(i, 6000)
        }
    }

    private fun addSummaryRow(sheet: Sheet, rowNum: Int, label: String, value: String, styles: ExcelStyles) {
        val row = sheet.createRow(rowNum)
        val labelCell = row.createCell(0)
        labelCell.setCellValue(label)
        labelCell.cellStyle = styles.dataStyle

        val valueCell = row.createCell(1)
        valueCell.setCellValue(value)
        valueCell.cellStyle = styles.dataStyle
    }

    private fun addTotalRow(sheet: Sheet, rowNum: Int, label: String, value: Double, styles: ExcelStyles) {
        val row = sheet.createRow(rowNum)
        val labelCell = row.createCell(0)
        labelCell.setCellValue(label)
        labelCell.cellStyle = styles.totalLabelStyle

        val valueCell = row.createCell(1)
        valueCell.setCellValue(value)
        valueCell.cellStyle = styles.totalValueStyle
    }

    private fun createTripsSheet(
        workbook: XSSFWorkbook,
        tripsWithExpenses: List<TripWithExpenses>,
        expenseMode: String,
        styles: ExcelStyles
    ) {
        val sheet = workbook.createSheet("Trajets")
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        var rowNum = 0

        val headers = if (expenseMode == "trips_only") {
            listOf("Date", "Heure", "Départ", "Arrivée", "Distance (km)", "Indemnités (€)")
        } else {
            listOf("Date", "Heure", "Départ", "Arrivée", "Distance (km)", "Indemnités (€)", "Frais (€)")
        }

        val categorized = categorizeTrips(tripsWithExpenses)

        // Helper: get vehicle display name
        fun getVehicleLabel(vehicleId: String?): String {
            if (vehicleId.isNullOrBlank()) return "Sans véhicule"
            val vehicle = vehiclesCache[vehicleId] ?: return "Véhicule inconnu"
            val powerLabel = vehicle.power?.cv ?: ""
            return "${vehicle.name} ($powerLabel ${vehicle.type.displayName})"
        }

        // Helper: write vehicle sub-section
        fun writeVehicleSubSection(vehicleId: String?, vehicleTrips: List<TripWithExpenses>): Int {
            if (vehicleTrips.isEmpty()) return rowNum

            val vehicleLabel = getVehicleLabel(vehicleId)
            val vehicleStats = calculateCategoryStats(vehicleTrips, vehiclesCache)

            // Vehicle sub-header
            val vehicleRow = sheet.createRow(rowNum++)
            vehicleRow.createCell(0).apply {
                setCellValue("  🚗 $vehicleLabel (${vehicleStats.tripCount} trajets - ${String.format("%.1f", vehicleStats.totalKm)} km)")
                cellStyle = styles.dataStyle
            }
            sheet.addMergedRegion(CellRangeAddress(rowNum - 1, rowNum - 1, 0, headers.size - 1))

            // Data rows for this vehicle
            vehicleTrips.sortedBy { it.trip.startTime }.forEach { tripWithExpenses ->
                val trip = tripWithExpenses.trip
                val row = sheet.createRow(rowNum++)
                val distanceKm = trip.totalDistance / 1000.0
                val indemnity = calculateTripIndemnity(trip, vehiclesCache)

                row.createCell(0).apply {
                    setCellValue(dateFormat.format(Date(trip.startTime)))
                    cellStyle = styles.dataStyle
                }
                row.createCell(1).apply {
                    setCellValue(timeFormat.format(Date(trip.startTime)))
                    cellStyle = styles.dataStyle
                }
                row.createCell(2).apply {
                    setCellValue(trip.startAddress?.take(40) ?: "Non géocodé")
                    cellStyle = styles.dataStyle
                }
                row.createCell(3).apply {
                    setCellValue(trip.endAddress?.take(40) ?: "Non géocodé")
                    cellStyle = styles.dataStyle
                }
                row.createCell(4).apply {
                    setCellValue(distanceKm)
                    cellStyle = styles.currencyStyle
                }
                row.createCell(5).apply {
                    setCellValue(indemnity)
                    cellStyle = styles.currencyStyle
                }

                if (expenseMode == "trips_with_expenses") {
                    val expensesTotal = tripWithExpenses.expenses.sumOf { it.amount }
                    row.createCell(6).apply {
                        setCellValue(expensesTotal)
                        cellStyle = styles.currencyStyle
                    }
                }
            }

            return rowNum
        }

        // Fonction helper pour écrire une section de trajets Excel avec sous-sections véhicules
        fun writeExcelCategorySection(
            sectionTitle: String,
            categoryTrips: List<TripWithExpenses>
        ): Int {
            if (categoryTrips.isEmpty()) return rowNum

            val stats = calculateCategoryStats(categoryTrips, vehiclesCache)

            // Titre de la section catégorie
            val titleRow = sheet.createRow(rowNum++)
            val titleCell = titleRow.createCell(0)
            titleCell.setCellValue("$sectionTitle (${stats.tripCount} trajets - ${String.format("%.1f", stats.totalKm)} km - ${String.format("%.2f", stats.totalIndemnities)} €)")
            titleCell.cellStyle = styles.subtitleStyle
            sheet.addMergedRegion(CellRangeAddress(rowNum - 1, rowNum - 1, 0, headers.size - 1))

            rowNum++ // Ligne vide

            // Headers
            val headerRow = sheet.createRow(rowNum++)
            headers.forEachIndexed { index, header ->
                val cell = headerRow.createCell(index)
                cell.setCellValue(header)
                cell.cellStyle = styles.headerStyle
            }

            // Group trips by vehicle and write sub-sections
            val tripsByVehicle = categoryTrips.groupBy { it.trip.vehicleId ?: "" }
            tripsByVehicle.forEach { (vehicleId, vehicleTrips) ->
                rowNum = writeVehicleSubSection(vehicleId.ifBlank { null }, vehicleTrips)
            }

            // Sous-total catégorie
            val subtotalRow = sheet.createRow(rowNum++)
            subtotalRow.createCell(0).apply {
                setCellValue("SOUS-TOTAL $sectionTitle")
                cellStyle = styles.totalLabelStyle
            }
            for (i in 1 until headers.size - 2) {
                subtotalRow.createCell(i).apply {
                    setCellValue("")
                    cellStyle = styles.totalLabelStyle
                }
            }
            subtotalRow.createCell(headers.size - 2).apply {
                setCellValue(stats.totalKm)
                cellStyle = styles.totalValueStyle
            }
            subtotalRow.createCell(headers.size - 1).apply {
                setCellValue(stats.totalIndemnities)
                cellStyle = styles.totalValueStyle
            }

            rowNum++ // Ligne vide après sous-total
            return rowNum
        }

        // Section Professionnels
        rowNum = writeExcelCategorySection("TRAJETS PROFESSIONNELS", categorized.professional)

        // Section Maison-Travail
        rowNum = writeExcelCategorySection("TRAJETS PERSONNELS - MAISON-TRAVAIL", categorized.personalWorkHome)

        // Section Autres Personnels
        rowNum = writeExcelCategorySection("TRAJETS PERSONNELS - AUTRES", categorized.personalOther)

        // Set fixed column widths (autoSizeColumn uses AWT which is not available on Android)
        // Width in characters * 256
        val columnWidths = listOf(12, 8, 30, 30, 12, 14, 12) // Date, Heure, Départ, Arrivée, Distance, Indemnités, Frais
        columnWidths.take(headers.size).forEachIndexed { index, width ->
            sheet.setColumnWidth(index, width * 256)
        }
    }

    private fun createExpensesSheet(
        workbook: XSSFWorkbook,
        tripsWithExpenses: List<TripWithExpenses>,
        styles: ExcelStyles,
        includePhotos: Boolean = false
    ) {
        val sheet = workbook.createSheet("Notes de Frais")
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        var rowNum = 0

        // Headers
        val headerRow = sheet.createRow(rowNum++)
        val headers = if (includePhotos) {
            listOf("Date", "Type", "Montant TTC (€)", "Montant HT (€)", "Note", "Justificatif")
        } else {
            listOf("Date", "Type", "Montant TTC (€)", "Montant HT (€)", "Note")
        }

        headers.forEachIndexed { index, header ->
            val cell = headerRow.createCell(index)
            cell.setCellValue(header)
            cell.cellStyle = styles.headerStyle
        }

        // Create drawing patriarch for images (if needed)
        val drawing = if (includePhotos) sheet.createDrawingPatriarch() else null
        val helper = workbook.creationHelper

        // Data rows
        tripsWithExpenses.sortedBy { it.trip.startTime }.forEach { tripWithExpenses ->
            tripWithExpenses.expenses.forEach { expense ->
                val row = sheet.createRow(rowNum)
                val currentRowNum = rowNum
                rowNum++

                row.createCell(0).apply {
                    setCellValue(dateFormat.format(Date(tripWithExpenses.trip.startTime)))
                    cellStyle = styles.dataStyle
                }
                row.createCell(1).apply {
                    setCellValue(expense.getExpenseTypeLabel())
                    cellStyle = styles.dataStyle
                }
                row.createCell(2).apply {
                    setCellValue(expense.amount)
                    cellStyle = styles.currencyStyle
                }
                row.createCell(3).apply {
                    setCellValue(expense.amountHT ?: 0.0)
                    cellStyle = styles.currencyStyle
                }
                row.createCell(4).apply {
                    setCellValue(expense.note.ifEmpty { "-" })
                    cellStyle = styles.dataStyle
                }

                // Photo column
                if (includePhotos) {
                    val photoCell = row.createCell(5)
                    photoCell.cellStyle = styles.dataStyle

                    if (expense.photoUri != null) {
                        try {
                            val imageData = loadExpensePhoto(expense.photoUri)

                            if (imageData != null) {
                                // Determine image type
                                val pictureType = when {
                                    expense.photoUri.lowercase().endsWith(".png") -> org.apache.poi.ss.usermodel.Workbook.PICTURE_TYPE_PNG
                                    else -> org.apache.poi.ss.usermodel.Workbook.PICTURE_TYPE_JPEG
                                }

                                // Add picture to workbook
                                val pictureIdx = workbook.addPicture(imageData, pictureType)

                                // Create anchor for image positioning
                                val anchor = helper.createClientAnchor().apply {
                                    setCol1(5)
                                    setCol2(6)
                                    setRow1(currentRowNum)
                                    setRow2(currentRowNum + 1)
                                }

                                // Insert picture
                                drawing?.createPicture(anchor, pictureIdx)

                                // Increase row height to accommodate image
                                row.heightInPoints = 60f
                            } else {
                                photoCell.setCellValue("Non disponible")
                            }
                        } catch (e: Exception) {
                            MotiumApplication.logger.e("Excel photo error: ${e.message}", "ExportManager", e)
                            photoCell.setCellValue("Erreur")
                        }
                    } else {
                        photoCell.setCellValue("-")
                    }
                }
            }
        }

        // Set fixed column widths (autoSizeColumn uses AWT which is not available on Android)
        val expenseColumnWidths = if (includePhotos) {
            listOf(12, 18, 14, 14, 30, 20) // Date, Type, Montant TTC, Montant HT, Note, Photo
        } else {
            listOf(12, 18, 14, 14, 40) // Date, Type, Montant TTC, Montant HT, Note
        }
        expenseColumnWidths.forEachIndexed { index, width ->
            sheet.setColumnWidth(index, width * 256)
        }
    }

    /**
     * Load expense photo from local URI or Supabase URL
     */
    private fun loadExpensePhoto(photoUri: String): ByteArray? {
        return try {
            when {
                photoUri.startsWith("content://") || photoUri.startsWith("file://") -> {
                    val uri = android.net.Uri.parse(photoUri)
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readBytes()
                    }
                }
                photoUri.startsWith("http://") || photoUri.startsWith("https://") -> {
                    runBlocking {
                        storageService.downloadReceiptPhoto(photoUri).getOrNull()
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Failed to load expense photo: ${e.message}", "ExportManager", e)
            null
        }
    }

    // ================================================================================
    // PRO EXPORT METHODS - Export with company legal information
    // ================================================================================

    /**
     * Export Pro data to CSV format with company header and employee grouping
     * Format légal français avec mentions obligatoires URSSAF
     */
    fun exportProToCSV(
        data: ProExportData,
        onSuccess: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val exportDir = File(context.getExternalFilesDir(null), EXPORT_FOLDER)
            if (!exportDir.exists()) {
                exportDir.mkdirs()
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "Motium_Pro_Facture_${timestamp}.csv"
            val csvFile = File(exportDir, fileName)

            val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

            val csvContent = buildString {
                // En-tête entreprise
                appendLine("ENTREPRISE")
                appendLine("Raison sociale;${data.companyName}")
                data.legalForm?.let { appendLine("Forme juridique;$it") }
                data.billingAddress?.let { appendLine("Adresse;${it.replace("\n", " ")}") }
                data.siret?.let { appendLine("SIRET;${formatSiret(it)}") }
                data.vatNumber?.let { appendLine("N° TVA;$it") }
                appendLine()

                // Période
                appendLine("PÉRIODE")
                appendLine("Du;${dateFormat.format(Date(data.startDate))}")
                appendLine("Au;${dateFormat.format(Date(data.endDate))}")
                appendLine()

                // ========== RÉSUMÉ PAR DÉPARTEMENT ==========
                val departmentSummaries = buildDepartmentSummaries(data.employees)
                appendLine("=" .repeat(60))
                appendLine("RÉSUMÉ PAR DÉPARTEMENT")
                appendLine("=" .repeat(60))
                appendLine("Département;Collaborateur;Indemnités Pro;Indemnités Perso;Frais")

                departmentSummaries.forEach { deptSummary ->
                    deptSummary.employees.forEach { emp ->
                        val fraisStr = if (data.includeExpenses) String.format("%.2f", emp.expensesTotal) else "-"
                        appendLine("${deptSummary.departmentName};${emp.displayName};${String.format("%.2f", emp.proIndemnities)};${String.format("%.2f", emp.persoIndemnities)};$fraisStr")
                    }
                    // Sous-total département
                    val deptProTotal = deptSummary.employees.sumOf { it.proIndemnities }
                    val deptPersoTotal = deptSummary.employees.sumOf { it.persoIndemnities }
                    val deptExpTotal = deptSummary.employees.sumOf { it.expensesTotal }
                    val fraisTotalStr = if (data.includeExpenses) String.format("%.2f", deptExpTotal) else "-"
                    appendLine("${deptSummary.departmentName} (SOUS-TOTAL);;${String.format("%.2f", deptProTotal)};${String.format("%.2f", deptPersoTotal)};$fraisTotalStr")
                }
                appendLine()

                // ========== TOTAL GÉNÉRAL ==========
                val allTrips = data.employees.flatMap { it.trips }
                val allExpenses = data.employees.flatMap { it.expenses }
                val proTrips = allTrips.filter { it.type == TripType.PROFESSIONAL }
                val persoTrips = allTrips.filter { it.type == TripType.PERSONAL }
                val totalProIndemnities = proTrips.sumOf { it.reimbursementAmount ?: (it.distanceKm * DEFAULT_MILEAGE_RATE) }
                val totalPersoIndemnities = persoTrips.sumOf { it.reimbursementAmount ?: (it.distanceKm * DEFAULT_MILEAGE_RATE) }
                val totalIndemnities = totalProIndemnities + totalPersoIndemnities
                val totalExpenses = allExpenses.sumOf { it.amount }

                appendLine("TOTAL GÉNÉRAL")
                appendLine("Indemnités Pro;${String.format("%.2f", totalProIndemnities)} €")
                appendLine("Indemnités Perso;${String.format("%.2f", totalPersoIndemnities)} €")
                if (data.includeExpenses && totalExpenses > 0) {
                    appendLine("Notes de frais;${String.format("%.2f", totalExpenses)} €")
                    appendLine("GRAND TOTAL;${String.format("%.2f", totalIndemnities + totalExpenses)} €")
                } else {
                    appendLine("TOTAL;${String.format("%.2f", totalIndemnities)} €")
                }
                appendLine()

                // ========== DÉTAIL PAR COLLABORATEUR ==========
                data.employees.forEachIndexed { index, employee ->
                    val deptStr = employee.department?.let { " ($it)" } ?: ""
                    appendLine("=" .repeat(60))
                    appendLine("COLLABORATEUR ${index + 1}: ${employee.displayName}$deptStr")
                    appendLine("=" .repeat(60))
                    appendLine("Email;${employee.email}")
                    employee.vehicle?.let { v ->
                        val powerStr = v.power?.cv ?: "N/A"
                        appendLine("Véhicule;${v.name} ($powerStr) - ${v.type.displayName}")
                    }
                    appendLine()

                    val proTripsEmp = employee.trips.filter { it.type == TripType.PROFESSIONAL }
                    val persoTripsEmp = employee.trips.filter { it.type == TripType.PERSONAL }

                    // TRAJETS PROFESSIONNELS
                    if (proTripsEmp.isNotEmpty()) {
                        val proTotalKm = proTripsEmp.sumOf { it.distanceKm }
                        val proTotalIndem = proTripsEmp.sumOf { it.reimbursementAmount ?: (it.distanceKm * DEFAULT_MILEAGE_RATE) }
                        appendLine("TRAJETS PROFESSIONNELS (${proTripsEmp.size} trajets - ${String.format("%.2f", proTotalIndem)} €)")
                        appendLine("Date;Heure;Départ;Arrivée;Distance (km);CV;Indemnités (€)")

                        proTripsEmp.sortedBy { it.startTime }.forEach { trip ->
                            val tripDate = dateFormat.format(Date(trip.startTime.toEpochMilliseconds()))
                            val tripTime = timeFormat.format(Date(trip.startTime.toEpochMilliseconds()))
                            val indemnity = trip.reimbursementAmount ?: (trip.distanceKm * DEFAULT_MILEAGE_RATE)
                            val startAddr = trip.startAddress?.replace(";", ",") ?: "Non géocodé"
                            val endAddr = trip.endAddress?.replace(";", ",") ?: "Non géocodé"
                            val vehicleCv = employee.vehicle?.power?.cv ?: "N/A"
                            appendLine("$tripDate;$tripTime;$startAddr;$endAddr;${String.format("%.2f", trip.distanceKm)};$vehicleCv;${String.format("%.2f", indemnity)}")
                        }
                        appendLine("SOUS-TOTAL PRO;${proTripsEmp.size} trajets;;${String.format("%.2f", proTotalKm)} km;;${String.format("%.2f", proTotalIndem)} €")
                        appendLine()
                    }

                    // TRAJETS PERSONNELS
                    if (persoTripsEmp.isNotEmpty()) {
                        val persoTotalKm = persoTripsEmp.sumOf { it.distanceKm }
                        val persoTotalIndem = persoTripsEmp.sumOf { it.reimbursementAmount ?: (it.distanceKm * DEFAULT_MILEAGE_RATE) }
                        appendLine("TRAJETS PERSONNELS (${persoTripsEmp.size} trajets - ${String.format("%.2f", persoTotalIndem)} €)")
                        appendLine("Date;Heure;Départ;Arrivée;Distance (km);CV;Indemnités (€)")

                        persoTripsEmp.sortedBy { it.startTime }.forEach { trip ->
                            val tripDate = dateFormat.format(Date(trip.startTime.toEpochMilliseconds()))
                            val tripTime = timeFormat.format(Date(trip.startTime.toEpochMilliseconds()))
                            val indemnity = trip.reimbursementAmount ?: (trip.distanceKm * DEFAULT_MILEAGE_RATE)
                            val startAddr = trip.startAddress?.replace(";", ",") ?: "Non géocodé"
                            val endAddr = trip.endAddress?.replace(";", ",") ?: "Non géocodé"
                            val vehicleCv = employee.vehicle?.power?.cv ?: "N/A"
                            appendLine("$tripDate;$tripTime;$startAddr;$endAddr;${String.format("%.2f", trip.distanceKm)};$vehicleCv;${String.format("%.2f", indemnity)}")
                        }
                        appendLine("SOUS-TOTAL PERSO;${persoTripsEmp.size} trajets;;${String.format("%.2f", persoTotalKm)} km;;${String.format("%.2f", persoTotalIndem)} €")
                        appendLine()
                    }

                    // NOTES DE FRAIS
                    if (data.includeExpenses && employee.expenses.isNotEmpty()) {
                        val expTotal = employee.expenses.sumOf { it.amount }
                        val expTotalHT = employee.expenses.sumOf { it.amountHT ?: it.amount }
                        val photosCount = employee.expenses.count { it.photoUri != null }
                        appendLine("NOTES DE FRAIS (${employee.expenses.size} notes - ${String.format("%.2f", expTotal)} € - $photosCount photo(s))")
                        appendLine("Date;Type;Montant TTC (€);Montant HT (€);TVA (€);Note;Photo")

                        employee.expenses.sortedBy { it.date }.forEach { expense ->
                            val note = expense.note.replace(";", ",")
                            val tva = expense.amount - (expense.amountHT ?: expense.amount)
                            val hasPhoto = if (expense.photoUri != null) "Oui" else "Non"
                            appendLine("${expense.date};${expense.getExpenseTypeLabel()};${String.format("%.2f", expense.amount)};${String.format("%.2f", expense.amountHT ?: 0.0)};${String.format("%.2f", tva)};$note;$hasPhoto")
                        }
                        appendLine("SOUS-TOTAL FRAIS;;${String.format("%.2f", expTotal)} €;${String.format("%.2f", expTotalHT)} €;;;$photosCount photo(s)")
                        appendLine()
                    }
                }

                // ========== RÉCAPITULATIF NOTES DE FRAIS ==========
                if (data.includeExpenses && data.employees.any { it.expenses.isNotEmpty() }) {
                    appendLine("=" .repeat(60))
                    appendLine("RÉCAPITULATIF NOTES DE FRAIS PAR COLLABORATEUR")
                    appendLine("=" .repeat(60))
                    appendLine()

                    data.employees.filter { it.expenses.isNotEmpty() }.forEach { emp ->
                        val deptStr = emp.department?.let { " ($it)" } ?: ""
                        val empTotal = emp.expenses.sumOf { it.amount }
                        val photosCount = emp.expenses.count { it.photoUri != null }
                        appendLine("--- ${emp.displayName}$deptStr ---")
                        appendLine("Total: ${String.format("%.2f", empTotal)} € | ${emp.expenses.size} note(s) | $photosCount photo(s)")

                        emp.vehicle?.let { v ->
                            appendLine("Véhicule: ${v.name} (${v.power?.cv ?: "N/A"})")
                        }
                        appendLine()
                    }

                    val grandTotal = data.employees.flatMap { it.expenses }.sumOf { it.amount }
                    val totalPhotos = data.employees.flatMap { it.expenses }.count { it.photoUri != null }
                    appendLine("TOTAL NOTES DE FRAIS: ${String.format("%.2f", grandTotal)} € | $totalPhotos photo(s)")
                    appendLine()
                }

                // Mention légale
                appendLine("NOTE LÉGALE")
                appendLine("Les indemnités kilométriques sont calculées selon le barème fiscal en vigueur.")
                appendLine("Ces remboursements ne sont pas soumis à la TVA (art. 261 CGI).")
                appendLine("Document à conserver pour justification URSSAF.")
                appendLine()
                appendLine("Document généré par Motium le ${SimpleDateFormat("dd/MM/yyyy à HH:mm", Locale.getDefault()).format(Date())}")
            }

            csvFile.writeText(csvContent, Charsets.UTF_8)

            MotiumApplication.logger.i("Pro CSV export successful: ${csvFile.absolutePath}", "ExportManager")
            onSuccess(csvFile)

        } catch (e: Exception) {
            val error = "Failed to export Pro CSV: ${e.message}"
            MotiumApplication.logger.e(error, "ExportManager", e)
            onError(error)
        }
    }

    /**
     * Export Pro data to PDF format with company header and employee sections
     * Format légal français avec mentions obligatoires URSSAF
     */
    fun exportProToPDF(
        data: ProExportData,
        onSuccess: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val exportDir = File(context.getExternalFilesDir(null), EXPORT_FOLDER)
            if (!exportDir.exists()) {
                exportDir.mkdirs()
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "Motium_Pro_Facture_${timestamp}.pdf"
            val pdfFile = File(exportDir, fileName)

            val pdfWriter = PdfWriter(pdfFile)
            val pdfDoc = PdfDocument(pdfWriter)
            val document = Document(pdfDoc)

            // En-tête Pro avec informations entreprise
            addProPdfHeader(document, data)

            // Résumé global
            addProPdfSummary(document, data)

            // Sections par employé
            data.employees.forEachIndexed { index, employee ->
                addProEmployeeSection(document, employee, index + 1, data.includeExpenses, data.includePhotos)
            }

            // Pied de page légal
            addProPdfFooter(document)

            document.close()

            MotiumApplication.logger.i("Pro PDF export successful: ${pdfFile.absolutePath}", "ExportManager")
            onSuccess(pdfFile)

        } catch (e: Exception) {
            val error = "Failed to export Pro PDF: ${e.message}"
            MotiumApplication.logger.e(error, "ExportManager", e)
            onError(error)
        }
    }

    /**
     * Export Pro data to Excel format with multiple sheets
     * Format légal français avec mentions obligatoires URSSAF
     */
    fun exportProToExcel(
        data: ProExportData,
        onSuccess: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val exportDir = File(context.getExternalFilesDir(null), EXPORT_FOLDER)
            if (!exportDir.exists()) {
                exportDir.mkdirs()
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "Motium_Pro_Facture_${timestamp}.xlsx"
            val xlsxFile = File(exportDir, fileName)

            val workbook = XSSFWorkbook()
            val styles = createExcelStyles(workbook)

            // Feuille 1: Résumé entreprise
            createProSummarySheet(workbook, data, styles)

            // Feuille 2: Détail par collaborateur
            createProEmployeesSheet(workbook, data, styles)

            // Feuille 3: Notes de frais (si incluses)
            if (data.includeExpenses && data.employees.any { it.expenses.isNotEmpty() }) {
                createProExpensesSheet(workbook, data, styles)
            }

            // Write to file
            FileOutputStream(xlsxFile).use { outputStream ->
                workbook.write(outputStream)
            }
            workbook.close()

            MotiumApplication.logger.i("Pro Excel export successful: ${xlsxFile.absolutePath}", "ExportManager")
            onSuccess(xlsxFile)

        } catch (e: Exception) {
            val error = "Failed to export Pro Excel: ${e.message}"
            MotiumApplication.logger.e(error, "ExportManager", e)
            onError(error)
        }
    }

    // --- Pro PDF Helper Methods ---

    private fun addProPdfHeader(document: Document, data: ProExportData) {
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

        // Table principale pour header
        val headerTable = PdfTable(UnitValue.createPercentArray(floatArrayOf(50f, 50f)))
            .setWidth(UnitValue.createPercentValue(100f))
            .setMarginBottom(20f)

        // Colonne gauche: Informations entreprise
        val companyCell = PdfCell()
            .setBorder(SolidBorder(MOTIUM_GREEN, 2f))
            .setBackgroundColor(MOTIUM_GREEN_LIGHT)
            .setPadding(15f)

        companyCell.add(Paragraph(data.companyName)
            .setFontSize(16f)
            .setBold()
            .setFontColor(MOTIUM_GREEN)
            .setMarginBottom(5f))

        data.legalForm?.let {
            companyCell.add(Paragraph(it)
                .setFontSize(10f)
                .setFontColor(GRAY_DARK))
        }

        data.billingAddress?.let {
            companyCell.add(Paragraph(it)
                .setFontSize(9f)
                .setFontColor(TEXT_PRIMARY)
                .setMarginTop(5f))
        }

        data.siret?.let {
            companyCell.add(Paragraph("SIRET: ${formatSiret(it)}")
                .setFontSize(9f)
                .setFontColor(TEXT_PRIMARY)
                .setMarginTop(5f))
        }

        data.vatNumber?.let {
            companyCell.add(Paragraph("TVA: $it")
                .setFontSize(9f)
                .setFontColor(TEXT_PRIMARY))
        }

        headerTable.addCell(companyCell)

        // Colonne droite: Titre et période
        val titleCell = PdfCell()
            .setBorder(SolidBorder(MOTIUM_PRIMARY, 2f))
            .setBackgroundColor(MOTIUM_PRIMARY_LIGHT)
            .setPadding(15f)

        titleCell.add(Paragraph("NOTE DE FRAIS")
            .setFontSize(18f)
            .setBold()
            .setFontColor(MOTIUM_PRIMARY)
            .setTextAlignment(TextAlignment.CENTER))

        titleCell.add(Paragraph("KILOMÉTRIQUES")
            .setFontSize(14f)
            .setBold()
            .setFontColor(MOTIUM_PRIMARY)
            .setTextAlignment(TextAlignment.CENTER)
            .setMarginBottom(10f))

        titleCell.add(Paragraph("📅 Période")
            .setFontSize(10f)
            .setFontColor(GRAY_DARK)
            .setTextAlignment(TextAlignment.CENTER))

        titleCell.add(Paragraph("${dateFormat.format(Date(data.startDate))} - ${dateFormat.format(Date(data.endDate))}")
            .setFontSize(11f)
            .setBold()
            .setFontColor(TEXT_PRIMARY)
            .setTextAlignment(TextAlignment.CENTER))

        titleCell.add(Paragraph("Généré le ${SimpleDateFormat("dd/MM/yyyy à HH:mm", Locale.getDefault()).format(Date())}")
            .setFontSize(8f)
            .setFontColor(GRAY_DARK)
            .setTextAlignment(TextAlignment.CENTER)
            .setMarginTop(5f))

        headerTable.addCell(titleCell)
        document.add(headerTable)
    }

    private fun addProPdfSummary(document: Document, data: ProExportData) {
        val allTrips = data.employees.flatMap { it.trips }
        val allExpenses = data.employees.flatMap { it.expenses }
        val proTrips = allTrips.filter { it.type == TripType.PROFESSIONAL }
        val persoTrips = allTrips.filter { it.type == TripType.PERSONAL }
        val totalProIndemnities = proTrips.sumOf { it.reimbursementAmount ?: (it.distanceKm * DEFAULT_MILEAGE_RATE) }
        val totalPersoIndemnities = persoTrips.sumOf { it.reimbursementAmount ?: (it.distanceKm * DEFAULT_MILEAGE_RATE) }
        val totalIndemnities = totalProIndemnities + totalPersoIndemnities
        val totalExpenses = allExpenses.sumOf { it.amount }

        // ========== RÉSUMÉ PAR DÉPARTEMENT ==========
        document.add(Paragraph("📁 RÉSUMÉ PAR DÉPARTEMENT")
            .setFontSize(14f)
            .setBold()
            .setFontColor(DEPT_COLOR)
            .setMarginBottom(10f))

        val departmentSummaries = buildDepartmentSummaries(data.employees)

        departmentSummaries.forEach { deptSummary ->
            // En-tête département
            val deptHeader = PdfTable(UnitValue.createPercentArray(floatArrayOf(100f)))
                .setWidth(UnitValue.createPercentValue(100f))
                .setMarginTop(8f)

            deptHeader.addCell(
                PdfCell().add(Paragraph("📂 ${deptSummary.departmentName}")
                    .setFontSize(11f)
                    .setBold())
                    .setBackgroundColor(DEPT_LIGHT)
                    .setFontColor(DEPT_COLOR)
                    .setPadding(8f)
                    .setBorder(SolidBorder(DEPT_COLOR, 1f))
            )
            document.add(deptHeader)

            // Tableau collaborateurs du département
            val deptTable = PdfTable(UnitValue.createPercentArray(floatArrayOf(40f, 20f, 20f, 20f)))
                .setWidth(UnitValue.createPercentValue(100f))
                .setFontSize(9f)

            // En-têtes
            listOf("Collaborateur", "Pro", "Perso", "Frais").forEach { header ->
                deptTable.addCell(
                    PdfCell().add(Paragraph(header).setBold().setFontSize(8f))
                        .setBackgroundColor(GRAY_LIGHT)
                        .setPadding(5f)
                        .setBorder(SolidBorder(GRAY_MEDIUM, 0.5f))
                )
            }

            deptSummary.employees.forEach { emp ->
                deptTable.addCell(
                    PdfCell().add(Paragraph(emp.displayName).setFontSize(8f))
                        .setPadding(5f)
                        .setBorder(SolidBorder(GRAY_MEDIUM, 0.5f))
                )
                deptTable.addCell(
                    PdfCell().add(Paragraph("${String.format("%.2f", emp.proIndemnities)} €").setFontSize(8f))
                        .setPadding(5f)
                        .setTextAlignment(TextAlignment.RIGHT)
                        .setBorder(SolidBorder(GRAY_MEDIUM, 0.5f))
                )
                deptTable.addCell(
                    PdfCell().add(Paragraph("${String.format("%.2f", emp.persoIndemnities)} €").setFontSize(8f))
                        .setPadding(5f)
                        .setTextAlignment(TextAlignment.RIGHT)
                        .setBorder(SolidBorder(GRAY_MEDIUM, 0.5f))
                )
                deptTable.addCell(
                    PdfCell().add(Paragraph(if (data.includeExpenses) "${String.format("%.2f", emp.expensesTotal)} €" else "-").setFontSize(8f))
                        .setPadding(5f)
                        .setTextAlignment(TextAlignment.RIGHT)
                        .setBorder(SolidBorder(GRAY_MEDIUM, 0.5f))
                )
            }

            // Sous-total département
            val deptProTotal = deptSummary.employees.sumOf { it.proIndemnities }
            val deptPersoTotal = deptSummary.employees.sumOf { it.persoIndemnities }
            val deptExpensesTotal = deptSummary.employees.sumOf { it.expensesTotal }

            deptTable.addCell(
                PdfCell().add(Paragraph("Sous-total").setBold().setFontSize(8f))
                    .setBackgroundColor(DEPT_LIGHT)
                    .setPadding(5f)
                    .setBorder(SolidBorder(DEPT_COLOR, 0.5f))
            )
            deptTable.addCell(
                PdfCell().add(Paragraph("${String.format("%.2f", deptProTotal)} €").setBold().setFontSize(8f))
                    .setBackgroundColor(DEPT_LIGHT)
                    .setPadding(5f)
                    .setTextAlignment(TextAlignment.RIGHT)
                    .setBorder(SolidBorder(DEPT_COLOR, 0.5f))
            )
            deptTable.addCell(
                PdfCell().add(Paragraph("${String.format("%.2f", deptPersoTotal)} €").setBold().setFontSize(8f))
                    .setBackgroundColor(DEPT_LIGHT)
                    .setPadding(5f)
                    .setTextAlignment(TextAlignment.RIGHT)
                    .setBorder(SolidBorder(DEPT_COLOR, 0.5f))
            )
            deptTable.addCell(
                PdfCell().add(Paragraph(if (data.includeExpenses) "${String.format("%.2f", deptExpensesTotal)} €" else "-").setBold().setFontSize(8f))
                    .setBackgroundColor(DEPT_LIGHT)
                    .setPadding(5f)
                    .setTextAlignment(TextAlignment.RIGHT)
                    .setBorder(SolidBorder(DEPT_COLOR, 0.5f))
            )

            document.add(deptTable)
        }

        // ========== TOTAL GÉNÉRAL ==========
        document.add(Paragraph("📊 TOTAL GÉNÉRAL")
            .setFontSize(12f)
            .setBold()
            .setFontColor(MOTIUM_PRIMARY)
            .setMarginTop(15f)
            .setMarginBottom(8f))

        val totalTable = PdfTable(UnitValue.createPercentArray(floatArrayOf(50f, 50f)))
            .setWidth(UnitValue.createPercentValue(100f))
            .setMarginBottom(20f)

        addSummaryRow(totalTable, "Indemnités Pro", "${String.format("%.2f", totalProIndemnities)} €")
        addSummaryRow(totalTable, "Indemnités Perso", "${String.format("%.2f", totalPersoIndemnities)} €")

        if (data.includeExpenses && totalExpenses > 0) {
            addSummaryRow(totalTable, "Notes de frais", "${String.format("%.2f", totalExpenses)} €")

            totalTable.addCell(
                PdfCell().add(Paragraph("TOTAL GÉNÉRAL").setBold())
                    .setBackgroundColor(MOTIUM_GREEN)
                    .setFontColor(com.itextpdf.kernel.colors.ColorConstants.WHITE)
                    .setPadding(10f)
                    .setBorder(null)
            )
            totalTable.addCell(
                PdfCell().add(Paragraph("${String.format("%.2f", totalIndemnities + totalExpenses)} €").setBold())
                    .setBackgroundColor(MOTIUM_GREEN)
                    .setFontColor(com.itextpdf.kernel.colors.ColorConstants.WHITE)
                    .setTextAlignment(TextAlignment.RIGHT)
                    .setPadding(10f)
                    .setBorder(null)
            )
        } else {
            totalTable.addCell(
                PdfCell().add(Paragraph("TOTAL").setBold())
                    .setBackgroundColor(MOTIUM_GREEN)
                    .setFontColor(com.itextpdf.kernel.colors.ColorConstants.WHITE)
                    .setPadding(10f)
                    .setBorder(null)
            )
            totalTable.addCell(
                PdfCell().add(Paragraph("${String.format("%.2f", totalIndemnities)} €").setBold())
                    .setBackgroundColor(MOTIUM_GREEN)
                    .setFontColor(com.itextpdf.kernel.colors.ColorConstants.WHITE)
                    .setTextAlignment(TextAlignment.RIGHT)
                    .setPadding(10f)
                    .setBorder(null)
            )
        }

        document.add(totalTable)
    }

    private fun addProEmployeeSection(
        document: Document,
        employee: EmployeeExportData,
        index: Int,
        includeExpenses: Boolean,
        includePhotos: Boolean = false
    ) {
        val proTrips = employee.trips.filter { it.type == TripType.PROFESSIONAL }
        val persoTrips = employee.trips.filter { it.type == TripType.PERSONAL }
        val dateFormat = SimpleDateFormat("dd/MM/yy", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        // En-tête employé avec département
        val employeeHeader = PdfTable(UnitValue.createPercentArray(floatArrayOf(100f)))
            .setWidth(UnitValue.createPercentValue(100f))
            .setMarginTop(15f)
            .setMarginBottom(10f)

        val headerCell = PdfCell()
            .setBackgroundColor(MOTIUM_PRIMARY)
            .setPadding(12f)
            .setBorder(null)

        val deptStr = employee.department?.let { " ($it)" } ?: ""
        headerCell.add(Paragraph("👤 COLLABORATEUR $index: ${employee.displayName}$deptStr")
            .setFontSize(12f)
            .setBold()
            .setFontColor(com.itextpdf.kernel.colors.ColorConstants.WHITE))

        headerCell.add(Paragraph("📧 ${employee.email}")
            .setFontSize(9f)
            .setFontColor(com.itextpdf.kernel.colors.ColorConstants.WHITE))

        employee.vehicle?.let { v ->
            val powerStr = v.power?.cv ?: "N/A"
            headerCell.add(Paragraph("🚗 ${v.name} ($powerStr) - ${v.type.displayName}")
                .setFontSize(9f)
                .setFontColor(com.itextpdf.kernel.colors.ColorConstants.WHITE))
        }

        employeeHeader.addCell(headerCell)
        document.add(employeeHeader)

        // ========== 💼 TRAJETS PROFESSIONNELS ==========
        if (proTrips.isNotEmpty()) {
            val proTotalKm = proTrips.sumOf { it.distanceKm }
            val proTotalIndemnities = proTrips.sumOf { it.reimbursementAmount ?: (it.distanceKm * DEFAULT_MILEAGE_RATE) }

            // Sous-titre Pro
            val proHeader = PdfTable(UnitValue.createPercentArray(floatArrayOf(100f)))
                .setWidth(UnitValue.createPercentValue(100f))
                .setMarginTop(8f)

            proHeader.addCell(
                PdfCell().add(Paragraph("💼 Trajets Professionnels (${proTrips.size} trajets - ${String.format("%.2f", proTotalIndemnities)} €)")
                    .setFontSize(10f)
                    .setBold())
                    .setBackgroundColor(PRO_TRIP_LIGHT)
                    .setFontColor(PRO_TRIP_COLOR)
                    .setPadding(8f)
                    .setBorder(SolidBorder(PRO_TRIP_COLOR, 1f))
            )
            document.add(proHeader)

            // Tableau trajets pro
            val proTable = PdfTable(UnitValue.createPercentArray(floatArrayOf(12f, 8f, 24f, 24f, 10f, 10f, 12f)))
                .setWidth(UnitValue.createPercentValue(100f))
                .setFontSize(8f)

            listOf("Date", "Heure", "Départ", "Arrivée", "Km", "CV", "Indem.").forEach { h ->
                proTable.addCell(
                    PdfCell().add(Paragraph(h).setBold().setFontSize(7f))
                        .setBackgroundColor(PRO_TRIP_LIGHT)
                        .setPadding(4f)
                        .setBorder(SolidBorder(GRAY_MEDIUM, 0.5f))
                )
            }

            proTrips.sortedBy { it.startTime }.forEach { trip ->
                val indemnity = trip.reimbursementAmount ?: (trip.distanceKm * DEFAULT_MILEAGE_RATE)
                val vehicleCv = employee.vehicle?.power?.cv ?: "N/A"
                addTableCell(proTable, dateFormat.format(Date(trip.startTime.toEpochMilliseconds())))
                addTableCell(proTable, timeFormat.format(Date(trip.startTime.toEpochMilliseconds())))
                addTableCell(proTable, trip.startAddress?.take(28) ?: "N/A")
                addTableCell(proTable, trip.endAddress?.take(28) ?: "N/A")
                addTableCell(proTable, String.format("%.1f", trip.distanceKm))
                addTableCell(proTable, vehicleCv)
                addTableCell(proTable, String.format("%.2f€", indemnity))
            }

            document.add(proTable)

            // Sous-total Pro
            document.add(Paragraph("Sous-total Pro: ${String.format("%.2f", proTotalKm)} km → ${String.format("%.2f", proTotalIndemnities)} €")
                .setFontSize(9f)
                .setBold()
                .setFontColor(PRO_TRIP_COLOR)
                .setTextAlignment(TextAlignment.RIGHT)
                .setMarginTop(3f)
                .setMarginBottom(8f))
        }

        // ========== 🏠 TRAJETS PERSONNELS ==========
        if (persoTrips.isNotEmpty()) {
            val persoTotalKm = persoTrips.sumOf { it.distanceKm }
            val persoTotalIndemnities = persoTrips.sumOf { it.reimbursementAmount ?: (it.distanceKm * DEFAULT_MILEAGE_RATE) }

            // Sous-titre Perso
            val persoHeader = PdfTable(UnitValue.createPercentArray(floatArrayOf(100f)))
                .setWidth(UnitValue.createPercentValue(100f))
                .setMarginTop(8f)

            persoHeader.addCell(
                PdfCell().add(Paragraph("🏠 Trajets Personnels (${persoTrips.size} trajets - ${String.format("%.2f", persoTotalIndemnities)} €)")
                    .setFontSize(10f)
                    .setBold())
                    .setBackgroundColor(PERSO_TRIP_LIGHT)
                    .setFontColor(PERSO_TRIP_COLOR)
                    .setPadding(8f)
                    .setBorder(SolidBorder(PERSO_TRIP_COLOR, 1f))
            )
            document.add(persoHeader)

            // Tableau trajets perso
            val persoTable = PdfTable(UnitValue.createPercentArray(floatArrayOf(12f, 8f, 24f, 24f, 10f, 10f, 12f)))
                .setWidth(UnitValue.createPercentValue(100f))
                .setFontSize(8f)

            listOf("Date", "Heure", "Départ", "Arrivée", "Km", "CV", "Indem.").forEach { h ->
                persoTable.addCell(
                    PdfCell().add(Paragraph(h).setBold().setFontSize(7f))
                        .setBackgroundColor(PERSO_TRIP_LIGHT)
                        .setPadding(4f)
                        .setBorder(SolidBorder(GRAY_MEDIUM, 0.5f))
                )
            }

            persoTrips.sortedBy { it.startTime }.forEach { trip ->
                val indemnity = trip.reimbursementAmount ?: (trip.distanceKm * DEFAULT_MILEAGE_RATE)
                val vehicleCv = employee.vehicle?.power?.cv ?: "N/A"
                addTableCell(persoTable, dateFormat.format(Date(trip.startTime.toEpochMilliseconds())))
                addTableCell(persoTable, timeFormat.format(Date(trip.startTime.toEpochMilliseconds())))
                addTableCell(persoTable, trip.startAddress?.take(28) ?: "N/A")
                addTableCell(persoTable, trip.endAddress?.take(28) ?: "N/A")
                addTableCell(persoTable, String.format("%.1f", trip.distanceKm))
                addTableCell(persoTable, vehicleCv)
                addTableCell(persoTable, String.format("%.2f€", indemnity))
            }

            document.add(persoTable)

            // Sous-total Perso
            document.add(Paragraph("Sous-total Perso: ${String.format("%.2f", persoTotalKm)} km → ${String.format("%.2f", persoTotalIndemnities)} €")
                .setFontSize(9f)
                .setBold()
                .setFontColor(PERSO_TRIP_COLOR)
                .setTextAlignment(TextAlignment.RIGHT)
                .setMarginTop(3f)
                .setMarginBottom(8f))
        }

        // ========== 🧾 NOTES DE FRAIS ==========
        if (includeExpenses && employee.expenses.isNotEmpty()) {
            val expTotalAmount = employee.expenses.sumOf { it.amount }

            // Sous-titre Dépenses
            val expHeader = PdfTable(UnitValue.createPercentArray(floatArrayOf(100f)))
                .setWidth(UnitValue.createPercentValue(100f))
                .setMarginTop(8f)

            expHeader.addCell(
                PdfCell().add(Paragraph("🧾 Notes de Frais (${employee.expenses.size} notes - ${String.format("%.2f", expTotalAmount)} €)")
                    .setFontSize(10f)
                    .setBold())
                    .setBackgroundColor(EXPENSE_LIGHT)
                    .setFontColor(EXPENSE_COLOR)
                    .setPadding(8f)
                    .setBorder(SolidBorder(EXPENSE_COLOR, 1f))
            )
            document.add(expHeader)

            // Tableau dépenses
            val expTable = PdfTable(UnitValue.createPercentArray(floatArrayOf(15f, 25f, 15f, 45f)))
                .setWidth(UnitValue.createPercentValue(100f))
                .setFontSize(8f)

            listOf("Date", "Type", "Montant", "Note").forEach { h ->
                expTable.addCell(
                    PdfCell().add(Paragraph(h).setBold().setFontSize(7f))
                        .setBackgroundColor(EXPENSE_LIGHT)
                        .setPadding(4f)
                        .setBorder(SolidBorder(GRAY_MEDIUM, 0.5f))
                )
            }

            employee.expenses.forEach { expense ->
                addTableCell(expTable, expense.date)
                addTableCell(expTable, expense.getExpenseTypeLabel())
                addTableCell(expTable, String.format("%.2f€", expense.amount))
                addTableCell(expTable, expense.note.ifEmpty { "-" })
            }

            document.add(expTable)

            // Photos des justificatifs si activé
            if (includePhotos) {
                employee.expenses.filter { it.photoUri != null }.forEach { expense ->
                    try {
                        // Tenter de charger l'image depuis l'URI (peut être local ou Supabase)
                        val photoUri = expense.photoUri!!
                        val photoBytes: ByteArray? = if (photoUri.startsWith("http")) {
                            // URL Supabase
                            runBlocking { storageService.downloadReceiptPhoto(photoUri).getOrNull() }
                        } else {
                            // Fichier local
                            try {
                                java.io.File(photoUri).readBytes()
                            } catch (e: Exception) { null }
                        }
                        if (photoBytes != null && photoBytes.isNotEmpty()) {
                            val imageData = com.itextpdf.io.image.ImageDataFactory.create(photoBytes)
                            val image = Image(imageData)
                                .scaleToFit(150f, 200f)
                                .setMarginTop(5f)
                                .setMarginBottom(5f)
                            document.add(Paragraph("📷 ${expense.getExpenseTypeLabel()} - ${expense.date}")
                                .setFontSize(8f)
                                .setFontColor(GRAY_DARK)
                                .setMarginTop(5f))
                            document.add(image)
                        }
                    } catch (e: Exception) {
                        MotiumApplication.logger.w("Could not load expense photo: ${e.message}", "ExportManager")
                    }
                }
            }

            // Sous-total Frais
            document.add(Paragraph("Sous-total Frais: ${String.format("%.2f", expTotalAmount)} €")
                .setFontSize(9f)
                .setBold()
                .setFontColor(EXPENSE_COLOR)
                .setTextAlignment(TextAlignment.RIGHT)
                .setMarginTop(3f)
                .setMarginBottom(8f))
        }
    }

    private fun addProPdfFooter(document: Document) {
        document.add(Paragraph("\n"))

        // Mention légale
        val legalBox = PdfTable(UnitValue.createPercentArray(floatArrayOf(100f)))
            .setWidth(UnitValue.createPercentValue(100f))
            .setMarginTop(20f)

        val legalCell = PdfCell()
            .setBackgroundColor(GRAY_LIGHT)
            .setBorder(SolidBorder(GRAY_MEDIUM, 1f))
            .setPadding(12f)

        legalCell.add(Paragraph("📋 MENTIONS LÉGALES")
            .setFontSize(10f)
            .setBold()
            .setFontColor(TEXT_PRIMARY)
            .setMarginBottom(5f))

        legalCell.add(Paragraph("Les indemnités kilométriques sont calculées selon le barème fiscal en vigueur (barème URSSAF 2024).")
            .setFontSize(8f)
            .setFontColor(GRAY_DARK))

        legalCell.add(Paragraph("Ces remboursements ne sont pas soumis à la TVA (art. 261 du Code Général des Impôts).")
            .setFontSize(8f)
            .setFontColor(GRAY_DARK))

        legalCell.add(Paragraph("Document à conserver pour justification en cas de contrôle URSSAF.")
            .setFontSize(8f)
            .setFontColor(GRAY_DARK))

        legalBox.addCell(legalCell)
        document.add(legalBox)

        // Pied de page Motium
        val footerDivider = PdfTable(UnitValue.createPercentArray(floatArrayOf(100f)))
            .setWidth(UnitValue.createPercentValue(100f))
            .setMarginTop(15f)
            .setMarginBottom(5f)
        footerDivider.addCell(
            PdfCell()
                .setBackgroundColor(MOTIUM_GREEN)
                .setHeight(2f)
                .setBorder(null)
        )
        document.add(footerDivider)

        document.add(Paragraph("Motium — Gestion de mobilité professionnelle")
            .setFontSize(9f)
            .setFontColor(MOTIUM_GREEN)
            .setBold()
            .setTextAlignment(TextAlignment.CENTER))
    }

    // --- Pro Excel Helper Methods ---

    private fun createProSummarySheet(workbook: XSSFWorkbook, data: ProExportData, styles: ExcelStyles) {
        val sheet = workbook.createSheet("Résumé")
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        var rowNum = 0

        // Titre
        val titleRow = sheet.createRow(rowNum++)
        titleRow.createCell(0).apply {
            setCellValue("${data.companyName} - NOTE DE FRAIS KILOMÉTRIQUES")
            cellStyle = styles.titleStyle
        }
        sheet.addMergedRegion(CellRangeAddress(0, 0, 0, 5))

        // Infos entreprise
        rowNum++
        data.legalForm?.let {
            sheet.createRow(rowNum++).createCell(0).setCellValue("Forme juridique: $it")
        }
        data.billingAddress?.let {
            sheet.createRow(rowNum++).createCell(0).setCellValue("Adresse: ${it.replace("\n", " ")}")
        }
        data.siret?.let {
            sheet.createRow(rowNum++).createCell(0).setCellValue("SIRET: ${formatSiret(it)}")
        }
        data.vatNumber?.let {
            sheet.createRow(rowNum++).createCell(0).setCellValue("N° TVA: $it")
        }

        // Période
        rowNum++
        sheet.createRow(rowNum++).createCell(0).apply {
            setCellValue("Période: ${dateFormat.format(Date(data.startDate))} - ${dateFormat.format(Date(data.endDate))}")
            cellStyle = styles.subtitleStyle
        }

        // ========== RÉSUMÉ PAR DÉPARTEMENT ==========
        rowNum += 2
        sheet.createRow(rowNum++).createCell(0).apply {
            setCellValue("RÉSUMÉ PAR DÉPARTEMENT")
            cellStyle = styles.headerStyle
        }
        sheet.addMergedRegion(CellRangeAddress(rowNum - 1, rowNum - 1, 0, 4))

        // Headers du tableau département
        val deptHeaderRow = sheet.createRow(rowNum++)
        listOf("Département", "Collaborateur", "Indemnités Pro", "Indemnités Perso", "Frais").forEachIndexed { i, h ->
            deptHeaderRow.createCell(i).apply {
                setCellValue(h)
                cellStyle = styles.headerStyle
            }
        }

        val departmentSummaries = buildDepartmentSummaries(data.employees)
        departmentSummaries.forEach { deptSummary ->
            deptSummary.employees.forEach { emp ->
                val row = sheet.createRow(rowNum++)
                row.createCell(0).apply { setCellValue(deptSummary.departmentName); cellStyle = styles.dataStyle }
                row.createCell(1).apply { setCellValue(emp.displayName); cellStyle = styles.dataStyle }
                row.createCell(2).apply { setCellValue(emp.proIndemnities); cellStyle = styles.currencyStyle }
                row.createCell(3).apply { setCellValue(emp.persoIndemnities); cellStyle = styles.currencyStyle }
                row.createCell(4).apply {
                    if (data.includeExpenses) setCellValue(emp.expensesTotal) else setCellValue("-")
                    cellStyle = if (data.includeExpenses) styles.currencyStyle else styles.dataStyle
                }
            }
            // Sous-total département
            val subtotalRow = sheet.createRow(rowNum++)
            val deptProTotal = deptSummary.employees.sumOf { it.proIndemnities }
            val deptPersoTotal = deptSummary.employees.sumOf { it.persoIndemnities }
            val deptExpTotal = deptSummary.employees.sumOf { it.expensesTotal }
            subtotalRow.createCell(0).apply { setCellValue("${deptSummary.departmentName} SOUS-TOTAL"); cellStyle = styles.totalLabelStyle }
            subtotalRow.createCell(2).apply { setCellValue(deptProTotal); cellStyle = styles.totalValueStyle }
            subtotalRow.createCell(3).apply { setCellValue(deptPersoTotal); cellStyle = styles.totalValueStyle }
            subtotalRow.createCell(4).apply {
                if (data.includeExpenses) setCellValue(deptExpTotal) else setCellValue("-")
                cellStyle = if (data.includeExpenses) styles.totalValueStyle else styles.dataStyle
            }
        }

        // ========== TOTAL GÉNÉRAL ==========
        val allTrips = data.employees.flatMap { it.trips }
        val allExpenses = data.employees.flatMap { it.expenses }
        val proTrips = allTrips.filter { it.type == TripType.PROFESSIONAL }
        val persoTrips = allTrips.filter { it.type == TripType.PERSONAL }
        val totalProIndemnities = proTrips.sumOf { it.reimbursementAmount ?: (it.distanceKm * DEFAULT_MILEAGE_RATE) }
        val totalPersoIndemnities = persoTrips.sumOf { it.reimbursementAmount ?: (it.distanceKm * DEFAULT_MILEAGE_RATE) }
        val totalIndemnities = totalProIndemnities + totalPersoIndemnities
        val totalExpenses = allExpenses.sumOf { it.amount }

        rowNum += 2
        addSummaryRow(sheet, rowNum++, "Indemnités Pro", String.format("%.2f €", totalProIndemnities), styles)
        addSummaryRow(sheet, rowNum++, "Indemnités Perso", String.format("%.2f €", totalPersoIndemnities), styles)

        if (data.includeExpenses && totalExpenses > 0) {
            addSummaryRow(sheet, rowNum++, "Notes de frais", String.format("%.2f €", totalExpenses), styles)
            rowNum++
            addTotalRow(sheet, rowNum++, "TOTAL GÉNÉRAL", totalIndemnities + totalExpenses, styles)
        } else {
            rowNum++
            addTotalRow(sheet, rowNum++, "TOTAL", totalIndemnities, styles)
        }

        // Note légale
        rowNum += 2
        sheet.createRow(rowNum++).createCell(0).setCellValue("Note: Les indemnités kilométriques ne sont pas soumises à la TVA.")
        sheet.createRow(rowNum).createCell(0).setCellValue("Document généré par Motium le ${SimpleDateFormat("dd/MM/yyyy à HH:mm", Locale.getDefault()).format(Date())}")

        // Auto-size
        for (i in 0..5) sheet.setColumnWidth(i, 6000)
    }

    private fun createProEmployeesSheet(workbook: XSSFWorkbook, data: ProExportData, styles: ExcelStyles) {
        val sheet = workbook.createSheet("Trajets détaillés")
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        var rowNum = 0

        data.employees.forEachIndexed { empIndex, emp ->
            val deptStr = emp.department?.let { " ($it)" } ?: ""

            // Header employé
            val empHeaderRow = sheet.createRow(rowNum++)
            empHeaderRow.createCell(0).apply {
                setCellValue("${empIndex + 1}. ${emp.displayName}$deptStr (${emp.email})")
                cellStyle = styles.totalLabelStyle
            }
            sheet.addMergedRegion(CellRangeAddress(rowNum - 1, rowNum - 1, 0, 6))

            emp.vehicle?.let { v ->
                val vRow = sheet.createRow(rowNum++)
                vRow.createCell(0).setCellValue("Véhicule: ${v.name} (${v.power?.cv ?: "N/A"}) - ${v.type.displayName}")
            }

            val proTrips = emp.trips.filter { it.type == TripType.PROFESSIONAL }
            val persoTrips = emp.trips.filter { it.type == TripType.PERSONAL }

            // ========== TRAJETS PROFESSIONNELS ==========
            if (proTrips.isNotEmpty()) {
                rowNum++
                val proTitleRow = sheet.createRow(rowNum++)
                proTitleRow.createCell(0).apply {
                    val proTotal = proTrips.sumOf { it.reimbursementAmount ?: (it.distanceKm * DEFAULT_MILEAGE_RATE) }
                    setCellValue("💼 TRAJETS PROFESSIONNELS (${proTrips.size} trajets - ${String.format("%.2f", proTotal)} €)")
                    cellStyle = styles.subtitleStyle
                }
                sheet.addMergedRegion(CellRangeAddress(rowNum - 1, rowNum - 1, 0, 6))

                // Headers
                val proHeaderRow = sheet.createRow(rowNum++)
                listOf("Date", "Heure", "Départ", "Arrivée", "Km", "CV", "Indemnités").forEachIndexed { i, h ->
                    proHeaderRow.createCell(i).apply { setCellValue(h); cellStyle = styles.headerStyle }
                }

                proTrips.sortedBy { it.startTime }.forEach { trip ->
                    val row = sheet.createRow(rowNum++)
                    val indemnity = trip.reimbursementAmount ?: (trip.distanceKm * DEFAULT_MILEAGE_RATE)
                    val vehicleCv = emp.vehicle?.power?.cv ?: "N/A"
                    row.createCell(0).apply { setCellValue(dateFormat.format(Date(trip.startTime.toEpochMilliseconds()))); cellStyle = styles.dataStyle }
                    row.createCell(1).apply { setCellValue(timeFormat.format(Date(trip.startTime.toEpochMilliseconds()))); cellStyle = styles.dataStyle }
                    row.createCell(2).apply { setCellValue(trip.startAddress?.take(35) ?: "N/A"); cellStyle = styles.dataStyle }
                    row.createCell(3).apply { setCellValue(trip.endAddress?.take(35) ?: "N/A"); cellStyle = styles.dataStyle }
                    row.createCell(4).apply { setCellValue(trip.distanceKm); cellStyle = styles.currencyStyle }
                    row.createCell(5).apply { setCellValue(vehicleCv); cellStyle = styles.dataStyle }
                    row.createCell(6).apply { setCellValue(indemnity); cellStyle = styles.currencyStyle }
                }

                // Sous-total Pro
                val proTotalKm = proTrips.sumOf { it.distanceKm }
                val proTotalIndem = proTrips.sumOf { it.reimbursementAmount ?: (it.distanceKm * DEFAULT_MILEAGE_RATE) }
                val proSubRow = sheet.createRow(rowNum++)
                proSubRow.createCell(0).apply { setCellValue("SOUS-TOTAL PRO"); cellStyle = styles.totalLabelStyle }
                proSubRow.createCell(3).apply { setCellValue("${proTrips.size} trajets"); cellStyle = styles.totalLabelStyle }
                proSubRow.createCell(4).apply { setCellValue(proTotalKm); cellStyle = styles.totalValueStyle }
                proSubRow.createCell(6).apply { setCellValue(proTotalIndem); cellStyle = styles.totalValueStyle }
            }

            // ========== TRAJETS PERSONNELS ==========
            if (persoTrips.isNotEmpty()) {
                rowNum++
                val persoTitleRow = sheet.createRow(rowNum++)
                persoTitleRow.createCell(0).apply {
                    val persoTotal = persoTrips.sumOf { it.reimbursementAmount ?: (it.distanceKm * DEFAULT_MILEAGE_RATE) }
                    setCellValue("🏠 TRAJETS PERSONNELS (${persoTrips.size} trajets - ${String.format("%.2f", persoTotal)} €)")
                    cellStyle = styles.subtitleStyle
                }
                sheet.addMergedRegion(CellRangeAddress(rowNum - 1, rowNum - 1, 0, 6))

                // Headers
                val persoHeaderRow = sheet.createRow(rowNum++)
                listOf("Date", "Heure", "Départ", "Arrivée", "Km", "CV", "Indemnités").forEachIndexed { i, h ->
                    persoHeaderRow.createCell(i).apply { setCellValue(h); cellStyle = styles.headerStyle }
                }

                persoTrips.sortedBy { it.startTime }.forEach { trip ->
                    val row = sheet.createRow(rowNum++)
                    val indemnity = trip.reimbursementAmount ?: (trip.distanceKm * DEFAULT_MILEAGE_RATE)
                    val vehicleCv = emp.vehicle?.power?.cv ?: "N/A"
                    row.createCell(0).apply { setCellValue(dateFormat.format(Date(trip.startTime.toEpochMilliseconds()))); cellStyle = styles.dataStyle }
                    row.createCell(1).apply { setCellValue(timeFormat.format(Date(trip.startTime.toEpochMilliseconds()))); cellStyle = styles.dataStyle }
                    row.createCell(2).apply { setCellValue(trip.startAddress?.take(35) ?: "N/A"); cellStyle = styles.dataStyle }
                    row.createCell(3).apply { setCellValue(trip.endAddress?.take(35) ?: "N/A"); cellStyle = styles.dataStyle }
                    row.createCell(4).apply { setCellValue(trip.distanceKm); cellStyle = styles.currencyStyle }
                    row.createCell(5).apply { setCellValue(vehicleCv); cellStyle = styles.dataStyle }
                    row.createCell(6).apply { setCellValue(indemnity); cellStyle = styles.currencyStyle }
                }

                // Sous-total Perso
                val persoTotalKm = persoTrips.sumOf { it.distanceKm }
                val persoTotalIndem = persoTrips.sumOf { it.reimbursementAmount ?: (it.distanceKm * DEFAULT_MILEAGE_RATE) }
                val persoSubRow = sheet.createRow(rowNum++)
                persoSubRow.createCell(0).apply { setCellValue("SOUS-TOTAL PERSO"); cellStyle = styles.totalLabelStyle }
                persoSubRow.createCell(3).apply { setCellValue("${persoTrips.size} trajets"); cellStyle = styles.totalLabelStyle }
                persoSubRow.createCell(4).apply { setCellValue(persoTotalKm); cellStyle = styles.totalValueStyle }
                persoSubRow.createCell(6).apply { setCellValue(persoTotalIndem); cellStyle = styles.totalValueStyle }
            }

            // ========== NOTES DE FRAIS ==========
            if (data.includeExpenses && emp.expenses.isNotEmpty()) {
                rowNum++
                val expTitleRow = sheet.createRow(rowNum++)
                val expTotal = emp.expenses.sumOf { it.amount }
                expTitleRow.createCell(0).apply {
                    setCellValue("🧾 NOTES DE FRAIS (${emp.expenses.size} notes - ${String.format("%.2f", expTotal)} €)")
                    cellStyle = styles.subtitleStyle
                }
                sheet.addMergedRegion(CellRangeAddress(rowNum - 1, rowNum - 1, 0, 6))

                // Headers
                val expHeaderRow = sheet.createRow(rowNum++)
                listOf("Date", "Type", "Montant TTC", "Montant HT", "Note", "", "").forEachIndexed { i, h ->
                    expHeaderRow.createCell(i).apply { setCellValue(h); cellStyle = styles.headerStyle }
                }

                emp.expenses.forEach { expense ->
                    val row = sheet.createRow(rowNum++)
                    row.createCell(0).apply { setCellValue(expense.date); cellStyle = styles.dataStyle }
                    row.createCell(1).apply { setCellValue(expense.getExpenseTypeLabel()); cellStyle = styles.dataStyle }
                    row.createCell(2).apply { setCellValue(expense.amount); cellStyle = styles.currencyStyle }
                    row.createCell(3).apply { setCellValue(expense.amountHT ?: 0.0); cellStyle = styles.currencyStyle }
                    row.createCell(4).apply { setCellValue(expense.note.ifEmpty { "-" }); cellStyle = styles.dataStyle }
                }

                // Sous-total Frais
                val expSubRow = sheet.createRow(rowNum++)
                expSubRow.createCell(0).apply { setCellValue("SOUS-TOTAL FRAIS"); cellStyle = styles.totalLabelStyle }
                expSubRow.createCell(2).apply { setCellValue(expTotal); cellStyle = styles.totalValueStyle }
            }

            rowNum += 2 // Empty rows between employees
        }

        // Set fixed column widths (autoSizeColumn uses AWT which is not available on Android)
        val proColumnWidths = listOf(12, 8, 30, 30, 12, 14, 12) // Date, Heure, Départ, Arrivée, Distance, Indemnités, Frais
        proColumnWidths.forEachIndexed { index, width ->
            sheet.setColumnWidth(index, width * 256)
        }
    }

    private fun createProExpensesSheet(workbook: XSSFWorkbook, data: ProExportData, styles: ExcelStyles) {
        val sheet = workbook.createSheet("Notes de frais")
        var rowNum = 0

        // Titre de la feuille
        val titleRow = sheet.createRow(rowNum++)
        titleRow.createCell(0).apply {
            setCellValue("🧾 NOTES DE FRAIS PAR COLLABORATEUR")
            cellStyle = styles.titleStyle
        }
        sheet.addMergedRegion(CellRangeAddress(0, 0, 0, 6))
        rowNum++ // Empty row

        // Expenses grouped by employee with subsections
        data.employees.filter { it.expenses.isNotEmpty() }.forEachIndexed { empIndex, emp ->
            val deptStr = emp.department?.let { " ($it)" } ?: ""
            val empTotal = emp.expenses.sumOf { it.amount }
            val expWithPhoto = emp.expenses.count { it.photoUri != null }

            // Header collaborateur
            val empHeaderRow = sheet.createRow(rowNum++)
            empHeaderRow.createCell(0).apply {
                setCellValue("👤 ${emp.displayName}$deptStr - ${emp.expenses.size} notes - ${String.format("%.2f", empTotal)} €")
                cellStyle = styles.totalLabelStyle
            }
            sheet.addMergedRegion(CellRangeAddress(rowNum - 1, rowNum - 1, 0, 6))

            // Info véhicule si disponible
            emp.vehicle?.let { v ->
                val vRow = sheet.createRow(rowNum++)
                vRow.createCell(0).apply {
                    setCellValue("🚗 ${v.name} (${v.power?.cv ?: "N/A"}) - ${v.type.displayName}")
                    cellStyle = styles.dataStyle
                }
            }

            // Headers du tableau
            val headerRow = sheet.createRow(rowNum++)
            listOf("Date", "Type", "Montant TTC", "Montant HT", "TVA", "Note", "Photo").forEachIndexed { i, h ->
                headerRow.createCell(i).apply {
                    setCellValue(h)
                    cellStyle = styles.headerStyle
                }
            }

            // Dépenses de ce collaborateur
            emp.expenses.sortedBy { it.date }.forEach { expense ->
                val row = sheet.createRow(rowNum++)
                val tva = expense.amount - (expense.amountHT ?: expense.amount)
                row.createCell(0).apply { setCellValue(expense.date); cellStyle = styles.dataStyle }
                row.createCell(1).apply { setCellValue(expense.getExpenseTypeLabel()); cellStyle = styles.dataStyle }
                row.createCell(2).apply { setCellValue(expense.amount); cellStyle = styles.currencyStyle }
                row.createCell(3).apply { setCellValue(expense.amountHT ?: 0.0); cellStyle = styles.currencyStyle }
                row.createCell(4).apply { setCellValue(tva); cellStyle = styles.currencyStyle }
                row.createCell(5).apply { setCellValue(expense.note.ifEmpty { "-" }); cellStyle = styles.dataStyle }
                row.createCell(6).apply { setCellValue(if (expense.photoUri != null) "✓" else "-"); cellStyle = styles.dataStyle }
            }

            // Sous-total collaborateur
            val subTotalRow = sheet.createRow(rowNum++)
            subTotalRow.createCell(0).apply { setCellValue("Sous-total ${emp.displayName}"); cellStyle = styles.totalLabelStyle }
            subTotalRow.createCell(2).apply { setCellValue(empTotal); cellStyle = styles.totalValueStyle }
            subTotalRow.createCell(6).apply { setCellValue("$expWithPhoto photo(s)"); cellStyle = styles.dataStyle }

            rowNum++ // Empty row between employees
        }

        // Total général
        val totalExpenses = data.employees.flatMap { it.expenses }.sumOf { it.amount }
        val totalPhotos = data.employees.flatMap { it.expenses }.count { it.photoUri != null }
        rowNum++
        val totalRow = sheet.createRow(rowNum)
        totalRow.createCell(0).apply { setCellValue("TOTAL GÉNÉRAL"); cellStyle = styles.totalLabelStyle }
        totalRow.createCell(2).apply { setCellValue(totalExpenses); cellStyle = styles.totalValueStyle }
        totalRow.createCell(6).apply { setCellValue("$totalPhotos photo(s)"); cellStyle = styles.dataStyle }

        // Set fixed column widths
        val proExpenseWidths = listOf(12, 18, 14, 14, 10, 35, 10)
        proExpenseWidths.forEachIndexed { index, width ->
            sheet.setColumnWidth(index, width * 256)
        }
    }

    /**
     * Format SIRET with spaces: XXX XXX XXX XXXXX
     */
    private fun formatSiret(siret: String): String {
        if (siret.length != 14) return siret
        return "${siret.substring(0, 3)} ${siret.substring(3, 6)} ${siret.substring(6, 9)} ${siret.substring(9)}"
    }

    /**
     * Share the exported file
     */
    fun shareFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = when {
                    file.name.endsWith(".csv") -> "text/csv"
                    file.name.endsWith(".pdf") -> "application/pdf"
                    file.name.endsWith(".xlsx") -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    else -> "*/*"
                }
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "Partager la facture")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)

            MotiumApplication.logger.i("File shared successfully: ${file.name}", "ExportManager")

        } catch (e: Exception) {
            MotiumApplication.logger.e("Failed to share file: ${e.message}", "ExportManager", e)
        }
    }
}
