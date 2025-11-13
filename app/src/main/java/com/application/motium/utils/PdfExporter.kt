package com.application.motium.utils

import android.content.Context
import android.os.Environment
import com.application.motium.domain.model.Trip
import com.application.motium.domain.model.TripType
import com.application.motium.domain.model.User
import com.itextpdf.kernel.colors.ColorConstants
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.*
import com.itextpdf.layout.properties.TextAlignment
import com.itextpdf.layout.properties.UnitValue
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toLocalDateTime
import java.io.File
import java.io.FileOutputStream
import java.time.format.DateTimeFormatter
import java.util.*

object PdfExporter {

    fun exportTripsAsPdf(
        context: Context,
        trips: List<Trip>,
        user: User,
        startDate: Instant,
        endDate: Instant,
        vehicleFilter: String? = null,
        tripTypeFilter: TripType? = null
    ): File? {
        try {
            val fileName = generateFileName(startDate, endDate)
            val file = createPdfFile(context, fileName)

            val pdfWriter = PdfWriter(FileOutputStream(file))
            val pdfDocument = PdfDocument(pdfWriter)
            val document = Document(pdfDocument)

            // Add header
            addHeader(document, user, startDate, endDate)

            // Add summary
            addSummary(document, trips)

            // Add filters info if any
            if (vehicleFilter != null || tripTypeFilter != null) {
                addFiltersInfo(document, vehicleFilter, tripTypeFilter)
            }

            // Add trip list
            addTripList(document, trips)

            // Add footer
            addFooter(document)

            document.close()
            return file

        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun generateFileName(startDate: Instant, endDate: Instant): String {
        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        val startStr = startDate.toLocalDateTime(TimeZone.currentSystemDefault()).toJavaLocalDateTime().format(formatter)
        val endStr = endDate.toLocalDateTime(TimeZone.currentSystemDefault()).toJavaLocalDateTime().format(formatter)
        return "motium_trajets_${startStr}_${endStr}.pdf"
    }

    private fun createPdfFile(context: Context, fileName: String): File {
        val documentsDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "Motium")
        if (!documentsDir.exists()) {
            documentsDir.mkdirs()
        }
        return File(documentsDir, fileName)
    }

    private fun addHeader(document: Document, user: User, startDate: Instant, endDate: Instant) {
        // Title
        val title = Paragraph("Export des Trajets Motium")
            .setFontSize(20f)
            .setBold()
            .setTextAlignment(TextAlignment.CENTER)
            .setMarginBottom(20f)

        document.add(title)

        // User info and period
        val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        val startStr = startDate.toLocalDateTime(TimeZone.currentSystemDefault()).toJavaLocalDateTime().format(dateFormatter)
        val endStr = endDate.toLocalDateTime(TimeZone.currentSystemDefault()).toJavaLocalDateTime().format(dateFormatter)

        val headerTable = Table(UnitValue.createPercentArray(floatArrayOf(50f, 50f)))
            .setWidth(UnitValue.createPercentValue(100f))

        headerTable.addCell(
            Cell().add(Paragraph("Utilisateur: ${user.name}"))
                .setBorder(null)
        )
        headerTable.addCell(
            Cell().add(Paragraph("Période: $startStr - $endStr"))
                .setBorder(null)
                .setTextAlignment(TextAlignment.RIGHT)
        )

        headerTable.addCell(
            Cell().add(Paragraph("Email: ${user.email}"))
                .setBorder(null)
        )
        headerTable.addCell(
            Cell().add(Paragraph("Rôle: ${user.role.displayName}"))
                .setBorder(null)
                .setTextAlignment(TextAlignment.RIGHT)
        )

        document.add(headerTable)
        document.add(Paragraph("\n"))
    }

    private fun addSummary(document: Document, trips: List<Trip>) {
        val summary = TripCalculator.calculateTripSummary(trips)

        val summaryTitle = Paragraph("Résumé de la Période")
            .setFontSize(16f)
            .setBold()
            .setMarginBottom(10f)

        document.add(summaryTitle)

        val summaryTable = Table(UnitValue.createPercentArray(floatArrayOf(25f, 25f, 25f, 25f)))
            .setWidth(UnitValue.createPercentValue(100f))

        // Header row
        summaryTable.addHeaderCell(createHeaderCell("Trajets"))
        summaryTable.addHeaderCell(createHeaderCell("Distance (km)"))
        summaryTable.addHeaderCell(createHeaderCell("Indemnités (€)"))
        summaryTable.addHeaderCell(createHeaderCell("Durée Moy."))

        // Data row
        val avgDurationHours = summary.averageDurationMs / (1000.0 * 60.0 * 60.0)
        summaryTable.addCell(createDataCell(summary.totalTrips.toString()))
        summaryTable.addCell(createDataCell(String.format("%.1f", summary.totalDistanceKm)))
        summaryTable.addCell(createDataCell(String.format("%.2f", summary.totalCost)))
        summaryTable.addCell(createDataCell(String.format("%.1fh", avgDurationHours)))

        document.add(summaryTable)

        // Professional vs Personal breakdown
        if (summary.professionalTrips > 0 || summary.personalTrips > 0) {
            document.add(Paragraph("\nRépartition par Type de Trajet").setBold())

            val breakdownTable = Table(UnitValue.createPercentArray(floatArrayOf(33f, 33f, 34f)))
                .setWidth(UnitValue.createPercentValue(100f))

            breakdownTable.addHeaderCell(createHeaderCell("Type"))
            breakdownTable.addHeaderCell(createHeaderCell("Trajets"))
            breakdownTable.addHeaderCell(createHeaderCell("Distance (km)"))

            breakdownTable.addCell(createDataCell("Professionnel"))
            breakdownTable.addCell(createDataCell(summary.professionalTrips.toString()))
            breakdownTable.addCell(createDataCell(String.format("%.1f", summary.professionalDistanceKm)))

            breakdownTable.addCell(createDataCell("Personnel"))
            breakdownTable.addCell(createDataCell(summary.personalTrips.toString()))
            breakdownTable.addCell(createDataCell(String.format("%.1f", summary.personalDistanceKm)))

            document.add(breakdownTable)
        }

        document.add(Paragraph("\n"))
    }

    private fun addFiltersInfo(document: Document, vehicleFilter: String?, tripTypeFilter: TripType?) {
        val filtersTitle = Paragraph("Filtres Appliqués")
            .setFontSize(14f)
            .setBold()

        document.add(filtersTitle)

        vehicleFilter?.let {
            document.add(Paragraph("Véhicule: $it"))
        }

        tripTypeFilter?.let {
            document.add(Paragraph("Type de trajet: ${it.displayName}"))
        }

        document.add(Paragraph("\n"))
    }

    private fun addTripList(document: Document, trips: List<Trip>) {
        val tripsTitle = Paragraph("Détail des Trajets")
            .setFontSize(16f)
            .setBold()
            .setMarginBottom(10f)

        document.add(tripsTitle)

        if (trips.isEmpty()) {
            document.add(Paragraph("Aucun trajet pour cette période."))
            return
        }

        val tripsTable = Table(UnitValue.createPercentArray(floatArrayOf(12f, 12f, 30f, 12f, 12f, 10f, 12f)))
            .setWidth(UnitValue.createPercentValue(100f))

        // Headers
        tripsTable.addHeaderCell(createHeaderCell("Date"))
        tripsTable.addHeaderCell(createHeaderCell("Heure"))
        tripsTable.addHeaderCell(createHeaderCell("Trajet"))
        tripsTable.addHeaderCell(createHeaderCell("Distance"))
        tripsTable.addHeaderCell(createHeaderCell("Durée"))
        tripsTable.addHeaderCell(createHeaderCell("Type"))
        tripsTable.addHeaderCell(createHeaderCell("Coût"))

        // Trip data
        val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        trips.sortedBy { it.startTime }.forEach { trip ->
            val startDateTime = trip.startTime.toLocalDateTime(TimeZone.currentSystemDefault()).toJavaLocalDateTime()
            val durationMinutes = trip.durationMs / (1000 * 60)

            tripsTable.addCell(createDataCell(startDateTime.format(dateFormatter)))
            tripsTable.addCell(createDataCell(startDateTime.format(timeFormatter)))

            // Route - truncate if too long
            val route = buildString {
                trip.startAddress?.let { append(it.take(20)) }
                if (trip.startAddress != null && trip.endAddress != null) append(" → ")
                trip.endAddress?.let { append(it.take(20)) }
            }
            tripsTable.addCell(createDataCell(route.ifEmpty { "Trajet non géocodé" }))

            tripsTable.addCell(createDataCell(String.format("%.1f km", trip.distanceKm)))
            tripsTable.addCell(createDataCell("${durationMinutes}min"))
            tripsTable.addCell(createDataCell(if (trip.type == TripType.PROFESSIONAL) "Pro" else "Perso"))
            tripsTable.addCell(createDataCell(String.format("%.2f€", trip.cost)))
        }

        document.add(tripsTable)
    }

    private fun addFooter(document: Document) {
        document.add(Paragraph("\n"))

        val footer = Paragraph("Document généré automatiquement par Motium")
            .setFontSize(10f)
            .setTextAlignment(TextAlignment.CENTER)
            .setFontColor(ColorConstants.GRAY)

        val generationDate = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
            .format(java.time.LocalDateTime.now())

        document.add(footer)
        document.add(
            Paragraph("Date de génération: $generationDate")
                .setFontSize(10f)
                .setTextAlignment(TextAlignment.CENTER)
                .setFontColor(ColorConstants.GRAY)
        )
    }

    private fun createHeaderCell(content: String): Cell {
        return Cell().add(Paragraph(content))
            .setBold()
            .setBackgroundColor(ColorConstants.LIGHT_GRAY)
            .setTextAlignment(TextAlignment.CENTER)
    }

    private fun createDataCell(content: String): Cell {
        return Cell().add(Paragraph(content))
            .setTextAlignment(TextAlignment.CENTER)
    }
}