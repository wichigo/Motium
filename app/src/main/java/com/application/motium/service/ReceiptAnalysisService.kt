package com.application.motium.service

import android.content.Context
import android.net.Uri
import com.application.motium.MotiumApplication
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.regex.Pattern

@Serializable
data class ReceiptAnalysisResult(
    val amountTTC: Double? = null,
    val amountHT: Double? = null,
    val confidence: String = "low"
)

class ReceiptAnalysisService(private val context: Context) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    companion object {
        @Volatile
        private var instance: ReceiptAnalysisService? = null

        fun getInstance(context: Context): ReceiptAnalysisService {
            return instance ?: synchronized(this) {
                instance ?: ReceiptAnalysisService(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Analyze a receipt image and extract amounts using ML Kit (on-device, free)
     */
    suspend fun analyzeReceipt(imageUri: Uri): Result<ReceiptAnalysisResult> = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("🔍 Analyzing receipt image with ML Kit: $imageUri", "ReceiptAnalysis")

            // Create InputImage from URI
            val image = InputImage.fromFilePath(context, imageUri)

            // Perform OCR using ML Kit
            val visionText = recognizer.process(image).await()

            // Extract all text
            val fullText = visionText.text
            MotiumApplication.logger.d("📝 OCR Text extracted: $fullText", "ReceiptAnalysis")

            // Parse amounts from text
            val result = parseAmountsFromText(fullText)

            MotiumApplication.logger.i("✅ Receipt analysis completed: TTC=${result.amountTTC}, HT=${result.amountHT}", "ReceiptAnalysis")
            Result.success(result)

        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Error analyzing receipt: ${e.message}", "ReceiptAnalysis", e)
            Result.failure(e)
        }
    }

    /**
     * Parse amounts from OCR text
     * Looks for common patterns like "Total TTC", "Montant TTC", "Total HT", etc.
     */
    private fun parseAmountsFromText(text: String): ReceiptAnalysisResult {
        var amountTTC: Double? = null
        var amountHT: Double? = null

        try {
            // Normalize text: replace common separators
            val normalizedText = text
                .replace(",", ".")  // French decimal separator
                .replace("€", " EUR ")
                .replace("EUR", " EUR ")

            // Split into lines for better parsing
            val lines = normalizedText.lines()

            // Patterns for TTC (Total, Montant TTC, Total TTC, etc.)
            val ttcPatterns = listOf(
                Pattern.compile("(?i)(total|montant|à payer).*?ttc.*?([0-9]+[.,][0-9]{2})", Pattern.CASE_INSENSITIVE),
                Pattern.compile("(?i)ttc[:\\s]*([0-9]+[.,][0-9]{2})", Pattern.CASE_INSENSITIVE),
                Pattern.compile("(?i)([0-9]+[.,][0-9]{2})\\s*€?\\s*ttc", Pattern.CASE_INSENSITIVE),
                Pattern.compile("(?i)total.*?([0-9]+[.,][0-9]{2})\\s*€?\\s*$", Pattern.CASE_INSENSITIVE),
                Pattern.compile("(?i)à payer.*?([0-9]+[.,][0-9]{2})", Pattern.CASE_INSENSITIVE),
                Pattern.compile("(?i)net à payer.*?([0-9]+[.,][0-9]{2})", Pattern.CASE_INSENSITIVE),
                Pattern.compile("(?i)total ttc.*?([0-9]+[.,][0-9]{2})", Pattern.CASE_INSENSITIVE)
            )

            // Patterns for HT (Montant HT, Total HT, Sous-total HT, Base HT, etc.)
            val htPatterns = listOf(
                Pattern.compile("(?i)(total|montant|sous.?total|base).*?h\\.?t\\.?.*?([0-9]+[.,][0-9]{2})", Pattern.CASE_INSENSITIVE),
                Pattern.compile("(?i)h\\.?t\\.?[:\\s]*([0-9]+[.,][0-9]{2})", Pattern.CASE_INSENSITIVE),
                Pattern.compile("(?i)([0-9]+[.,][0-9]{2})\\s*€?\\s*h\\.?t\\.?", Pattern.CASE_INSENSITIVE),
                Pattern.compile("(?i)total h\\.?t\\.?.*?([0-9]+[.,][0-9]{2})", Pattern.CASE_INSENSITIVE),
                Pattern.compile("(?i)montant h\\.?t\\.?.*?([0-9]+[.,][0-9]{2})", Pattern.CASE_INSENSITIVE),
                Pattern.compile("(?i)base h\\.?t\\.?.*?([0-9]+[.,][0-9]{2})", Pattern.CASE_INSENSITIVE),
                Pattern.compile("(?i)sous.?total.*?([0-9]+[.,][0-9]{2})", Pattern.CASE_INSENSITIVE)
            )

            // Patterns for TVA amount (to calculate HT = TTC - TVA)
            val tvaPatterns = listOf(
                Pattern.compile("(?i)tva[:\\s]*([0-9]+[.,][0-9]{2})", Pattern.CASE_INSENSITIVE),
                Pattern.compile("(?i)t\\.?v\\.?a\\.?[:\\s]*([0-9]+[.,][0-9]{2})", Pattern.CASE_INSENSITIVE),
                Pattern.compile("(?i)dont tva.*?([0-9]+[.,][0-9]{2})", Pattern.CASE_INSENSITIVE),
                Pattern.compile("(?i)montant tva.*?([0-9]+[.,][0-9]{2})", Pattern.CASE_INSENSITIVE)
            )

            // Try to find TTC amount
            for (pattern in ttcPatterns) {
                for (line in lines) {
                    val matcher = pattern.matcher(line)
                    if (matcher.find()) {
                        val amountStr = matcher.group(matcher.groupCount()).replace(",", ".")
                        amountTTC = amountStr.toDoubleOrNull()
                        if (amountTTC != null) {
                            MotiumApplication.logger.d("Found TTC: $amountTTC from line: $line", "ReceiptAnalysis")
                            break
                        }
                    }
                }
                if (amountTTC != null) break
            }

            // Try to find HT amount
            for (pattern in htPatterns) {
                for (line in lines) {
                    val matcher = pattern.matcher(line)
                    if (matcher.find()) {
                        val amountStr = matcher.group(matcher.groupCount()).replace(",", ".")
                        amountHT = amountStr.toDoubleOrNull()
                        if (amountHT != null) {
                            MotiumApplication.logger.d("Found HT: $amountHT from line: $line", "ReceiptAnalysis")
                            break
                        }
                    }
                }
                if (amountHT != null) break
            }

            // If HT not found but we have TTC, try to find TVA and calculate HT = TTC - TVA
            if (amountHT == null && amountTTC != null) {
                var tvaAmount: Double? = null
                for (pattern in tvaPatterns) {
                    for (line in lines) {
                        val matcher = pattern.matcher(line)
                        if (matcher.find()) {
                            val amountStr = matcher.group(matcher.groupCount()).replace(",", ".")
                            tvaAmount = amountStr.toDoubleOrNull()
                            if (tvaAmount != null) {
                                MotiumApplication.logger.d("Found TVA: $tvaAmount from line: $line", "ReceiptAnalysis")
                                break
                            }
                        }
                    }
                    if (tvaAmount != null) break
                }

                // Calculate HT from TTC - TVA
                if (tvaAmount != null && tvaAmount < amountTTC!!) {
                    amountHT = amountTTC!! - tvaAmount
                    // Round to 2 decimal places
                    amountHT = Math.round(amountHT!! * 100.0) / 100.0
                    MotiumApplication.logger.d("Calculated HT from TTC - TVA: $amountHT", "ReceiptAnalysis")
                }
            }

            // If we didn't find TTC but found HT, try to find the largest amount as TTC
            if (amountTTC == null && amountHT != null) {
                val allAmounts = extractAllAmounts(normalizedText)
                amountTTC = allAmounts.filter { it > (amountHT ?: 0.0) }.maxOrNull()
                MotiumApplication.logger.d("Inferred TTC from largest amount: $amountTTC", "ReceiptAnalysis")
            }

            // If we only found one amount and no keywords, assume it's TTC (total)
            if (amountTTC == null && amountHT == null) {
                val allAmounts = extractAllAmounts(normalizedText)
                if (allAmounts.isNotEmpty()) {
                    // Take the largest amount as TTC
                    amountTTC = allAmounts.maxOrNull()
                    MotiumApplication.logger.d("Using largest amount as TTC: $amountTTC", "ReceiptAnalysis")
                }
            }

        } catch (e: Exception) {
            MotiumApplication.logger.e("Error parsing amounts: ${e.message}", "ReceiptAnalysis", e)
        }

        val confidence = when {
            amountTTC != null && amountHT != null -> "high"
            amountTTC != null -> "medium"
            else -> "low"
        }

        return ReceiptAnalysisResult(
            amountTTC = amountTTC,
            amountHT = amountHT,
            confidence = confidence
        )
    }

    /**
     * Extract all monetary amounts from text
     */
    private fun extractAllAmounts(text: String): List<Double> {
        val amounts = mutableListOf<Double>()
        val pattern = Pattern.compile("([0-9]+[.,][0-9]{2})(?:\\s*€)?")
        val matcher = pattern.matcher(text)

        while (matcher.find()) {
            val amountStr = matcher.group(1).replace(",", ".")
            amountStr.toDoubleOrNull()?.let { amounts.add(it) }
        }

        return amounts.distinct().sorted()
    }
}
