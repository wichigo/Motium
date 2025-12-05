package com.application.motium.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.application.motium.MotiumApplication
import com.application.motium.data.Trip
import com.application.motium.data.ExpenseRepository
import com.application.motium.service.SupabaseStorageService
import com.application.motium.domain.model.Expense
import com.itextpdf.kernel.colors.Color
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.borders.Border
import com.itextpdf.layout.borders.SolidBorder
import com.itextpdf.layout.element.*
import com.itextpdf.layout.properties.TextAlignment
import com.itextpdf.layout.properties.UnitValue
import kotlinx.coroutines.runBlocking
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Données combinées d'un trajet avec ses notes de frais
 */
data class TripWithExpenses(
    val trip: Trip,
    val expenses: List<Expense>
)

class ExportManager(private val context: Context) {

    companion object {
        private const val EXPORT_FOLDER = "Motium_Exports"
        private const val MILEAGE_RATE = 0.50 // €/km - Barème fiscal France

        // Couleurs Motium
        private val MOTIUM_GREEN = DeviceRgb(16, 185, 129) // #10B981
        private val MOTIUM_PRIMARY = DeviceRgb(59, 130, 246) // #3B82F6
        private val GRAY_LIGHT = DeviceRgb(243, 244, 246) // #F3F4F6
        private val GRAY_DARK = DeviceRgb(107, 114, 128) // #6B7280
    }

    private val expenseRepository = ExpenseRepository.getInstance(context)
    private val storageService = SupabaseStorageService.getInstance(context)

