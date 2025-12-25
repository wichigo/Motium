package com.application.motium.service

import android.content.Context
import android.net.Uri
import com.application.motium.MotiumApplication
import com.application.motium.data.supabase.SupabaseClient
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.TimeUnit

class SupabaseStorageService(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val storage = SupabaseClient.client.storage

    companion object {
        @Volatile
        private var instance: SupabaseStorageService? = null
        private const val RECEIPTS_BUCKET = "receipts"

        fun getInstance(context: Context): SupabaseStorageService {
            return instance ?: synchronized(this) {
                instance ?: SupabaseStorageService(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Upload a receipt photo to Supabase Storage
     * @param imageUri The content URI of the image to upload
     * @return Result with the public URL of the uploaded image
     */
    suspend fun uploadReceiptPhoto(imageUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("📤 Uploading receipt photo to Supabase Storage", "SupabaseStorage")

            // Read the image file as bytes
            val imageBytes = context.contentResolver.openInputStream(imageUri)?.use { inputStream ->
                inputStream.readBytes()
            } ?: return@withContext Result.failure(Exception("Failed to read image file"))

            // Generate unique filename
            val extension = getFileExtension(imageUri)
            val fileName = "${UUID.randomUUID()}.$extension"
            val filePath = "receipts/$fileName"

            // Upload to Supabase Storage
            storage.from(RECEIPTS_BUCKET).upload(filePath, imageBytes)

            // Get public URL
            val publicUrl = storage.from(RECEIPTS_BUCKET).publicUrl(filePath)

            MotiumApplication.logger.i("✅ Receipt photo uploaded successfully: $publicUrl", "SupabaseStorage")
            Result.success(publicUrl)

        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Failed to upload receipt photo: ${e.message}", "SupabaseStorage", e)
            Result.failure(e)
        }
    }

    /**
     * Upload a queued photo from local URI for background sync.
     * Used by DeltaSyncWorker to process pending file uploads.
     *
     * @param localUri The file:// URI of the local image to upload
     * @return Result with the public URL of the uploaded image
     */
    suspend fun uploadQueuedPhoto(localUri: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("📤 Uploading queued photo: $localUri", "SupabaseStorage")

            val uri = Uri.parse(localUri)

            // Read the image file as bytes
            val imageBytes = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.readBytes()
            } ?: return@withContext Result.failure(Exception("Failed to read image file from $localUri"))

            // Generate unique filename
            val extension = getFileExtension(uri)
            val fileName = "${UUID.randomUUID()}.$extension"
            val filePath = "receipts/$fileName"

            // Upload to Supabase Storage
            storage.from(RECEIPTS_BUCKET).upload(filePath, imageBytes)

            // Get public URL
            val publicUrl = storage.from(RECEIPTS_BUCKET).publicUrl(filePath)

            MotiumApplication.logger.i("✅ Queued photo uploaded successfully: $publicUrl", "SupabaseStorage")
            Result.success(publicUrl)

        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Failed to upload queued photo: ${e.message}", "SupabaseStorage", e)
            Result.failure(e)
        }
    }

    /**
     * Download a receipt photo from Supabase Storage
     * @param photoUrl The public URL of the photo to download
     * @return Result with the photo bytes
     */
    suspend fun downloadReceiptPhoto(photoUrl: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("📥 Downloading receipt photo: $photoUrl", "SupabaseStorage")

            // Download directly via public URL using OkHttp
            val request = Request.Builder()
                .url(photoUrl)
                .build()

            val response = httpClient.newCall(request).execute()

            if (response.isSuccessful) {
                val bytes = response.body?.bytes()
                if (bytes != null && bytes.isNotEmpty()) {
                    MotiumApplication.logger.i("✅ Receipt photo downloaded successfully (${bytes.size} bytes)", "SupabaseStorage")
                    Result.success(bytes)
                } else {
                    MotiumApplication.logger.e("❌ Empty response body", "SupabaseStorage")
                    Result.failure(Exception("Empty response body"))
                }
            } else {
                MotiumApplication.logger.e("❌ HTTP error: ${response.code}", "SupabaseStorage")
                Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Failed to download receipt photo: ${e.message}", "SupabaseStorage", e)
            Result.failure(e)
        }
    }

    /**
     * Delete a receipt photo from Supabase Storage
     * @param photoUrl The public URL of the photo to delete
     */
    suspend fun deleteReceiptPhoto(photoUrl: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Extract file path from URL
            val filePath = extractFilePathFromUrl(photoUrl) ?: return@withContext Result.failure(
                Exception("Invalid photo URL")
            )

            storage.from(RECEIPTS_BUCKET).delete(filePath)

            MotiumApplication.logger.i("✅ Receipt photo deleted: $filePath", "SupabaseStorage")
            Result.success(Unit)

        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Failed to delete receipt photo: ${e.message}", "SupabaseStorage", e)
            Result.failure(e)
        }
    }

    /**
     * Get file extension from URI
     */
    private fun getFileExtension(uri: Uri): String {
        return when (context.contentResolver.getType(uri)) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg" // Default to jpg
        }
    }

    /**
     * Extract file path from Supabase Storage public URL
     * Example: https://xxx.supabase.co/storage/v1/object/public/receipts/receipts/uuid.jpg -> receipts/uuid.jpg
     */
    private fun extractFilePathFromUrl(url: String): String? {
        return try {
            val parts = url.split("/receipts/")
            if (parts.size >= 2) {
                "receipts/" + parts.last()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
