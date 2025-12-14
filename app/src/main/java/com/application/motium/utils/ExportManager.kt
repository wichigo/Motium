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
    val includeExpenses: Boolean
)

/**
 * Données d'un employé pour l'export Pro
 */
data class EmployeeExportData(
    val userId: String,
    val displayName: String,
    val email: String,
    val trips: List<DomainTrip>,
    val expenses: List<Expense>,
    val vehicle: Vehicle?
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
        if (vehicleIds.isEmpty()) return emptyMap()

        val vehicles = vehicleRepository.getAllVehiclesForUser(userId)
        return vehicles.associateBy { it.id }
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
            // Load expenses and vehicles
            val tripsWithExpenses: List<TripWithExpenses>
            val vehiclesMap: Map<String, Vehicle>

            runBlocking {
                tripsWithExpenses = loadExpensesForTrips(trips)
                val userId = trips.firstOrNull()?.userId ?: ""
                vehiclesMap = if (userId.isNotBlank()) loadVehiclesForTrips(trips, userId) else emptyMap()
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

                // Détail selon le mode
                if (expenseMode == "trips_only" || expenseMode == "trips_with_expenses") {
                    appendLine("DÉTAIL DES TRAJETS")
                    if (expenseMode == "trips_only") {
                        appendLine("Date,Heure,Départ,Arrivée,Distance (km),Indemnités (€)")
                    } else {
                        appendLine("Date,Heure,Départ,Arrivée,Distance (km),Indemnités (€),Notes de frais (€)")
                    }

                    tripsWithExpenses.sortedBy { it.trip.startTime }.forEach { tripWithExpenses ->
                        val trip = tripWithExpenses.trip
                        val tripDate = dateFormat.format(Date(trip.startTime))
                        val tripTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(trip.startTime))
                        val distanceKm = trip.totalDistance / 1000.0
                        val indemnity = calculateTripIndemnity(trip, vehiclesMap)
                        val startAddr = trip.startAddress?.replace(",", " ") ?: "Non géocodé"
                        val endAddr = trip.endAddress?.replace(",", " ") ?: "Non géocodé"

                        if (expenseMode == "trips_only") {
                            appendLine("$tripDate,$tripTime,\"$startAddr\",\"$endAddr\",${String.format("%.2f", distanceKm)},${String.format("%.2f", indemnity)}")
                        } else {
                            val expensesTotal = tripWithExpenses.expenses.sumOf { it.amount }
                            appendLine("$tripDate,$tripTime,\"$startAddr\",\"$endAddr\",${String.format("%.2f", distanceKm)},${String.format("%.2f", indemnity)},${String.format("%.2f", expensesTotal)}")
                        }
                    }

                    appendLine()
                }

                // Détail des notes de frais
                if (expenseMode != "trips_only" && tripsWithExpenses.any { it.expenses.isNotEmpty() }) {
                    appendLine("DÉTAIL DES NOTES DE FRAIS")
                    appendLine("Date,Type,Montant (€),Note")

                    tripsWithExpenses.sortedBy { it.trip.startTime }.forEach { tripWithExpenses ->
                        tripWithExpenses.expenses.forEach { expense ->
                            val expenseDate = dateFormat.format(Date(tripWithExpenses.trip.startTime))
                            val note = expense.note.replace(",", " ")
                            appendLine("$expenseDate,${expense.getExpenseTypeLabel()},${String.format("%.2f", expense.amount)},\"$note\"")
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
            // Load expenses and vehicles
            val tripsWithExpenses: List<TripWithExpenses>
            val vehiclesMap: Map<String, Vehicle>

            runBlocking {
                tripsWithExpenses = loadExpensesForTrips(trips)
                val userId = trips.firstOrNull()?.userId ?: ""
                vehiclesMap = if (userId.isNotBlank()) loadVehiclesForTrips(trips, userId) else emptyMap()
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

            // Détail des trajets (si trips_only ou trips_with_expenses)
            if (expenseMode == "trips_only" || expenseMode == "trips_with_expenses") {
                addPdfTripsTable(document, tripsWithExpenses, expenseMode)
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
                val totalIndemnities = trips.sumOf { calculateTripIndemnity(it, vehiclesCache) }
                val avgRate = getAverageRate(trips, vehiclesCache)

                addSummaryRow(summaryTable, "Nombre de trajets", trips.size.toString())
                addSummaryRow(summaryTable, "Distance totale", "${String.format("%.2f", totalKm)} km")
                addSummaryRow(summaryTable, "Indemnités kilométriques (barème progressif)", "${String.format("%.2f", totalIndemnities)} €")
                addSummaryRow(summaryTable, "Taux moyen", "${String.format("%.3f", avgRate)} €/km")

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
                val totalIndemnities = trips.sumOf { calculateTripIndemnity(it, vehiclesCache) }
                val totalExpenses = tripsWithExpenses.flatMap { it.expenses }.sumOf { it.amount }
                val grandTotal = totalIndemnities + totalExpenses
                val avgRate = getAverageRate(trips, vehiclesCache)

                addSummaryRow(summaryTable, "Nombre de trajets", trips.size.toString())
                addSummaryRow(summaryTable, "Distance totale", "${String.format("%.2f", totalKm)} km")
                addSummaryRow(summaryTable, "Indemnités kilométriques (barème progressif)", "${String.format("%.2f", totalIndemnities)} €")
                addSummaryRow(summaryTable, "Taux moyen", "${String.format("%.3f", avgRate)} €/km")
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
            // Load expenses and vehicles
            val tripsWithExpenses: List<TripWithExpenses>
            val vehiclesMap: Map<String, Vehicle>

            runBlocking {
                tripsWithExpenses = loadExpensesForTrips(trips)
                val userId = trips.firstOrNull()?.userId ?: ""
                vehiclesMap = if (userId.isNotBlank()) loadVehiclesForTrips(trips, userId) else emptyMap()
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
                createExpensesSheet(workbook, tripsWithExpenses, styles)
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

        // Headers
        val headerRow = sheet.createRow(rowNum++)
        val headers = if (expenseMode == "trips_only") {
            listOf("Date", "Heure", "Départ", "Arrivée", "Distance (km)", "Indemnités (€)")
        } else {
            listOf("Date", "Heure", "Départ", "Arrivée", "Distance (km)", "Indemnités (€)", "Frais (€)")
        }

        headers.forEachIndexed { index, header ->
            val cell = headerRow.createCell(index)
            cell.setCellValue(header)
            cell.cellStyle = styles.headerStyle
        }

        // Data rows
        tripsWithExpenses.sortedBy { it.trip.startTime }.forEach { tripWithExpenses ->
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

        // Auto-size columns
        for (i in headers.indices) {
            sheet.autoSizeColumn(i)
        }
    }

    private fun createExpensesSheet(
        workbook: XSSFWorkbook,
        tripsWithExpenses: List<TripWithExpenses>,
        styles: ExcelStyles
    ) {
        val sheet = workbook.createSheet("Notes de Frais")
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        var rowNum = 0

        // Headers
        val headerRow = sheet.createRow(rowNum++)
        val headers = listOf("Date", "Type", "Montant TTC (€)", "Montant HT (€)", "Note")

        headers.forEachIndexed { index, header ->
            val cell = headerRow.createCell(index)
            cell.setCellValue(header)
            cell.cellStyle = styles.headerStyle
        }

        // Data rows
        tripsWithExpenses.sortedBy { it.trip.startTime }.forEach { tripWithExpenses ->
            tripWithExpenses.expenses.forEach { expense ->
                val row = sheet.createRow(rowNum++)

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
            }
        }

        // Auto-size columns
        for (i in headers.indices) {
            sheet.autoSizeColumn(i)
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

                // Totaux généraux
                val allTrips = data.employees.flatMap { it.trips }
                val allExpenses = data.employees.flatMap { it.expenses }
                val totalKm = allTrips.sumOf { it.distanceKm }
                val totalIndemnities = allTrips.sumOf { it.reimbursementAmount ?: (it.distanceKm * DEFAULT_MILEAGE_RATE) }
                val totalExpenses = allExpenses.sumOf { it.amount }

                // Détail par employé
                data.employees.forEachIndexed { index, employee ->
                    appendLine("=" .repeat(60))
                    appendLine("COLLABORATEUR ${index + 1}: ${employee.displayName}")
                    appendLine("=" .repeat(60))
                    appendLine("Email;${employee.email}")
                    employee.vehicle?.let { v ->
                        val powerStr = v.power?.cv ?: "N/A"
                        appendLine("Véhicule;${v.name} ($powerStr) - ${v.type.displayName}")
                    }
                    appendLine()

                    // Trajets de l'employé
                    if (employee.trips.isNotEmpty()) {
                        appendLine("TRAJETS")
                        appendLine("Date;Heure;Départ;Arrivée;Distance (km);Véhicule (CV);Indemnités (€)")

                        employee.trips.sortedBy { it.startTime }.forEach { trip ->
                            val tripDate = dateFormat.format(Date(trip.startTime.toEpochMilliseconds()))
                            val tripTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(trip.startTime.toEpochMilliseconds()))
                            val distanceKm = trip.distanceKm
                            val indemnity = trip.reimbursementAmount ?: (distanceKm * DEFAULT_MILEAGE_RATE)
                            val startAddr = trip.startAddress?.replace(";", ",") ?: "Non géocodé"
                            val endAddr = trip.endAddress?.replace(";", ",") ?: "Non géocodé"
                            val vehicleCv = employee.vehicle?.power?.cv ?: "N/A"

                            appendLine("$tripDate;$tripTime;$startAddr;$endAddr;${String.format("%.2f", distanceKm)};$vehicleCv;${String.format("%.2f", indemnity)}")
                        }

                        // Sous-total employé
                        val empTotalKm = employee.trips.sumOf { it.distanceKm }
                        val empTotalIndemnities = employee.trips.sumOf { it.reimbursementAmount ?: (it.distanceKm * DEFAULT_MILEAGE_RATE) }
                        appendLine("SOUS-TOTAL;${employee.trips.size} trajets;;${String.format("%.2f", empTotalKm)} km;;${String.format("%.2f", empTotalIndemnities)} €")
                        appendLine()
                    }

                    // Notes de frais de l'employé (si incluses)
                    if (data.includeExpenses && employee.expenses.isNotEmpty()) {
                        appendLine("NOTES DE FRAIS")
                        appendLine("Date;Type;Montant TTC (€);Note")

                        employee.expenses.forEach { expense ->
                            val note = expense.note.replace(";", ",")
                            appendLine("${expense.date};${expense.getExpenseTypeLabel()};${String.format("%.2f", expense.amount)};$note")
                        }

                        val empTotalExpenses = employee.expenses.sumOf { it.amount }
                        appendLine("SOUS-TOTAL FRAIS;;;${String.format("%.2f", empTotalExpenses)} €")
                        appendLine()
                    }
                }

                // Total général
                appendLine("=" .repeat(60))
                appendLine("TOTAL GÉNÉRAL")
                appendLine("=" .repeat(60))
                appendLine("Nombre de collaborateurs;${data.employees.size}")
                appendLine("Nombre total de trajets;${allTrips.size}")
                appendLine("Distance totale;${String.format("%.2f", totalKm)} km")
                appendLine("Indemnités kilométriques totales;${String.format("%.2f", totalIndemnities)} €")
                if (data.includeExpenses && totalExpenses > 0) {
                    appendLine("Notes de frais totales;${String.format("%.2f", totalExpenses)} €")
                    appendLine("GRAND TOTAL;${String.format("%.2f", totalIndemnities + totalExpenses)} €")
                }
                appendLine()

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
                addProEmployeeSection(document, employee, index + 1, data.includeExpenses)
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
        val totalKm = allTrips.sumOf { it.distanceKm }
        val totalIndemnities = allTrips.sumOf { it.reimbursementAmount ?: (it.distanceKm * DEFAULT_MILEAGE_RATE) }
        val totalExpenses = allExpenses.sumOf { it.amount }

        document.add(Paragraph("📊 RÉSUMÉ GÉNÉRAL")
            .setFontSize(14f)
            .setBold()
            .setFontColor(MOTIUM_PRIMARY)
            .setMarginBottom(10f))

        val summaryTable = PdfTable(UnitValue.createPercentArray(floatArrayOf(60f, 40f)))
            .setWidth(UnitValue.createPercentValue(100f))
            .setMarginBottom(20f)

        addSummaryRow(summaryTable, "Nombre de collaborateurs", data.employees.size.toString())
        addSummaryRow(summaryTable, "Nombre total de trajets", allTrips.size.toString())
        addSummaryRow(summaryTable, "Distance totale", "${String.format("%.2f", totalKm)} km")
        addSummaryRow(summaryTable, "Indemnités kilométriques", "${String.format("%.2f", totalIndemnities)} €")

        if (data.includeExpenses && totalExpenses > 0) {
            addSummaryRow(summaryTable, "Notes de frais", "${String.format("%.2f", totalExpenses)} €")

            summaryTable.addCell(
                PdfCell().add(Paragraph("TOTAL GÉNÉRAL").setBold())
                    .setBackgroundColor(MOTIUM_GREEN)
                    .setFontColor(com.itextpdf.kernel.colors.ColorConstants.WHITE)
                    .setPadding(10f)
                    .setBorder(null)
            )
            summaryTable.addCell(
                PdfCell().add(Paragraph("${String.format("%.2f", totalIndemnities + totalExpenses)} €").setBold())
                    .setBackgroundColor(MOTIUM_GREEN)
                    .setFontColor(com.itextpdf.kernel.colors.ColorConstants.WHITE)
                    .setTextAlignment(TextAlignment.RIGHT)
                    .setPadding(10f)
                    .setBorder(null)
            )
        } else {
            summaryTable.addCell(
                PdfCell().add(Paragraph("TOTAL").setBold())
                    .setBackgroundColor(MOTIUM_GREEN)
                    .setFontColor(com.itextpdf.kernel.colors.ColorConstants.WHITE)
                    .setPadding(10f)
                    .setBorder(null)
            )
            summaryTable.addCell(
                PdfCell().add(Paragraph("${String.format("%.2f", totalIndemnities)} €").setBold())
                    .setBackgroundColor(MOTIUM_GREEN)
                    .setFontColor(com.itextpdf.kernel.colors.ColorConstants.WHITE)
                    .setTextAlignment(TextAlignment.RIGHT)
                    .setPadding(10f)
                    .setBorder(null)
            )
        }

        document.add(summaryTable)
    }

    private fun addProEmployeeSection(
        document: Document,
        employee: EmployeeExportData,
        index: Int,
        includeExpenses: Boolean
    ) {
        // En-tête employé
        val employeeHeader = PdfTable(UnitValue.createPercentArray(floatArrayOf(100f)))
            .setWidth(UnitValue.createPercentValue(100f))
            .setMarginTop(15f)
            .setMarginBottom(10f)

        val headerCell = PdfCell()
            .setBackgroundColor(MOTIUM_PRIMARY)
            .setPadding(12f)
            .setBorder(null)

        headerCell.add(Paragraph("👤 COLLABORATEUR $index: ${employee.displayName}")
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

        // Tableau des trajets
        if (employee.trips.isNotEmpty()) {
            val tripsTable = PdfTable(UnitValue.createPercentArray(floatArrayOf(12f, 8f, 22f, 22f, 10f, 10f, 12f)))
                .setWidth(UnitValue.createPercentValue(100f))
                .setFontSize(8f)

            addTableHeader(tripsTable, "Date")
            addTableHeader(tripsTable, "Heure")
            addTableHeader(tripsTable, "Départ")
            addTableHeader(tripsTable, "Arrivée")
            addTableHeader(tripsTable, "Km")
            addTableHeader(tripsTable, "CV")
            addTableHeader(tripsTable, "Indem.")

            val dateFormat = SimpleDateFormat("dd/MM/yy", Locale.getDefault())
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

            employee.trips.sortedBy { it.startTime }.forEach { trip ->
                val indemnity = trip.reimbursementAmount ?: (trip.distanceKm * DEFAULT_MILEAGE_RATE)
                val vehicleCv = employee.vehicle?.power?.cv ?: "N/A"

                addTableCell(tripsTable, dateFormat.format(Date(trip.startTime.toEpochMilliseconds())))
                addTableCell(tripsTable, timeFormat.format(Date(trip.startTime.toEpochMilliseconds())))
                addTableCell(tripsTable, trip.startAddress?.take(28) ?: "N/A")
                addTableCell(tripsTable, trip.endAddress?.take(28) ?: "N/A")
                addTableCell(tripsTable, String.format("%.1f", trip.distanceKm))
                addTableCell(tripsTable, vehicleCv)
                addTableCell(tripsTable, String.format("%.2f€", indemnity))
            }

            document.add(tripsTable)

            // Sous-total employé
            val empTotalKm = employee.trips.sumOf { it.distanceKm }
            val empTotalIndemnities = employee.trips.sumOf { it.reimbursementAmount ?: (it.distanceKm * DEFAULT_MILEAGE_RATE) }

            val subtotalTable = PdfTable(UnitValue.createPercentArray(floatArrayOf(50f, 50f)))
                .setWidth(UnitValue.createPercentValue(60f))
                .setMarginTop(5f)
                .setMarginBottom(10f)
                .setHorizontalAlignment(com.itextpdf.layout.properties.HorizontalAlignment.RIGHT)

            subtotalTable.addCell(
                PdfCell().add(Paragraph("Sous-total: ${employee.trips.size} trajets").setFontSize(9f))
                    .setBackgroundColor(GRAY_LIGHT)
                    .setPadding(6f)
                    .setBorder(SolidBorder(GRAY_MEDIUM, 0.5f))
            )
            subtotalTable.addCell(
                PdfCell().add(Paragraph("${String.format("%.2f", empTotalKm)} km → ${String.format("%.2f", empTotalIndemnities)} €").setFontSize(9f).setBold())
                    .setBackgroundColor(GRAY_LIGHT)
                    .setPadding(6f)
                    .setTextAlignment(TextAlignment.RIGHT)
                    .setBorder(SolidBorder(GRAY_MEDIUM, 0.5f))
            )

            document.add(subtotalTable)
        }

        // Notes de frais employé
        if (includeExpenses && employee.expenses.isNotEmpty()) {
            document.add(Paragraph("🧾 Notes de frais")
                .setFontSize(10f)
                .setBold()
                .setFontColor(GRAY_DARK)
                .setMarginTop(10f)
                .setMarginBottom(5f))

            val expensesTable = PdfTable(UnitValue.createPercentArray(floatArrayOf(15f, 25f, 15f, 45f)))
                .setWidth(UnitValue.createPercentValue(100f))
                .setFontSize(8f)

            addTableHeader(expensesTable, "Date")
            addTableHeader(expensesTable, "Type")
            addTableHeader(expensesTable, "Montant")
            addTableHeader(expensesTable, "Note")

            employee.expenses.forEach { expense ->
                addTableCell(expensesTable, expense.date)
                addTableCell(expensesTable, expense.getExpenseTypeLabel())
                addTableCell(expensesTable, String.format("%.2f€", expense.amount))
                addTableCell(expensesTable, expense.note.ifEmpty { "-" })
            }

            document.add(expensesTable)

            val empTotalExpenses = employee.expenses.sumOf { it.amount }
            document.add(Paragraph("Sous-total frais: ${String.format("%.2f", empTotalExpenses)} €")
                .setFontSize(9f)
                .setBold()
                .setTextAlignment(TextAlignment.RIGHT)
                .setMarginTop(5f))
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
        sheet.addMergedRegion(CellRangeAddress(0, 0, 0, 4))

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

        // Calculs totaux
        val allTrips = data.employees.flatMap { it.trips }
        val allExpenses = data.employees.flatMap { it.expenses }
        val totalKm = allTrips.sumOf { it.distanceKm }
        val totalIndemnities = allTrips.sumOf { it.reimbursementAmount ?: (it.distanceKm * DEFAULT_MILEAGE_RATE) }
        val totalExpenses = allExpenses.sumOf { it.amount }

        rowNum += 2
        addSummaryRow(sheet, rowNum++, "Nombre de collaborateurs", data.employees.size.toString(), styles)
        addSummaryRow(sheet, rowNum++, "Nombre total de trajets", allTrips.size.toString(), styles)
        addSummaryRow(sheet, rowNum++, "Distance totale", String.format("%.2f km", totalKm), styles)
        addSummaryRow(sheet, rowNum++, "Indemnités kilométriques", String.format("%.2f €", totalIndemnities), styles)

        if (data.includeExpenses && totalExpenses > 0) {
            addSummaryRow(sheet, rowNum++, "Notes de frais", String.format("%.2f €", totalExpenses), styles)
            rowNum++
            addTotalRow(sheet, rowNum++, "TOTAL GÉNÉRAL", totalIndemnities + totalExpenses, styles)
        } else {
            rowNum++
            addTotalRow(sheet, rowNum++, "TOTAL", totalIndemnities, styles)
        }

        // Récapitulatif par employé
        rowNum += 2
        sheet.createRow(rowNum++).createCell(0).apply {
            setCellValue("RÉCAPITULATIF PAR COLLABORATEUR")
            cellStyle = styles.headerStyle
        }
        sheet.addMergedRegion(CellRangeAddress(rowNum - 1, rowNum - 1, 0, 3))

        val headerRow = sheet.createRow(rowNum++)
        listOf("Collaborateur", "Trajets", "Km", "Indemnités").forEachIndexed { i, h ->
            headerRow.createCell(i).apply {
                setCellValue(h)
                cellStyle = styles.headerStyle
            }
        }

        data.employees.forEach { emp ->
            val empKm = emp.trips.sumOf { it.distanceKm }
            val empIndemnities = emp.trips.sumOf { it.reimbursementAmount ?: (it.distanceKm * DEFAULT_MILEAGE_RATE) }
            val row = sheet.createRow(rowNum++)
            row.createCell(0).apply { setCellValue(emp.displayName); cellStyle = styles.dataStyle }
            row.createCell(1).apply { setCellValue(emp.trips.size.toDouble()); cellStyle = styles.dataStyle }
            row.createCell(2).apply { setCellValue(empKm); cellStyle = styles.currencyStyle }
            row.createCell(3).apply { setCellValue(empIndemnities); cellStyle = styles.currencyStyle }
        }

        // Note légale
        rowNum += 2
        sheet.createRow(rowNum++).createCell(0).setCellValue("Note: Les indemnités kilométriques ne sont pas soumises à la TVA.")
        sheet.createRow(rowNum).createCell(0).setCellValue("Document généré par Motium le ${SimpleDateFormat("dd/MM/yyyy à HH:mm", Locale.getDefault()).format(Date())}")

        // Auto-size
        for (i in 0..4) sheet.setColumnWidth(i, 6000)
    }

    private fun createProEmployeesSheet(workbook: XSSFWorkbook, data: ProExportData, styles: ExcelStyles) {
        val sheet = workbook.createSheet("Trajets détaillés")
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        var rowNum = 0

        data.employees.forEachIndexed { empIndex, emp ->
            // Header employé
            val empHeaderRow = sheet.createRow(rowNum++)
            empHeaderRow.createCell(0).apply {
                setCellValue("${empIndex + 1}. ${emp.displayName} (${emp.email})")
                cellStyle = styles.totalLabelStyle
            }
            sheet.addMergedRegion(CellRangeAddress(rowNum - 1, rowNum - 1, 0, 6))

            emp.vehicle?.let { v ->
                val vRow = sheet.createRow(rowNum++)
                vRow.createCell(0).setCellValue("Véhicule: ${v.name} (${v.power?.cv ?: "N/A"}) - ${v.type.displayName}")
            }

            // Headers trajets
            val headerRow = sheet.createRow(rowNum++)
            listOf("Date", "Heure", "Départ", "Arrivée", "Km", "CV", "Indemnités").forEachIndexed { i, h ->
                headerRow.createCell(i).apply {
                    setCellValue(h)
                    cellStyle = styles.headerStyle
                }
            }

            // Trajets
            emp.trips.sortedBy { it.startTime }.forEach { trip ->
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

            // Sous-total
            val empTotalKm = emp.trips.sumOf { it.distanceKm }
            val empTotalIndemnities = emp.trips.sumOf { it.reimbursementAmount ?: (it.distanceKm * DEFAULT_MILEAGE_RATE) }
            val subtotalRow = sheet.createRow(rowNum++)
            subtotalRow.createCell(0).apply { setCellValue("SOUS-TOTAL"); cellStyle = styles.totalLabelStyle }
            subtotalRow.createCell(3).apply { setCellValue("${emp.trips.size} trajets"); cellStyle = styles.totalLabelStyle }
            subtotalRow.createCell(4).apply { setCellValue(empTotalKm); cellStyle = styles.totalValueStyle }
            subtotalRow.createCell(6).apply { setCellValue(empTotalIndemnities); cellStyle = styles.totalValueStyle }

            rowNum++ // Empty row between employees
        }

        // Auto-size columns
        for (i in 0..6) sheet.autoSizeColumn(i)
    }

    private fun createProExpensesSheet(workbook: XSSFWorkbook, data: ProExportData, styles: ExcelStyles) {
        val sheet = workbook.createSheet("Notes de frais")
        var rowNum = 0

        // Header
        val headerRow = sheet.createRow(rowNum++)
        listOf("Collaborateur", "Date", "Type", "Montant TTC", "Montant HT", "Note").forEachIndexed { i, h ->
            headerRow.createCell(i).apply {
                setCellValue(h)
                cellStyle = styles.headerStyle
            }
        }

        // Expenses by employee
        data.employees.forEach { emp ->
            emp.expenses.forEach { expense ->
                val row = sheet.createRow(rowNum++)
                row.createCell(0).apply { setCellValue(emp.displayName); cellStyle = styles.dataStyle }
                row.createCell(1).apply { setCellValue(expense.date); cellStyle = styles.dataStyle }
                row.createCell(2).apply { setCellValue(expense.getExpenseTypeLabel()); cellStyle = styles.dataStyle }
                row.createCell(3).apply { setCellValue(expense.amount); cellStyle = styles.currencyStyle }
                row.createCell(4).apply { setCellValue(expense.amountHT ?: 0.0); cellStyle = styles.currencyStyle }
                row.createCell(5).apply { setCellValue(expense.note.ifEmpty { "-" }); cellStyle = styles.dataStyle }
            }
        }

        // Total
        val totalExpenses = data.employees.flatMap { it.expenses }.sumOf { it.amount }
        rowNum++
        val totalRow = sheet.createRow(rowNum)
        totalRow.createCell(0).apply { setCellValue("TOTAL"); cellStyle = styles.totalLabelStyle }
        totalRow.createCell(3).apply { setCellValue(totalExpenses); cellStyle = styles.totalValueStyle }

        // Auto-size
        for (i in 0..5) sheet.autoSizeColumn(i)
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