    /**
     * Charge les expenses pour une liste de trips en une seule requête batch (optimisé)
     */
    private suspend fun loadExpensesForTrips(trips: List<Trip>): List<TripWithExpenses> {
        if (trips.isEmpty()) {
            return emptyList()
        }

        // Charger toutes les expenses en une seule requête
        val tripIds = trips.map { it.id }
        val expensesByTrip = expenseRepository.getExpensesForTrips(tripIds).getOrNull() ?: emptyMap()

        // Mapper les trips avec leurs expenses
        return trips.map { trip ->
            val expenses = expensesByTrip[trip.id] ?: emptyList()
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
            // Load expenses
            val tripsWithExpenses = runBlocking {
                loadExpensesForTrips(trips)
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
                        val totalIndemnities = totalKm * MILEAGE_RATE

                        appendLine("RÉSUMÉ")
                        appendLine("Nombre de trajets,${trips.size}")
                        appendLine("Distance totale (km),${String.format("%.2f", totalKm)}")
                        appendLine("Indemnités kilométriques (${MILEAGE_RATE}€/km),${String.format("%.2f", totalIndemnities)}€")
                        appendLine("TOTAL,${String.format("%.2f", totalIndemnities)}€")
                        appendLine()
                    }
                    "trips_with_expenses" -> {
                        val totalKm = trips.sumOf { it.totalDistance / 1000.0 }
                        val totalIndemnities = totalKm * MILEAGE_RATE
                        val totalExpenses = tripsWithExpenses.flatMap { it.expenses }.sumOf { it.amount }
                        val grandTotal = totalIndemnities + totalExpenses

                        appendLine("RÉSUMÉ")
                        appendLine("Nombre de trajets,${trips.size}")
                        appendLine("Distance totale (km),${String.format("%.2f", totalKm)}")
                        appendLine("Indemnités kilométriques (${MILEAGE_RATE}€/km),${String.format("%.2f", totalIndemnities)}€")
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
                        val indemnity = distanceKm * MILEAGE_RATE
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
            // Load expenses
            val tripsWithExpenses = runBlocking {
                loadExpensesForTrips(trips)
            }

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

        // Logo virtuel et titre
        val title = Paragraph("MOTIUM")
            .setFontSize(28f)
            .setBold()
            .setFontColor(MOTIUM_GREEN)
            .setTextAlignment(TextAlignment.CENTER)
        document.add(title)

        val subtitle = Paragraph("Note de Frais Professionnels")
            .setFontSize(16f)
            .setFontColor(GRAY_DARK)
            .setTextAlignment(TextAlignment.CENTER)
            .setMarginBottom(20f)
        document.add(subtitle)

        // Période
        val period = Paragraph("Période du ${dateFormat.format(Date(startDate))} au ${dateFormat.format(Date(endDate))}")
            .setFontSize(12f)
            .setTextAlignment(TextAlignment.CENTER)
            .setMarginBottom(30f)
        document.add(period)

        // Ligne de séparation
        val separator = Paragraph("")
            .setBorder(SolidBorder(MOTIUM_PRIMARY, 2f))
            .setPadding(5f)
            .setMarginBottom(20f)
        document.add(separator)
    }

    private fun addPdfSummary(document: Document, trips: List<Trip>, tripsWithExpenses: List<TripWithExpenses>, expenseMode: String) {
        val summaryTitle = Paragraph("RÉSUMÉ")
            .setFontSize(14f)
            .setBold()
            .setFontColor(MOTIUM_PRIMARY)
            .setMarginBottom(10f)
        document.add(summaryTitle)

        val summaryTable = Table(UnitValue.createPercentArray(floatArrayOf(60f, 40f)))
            .setWidth(UnitValue.createPercentValue(100f))
            .setMarginBottom(20f)

        when (expenseMode) {
            "trips_only" -> {
                val totalKm = trips.sumOf { it.totalDistance / 1000.0 }
                val totalIndemnities = totalKm * MILEAGE_RATE

                addSummaryRow(summaryTable, "Nombre de trajets", trips.size.toString())
                addSummaryRow(summaryTable, "Distance totale", "${String.format("%.2f", totalKm)} km")
                addSummaryRow(summaryTable, "Indemnités kilométriques ($MILEAGE_RATE€/km)", "${String.format("%.2f", totalIndemnities)} €")

                // Total en vert
                summaryTable.addCell(
                    Cell().add(Paragraph("TOTAL").setBold())
                        .setBackgroundColor(MOTIUM_GREEN)
                        .setFontColor(com.itextpdf.kernel.colors.ColorConstants.WHITE)
                        .setPadding(8f)
                        .setBorder(null)
                )
                summaryTable.addCell(
                    Cell().add(Paragraph("${String.format("%.2f", totalIndemnities)} €").setBold())
                        .setBackgroundColor(MOTIUM_GREEN)
                        .setFontColor(com.itextpdf.kernel.colors.ColorConstants.WHITE)
                        .setTextAlignment(TextAlignment.RIGHT)
                        .setPadding(8f)
                        .setBorder(null)
                )
            }
            "trips_with_expenses" -> {
                val totalKm = trips.sumOf { it.totalDistance / 1000.0 }
                val totalIndemnities = totalKm * MILEAGE_RATE
                val totalExpenses = tripsWithExpenses.flatMap { it.expenses }.sumOf { it.amount }
                val grandTotal = totalIndemnities + totalExpenses

                addSummaryRow(summaryTable, "Nombre de trajets", trips.size.toString())
                addSummaryRow(summaryTable, "Distance totale", "${String.format("%.2f", totalKm)} km")
                addSummaryRow(summaryTable, "Indemnités kilométriques ($MILEAGE_RATE€/km)", "${String.format("%.2f", totalIndemnities)} €")
                addSummaryRow(summaryTable, "Frais annexes", "${String.format("%.2f", totalExpenses)} €")

                // Total en vert
                summaryTable.addCell(
                    Cell().add(Paragraph("TOTAL GÉNÉRAL").setBold())
                        .setBackgroundColor(MOTIUM_GREEN)
                        .setFontColor(com.itextpdf.kernel.colors.ColorConstants.WHITE)
                        .setPadding(8f)
                        .setBorder(null)
                )
                summaryTable.addCell(
                    Cell().add(Paragraph("${String.format("%.2f", grandTotal)} €").setBold())
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
                    Cell().add(Paragraph("TOTAL").setBold())
                        .setBackgroundColor(MOTIUM_GREEN)
                        .setFontColor(com.itextpdf.kernel.colors.ColorConstants.WHITE)
                        .setPadding(8f)
                        .setBorder(null)
                )
                summaryTable.addCell(
                    Cell().add(Paragraph("${String.format("%.2f", totalExpenses)} €").setBold())
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

    private fun addSummaryRow(table: Table, label: String, value: String) {
        table.addCell(
            Cell().add(Paragraph(label))
                .setBackgroundColor(GRAY_LIGHT)
                .setPadding(5f)
                .setBorder(null)
        )
        table.addCell(
            Cell().add(Paragraph(value))
                .setTextAlignment(TextAlignment.RIGHT)
                .setPadding(5f)
                .setBorder(null)
        )
    }

    private fun addPdfTripsTable(document: Document, tripsWithExpenses: List<TripWithExpenses>, expenseMode: String) {
        val tripsTitle = Paragraph("DÉTAIL DES TRAJETS")
            .setFontSize(14f)
            .setBold()
            .setFontColor(MOTIUM_PRIMARY)
            .setMarginTop(20f)
            .setMarginBottom(10f)
        document.add(tripsTitle)

        // Adapter les colonnes selon le mode
        val tripsTable = if (expenseMode == "trips_only") {
            Table(UnitValue.createPercentArray(floatArrayOf(15f, 10f, 28f, 28f, 10f, 9f)))
                .setWidth(UnitValue.createPercentValue(100f))
                .setFontSize(9f)
        } else {
            Table(UnitValue.createPercentArray(floatArrayOf(12f, 8f, 25f, 25f, 10f, 10f, 10f)))
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
            val indemnity = distanceKm * MILEAGE_RATE

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
        val expensesTitle = Paragraph("DÉTAIL DES NOTES DE FRAIS")
            .setFontSize(14f)
            .setBold()
            .setFontColor(MOTIUM_PRIMARY)
            .setMarginTop(20f)
            .setMarginBottom(10f)
        document.add(expensesTitle)

        if (includePhotos) {
            // Format avec photos - Une note de frais par section
            val dateFormat = SimpleDateFormat("dd/MM/yy", Locale.getDefault())

            tripsWithExpenses.sortedBy { it.trip.startTime }.forEach { tripWithExpenses ->
                tripWithExpenses.expenses.forEach { expense ->
                    // Tableau pour chaque note de frais
                    val expenseTable = Table(UnitValue.createPercentArray(floatArrayOf(25f, 75f)))
                        .setWidth(UnitValue.createPercentValue(100f))
                        .setFontSize(9f)
                        .setMarginBottom(10f)

                    // Date
                    expenseTable.addCell(
                        Cell().add(Paragraph("Date").setBold())
                            .setBackgroundColor(GRAY_LIGHT)
                            .setPadding(5f)
                            .setBorder(null)
                    )
                    expenseTable.addCell(
                        Cell().add(Paragraph(dateFormat.format(Date(tripWithExpenses.trip.startTime))))
                            .setPadding(5f)
                            .setBorder(Border.NO_BORDER)
                    )

                    // Type
                    expenseTable.addCell(
                        Cell().add(Paragraph("Type").setBold())
                            .setBackgroundColor(GRAY_LIGHT)
                            .setPadding(5f)
                            .setBorder(null)
                    )
                    expenseTable.addCell(
                        Cell().add(Paragraph(expense.getExpenseTypeLabel()))
                            .setPadding(5f)
                            .setBorder(Border.NO_BORDER)
                    )

                    // Montant
                    expenseTable.addCell(
                        Cell().add(Paragraph("Montant").setBold())
                            .setBackgroundColor(GRAY_LIGHT)
                            .setPadding(5f)
                            .setBorder(null)
                    )
                    expenseTable.addCell(
                        Cell().add(Paragraph(String.format("%.2f€", expense.amount)))
                            .setPadding(5f)
                            .setBorder(Border.NO_BORDER)
                    )

                    // Note
                    expenseTable.addCell(
                        Cell().add(Paragraph("Note").setBold())
                            .setBackgroundColor(GRAY_LIGHT)
                            .setPadding(5f)
                            .setBorder(null)
                    )
                    expenseTable.addCell(
                        Cell().add(Paragraph(expense.note.ifEmpty { "-" }))
                            .setPadding(5f)
                            .setBorder(Border.NO_BORDER)
                    )

                    document.add(expenseTable)

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
                                    // C'est une URL Supabase, il faut la télécharger
                                    runBlocking {
                                        try {
                                            MotiumApplication.logger.i("Downloading photo from Supabase: ${expense.photoUri}", "ExportManager")
                                            storageService.downloadReceiptPhoto(expense.photoUri).getOrNull()
                                        } catch (e: Exception) {
                                            MotiumApplication.logger.e("Error downloading photo from Supabase: ${e.message}", "ExportManager", e)
                                            null
                                        }
                                    }
                                }
                                else -> null
                            }

                            if (imageData != null) {
                                val imageFile = com.itextpdf.io.image.ImageDataFactory.create(imageData)
                                val image = com.itextpdf.layout.element.Image(imageFile)
                                    .setWidth(UnitValue.createPercentValue(40f))
                                    .setMarginTop(5f)
                                    .setMarginBottom(15f)
                                document.add(image)
                            } else {
                                document.add(Paragraph("Photo non disponible pour l'export")
                                    .setFontSize(8f)
                                    .setFontColor(GRAY_DARK)
                                    .setMarginBottom(15f))
                            }
                        } catch (e: Exception) {
                            MotiumApplication.logger.e("Failed to add photo to PDF: ${e.message}", "ExportManager", e)
                            document.add(Paragraph("Erreur lors du chargement de la photo: ${e.message}")
                                .setFontSize(8f)
                                .setFontColor(GRAY_DARK)
                                .setMarginBottom(15f))
                        }
                    }
                }
            }
        } else {
            // Format tabulaire sans photos
            val expensesTable = Table(UnitValue.createPercentArray(floatArrayOf(15f, 20f, 15f, 50f)))
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

    private fun addTableHeader(table: Table, text: String) {
        table.addHeaderCell(
            Cell().add(Paragraph(text).setBold())
                .setBackgroundColor(MOTIUM_PRIMARY)
                .setFontColor(com.itextpdf.kernel.colors.ColorConstants.WHITE)
                .setPadding(5f)
                .setTextAlignment(TextAlignment.CENTER)
                .setBorder(null)
        )
    }

    private fun addTableCell(table: Table, text: String) {
        table.addCell(
            Cell().add(Paragraph(text))
                .setPadding(5f)
                .setBorder(Border.NO_BORDER)
        )
    }

    private fun addPdfFooter(document: Document) {
        document.add(Paragraph("\n"))

        val footer = Paragraph("Document généré automatiquement par Motium le ${SimpleDateFormat("dd/MM/yyyy à HH:mm", Locale.getDefault()).format(Date())}")
            .setFontSize(8f)
            .setFontColor(GRAY_DARK)
            .setTextAlignment(TextAlignment.CENTER)
        document.add(footer)
    }

    /**
     * Export trips to Excel format - TODO
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
        onError("Excel export coming soon! Please use CSV or PDF format.")
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
