package com.application.motium.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.application.motium.BuildConfig
import com.application.motium.MotiumApplication
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream

@Serializable
data class ReceiptAnalysisResult(
    val amountTTC: Double? = null,
    val amountHT: Double? = null,
    val confidence: String = "low"
)

class ReceiptAnalysisService(private val context: Context) {

    private val client = HttpClient()
    private val json = Json { ignoreUnknownKeys = true }

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
     * Analyze a receipt image and extract amounts
     */
    suspend fun analyzeReceipt(imageUri: Uri): Result<ReceiptAnalysisResult> = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("🔍 Analyzing receipt image: $imageUri", "ReceiptAnalysis")

            // Convert image to base64
            val base64Image = imageUriToBase64(imageUri)
            if (base64Image == null) {
                MotiumApplication.logger.e("❌ Failed to convert image to base64", "ReceiptAnalysis")
                return@withContext Result.failure(Exception("Failed to convert image"))
            }

            // Call Claude API
            val response = callClaudeVisionAPI(base64Image)

            MotiumApplication.logger.i("✅ Receipt analysis completed: TTC=${response.amountTTC}, HT=${response.amountHT}", "ReceiptAnalysis")
            Result.success(response)

        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Error analyzing receipt: ${e.message}", "ReceiptAnalysis", e)
            Result.failure(e)
        }
    }

    /**
     * Convert image URI to base64 string
     */
    private fun imageUriToBase64(uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            // Resize image if too large (max 1024x1024 to save bandwidth)
            val resizedBitmap = if (bitmap.width > 1024 || bitmap.height > 1024) {
                val ratio = minOf(1024f / bitmap.width, 1024f / bitmap.height)
                val width = (bitmap.width * ratio).toInt()
                val height = (bitmap.height * ratio).toInt()
                Bitmap.createScaledBitmap(bitmap, width, height, true)
            } else {
                bitmap
            }

            // Convert to JPEG and encode to base64
            val outputStream = ByteArrayOutputStream()
            resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
            val byteArray = outputStream.toByteArray()
            Base64.encodeToString(byteArray, Base64.NO_WRAP)
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error converting image to base64: ${e.message}", "ReceiptAnalysis", e)
            null
        }
    }

    /**
     * Call Claude Vision API to analyze receipt
     */
    private suspend fun callClaudeVisionAPI(base64Image: String): ReceiptAnalysisResult {
        val apiKey = BuildConfig.CLAUDE_API_KEY

        val requestBody = """
        {
            "model": "claude-3-5-sonnet-20241022",
            "max_tokens": 1024,
            "messages": [
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "image",
                            "source": {
                                "type": "base64",
                                "media_type": "image/jpeg",
                                "data": "$base64Image"
                            }
                        },
                        {
                            "type": "text",
                            "text": "Analyse cette facture/reçu et extrait les montants suivants:\n- Montant TTC (Toutes Taxes Comprises) ou montant total\n- Montant HT (Hors Taxes) si présent\n\nRéponds UNIQUEMENT au format JSON suivant, sans texte supplémentaire:\n{\n  \"amountTTC\": <nombre ou null>,\n  \"amountHT\": <nombre ou null>\n}\n\nUtilise le point comme séparateur décimal (pas de virgule). Si un montant n'est pas trouvé, mets null."
                        }
                    ]
                }
            ]
        }
        """.trimIndent()

        return try {
            val response: HttpResponse = client.post("https://api.anthropic.com/v1/messages") {
                headers {
                    append("x-api-key", apiKey)
                    append("anthropic-version", "2023-06-01")
                    append("content-type", "application/json")
                }
                setBody(requestBody)
            }

            val responseBody = response.bodyAsText()
            MotiumApplication.logger.d("Claude API Response: $responseBody", "ReceiptAnalysis")

            // Parse Claude's response
            parseClaudeResponse(responseBody)

        } catch (e: Exception) {
            MotiumApplication.logger.e("Error calling Claude API: ${e.message}", "ReceiptAnalysis", e)
            throw e
        }
    }

    /**
     * Parse Claude's response to extract amounts
     */
    private fun parseClaudeResponse(responseBody: String): ReceiptAnalysisResult {
        return try {
            // Parse the Claude API response structure
            @Serializable
            data class ClaudeContent(val text: String, val type: String)
            @Serializable
            data class ClaudeResponse(val content: List<ClaudeContent>)

            val claudeResponse = json.decodeFromString<ClaudeResponse>(responseBody)
            val textContent = claudeResponse.content.firstOrNull { it.type == "text" }?.text

            if (textContent != null) {
                // Extract JSON from the text (might have markdown code blocks)
                val jsonText = textContent
                    .replace("```json", "")
                    .replace("```", "")
                    .trim()

                MotiumApplication.logger.d("Extracted JSON: $jsonText", "ReceiptAnalysis")

                // Parse the amounts
                @Serializable
                data class AmountsResponse(val amountTTC: Double? = null, val amountHT: Double? = null)

                val amounts = json.decodeFromString<AmountsResponse>(jsonText)
                ReceiptAnalysisResult(
                    amountTTC = amounts.amountTTC,
                    amountHT = amounts.amountHT,
                    confidence = if (amounts.amountTTC != null) "high" else "low"
                )
            } else {
                MotiumApplication.logger.w("No text content in Claude response", "ReceiptAnalysis")
                ReceiptAnalysisResult()
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("Error parsing Claude response: ${e.message}", "ReceiptAnalysis", e)
            ReceiptAnalysisResult()
        }
    }
}
