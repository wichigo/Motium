package com.application.motium.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.application.motium.MotiumApplication
import com.application.motium.data.supabase.SupabaseClient
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
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
        private const val PROFILE_PHOTOS_PREFIX = "profiles"

        // Image compression settings
        private const val MAX_IMAGE_DIMENSION = 1280 // Max width/height in pixels
        private const val JPEG_QUALITY = 80 // 0-100, lower = smaller file
        private const val MAX_FILE_SIZE_BYTES = 2 * 1024 * 1024 // 2MB max for Supabase free tier

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

            // Compress the image before upload
            val imageBytes = compressImage(imageUri)
                ?: return@withContext Result.failure(Exception("Failed to compress image file"))

            MotiumApplication.logger.i("📦 Compressed image size: ${imageBytes.size / 1024}KB", "SupabaseStorage")

            // Generate unique filename (always jpg after compression)
            val fileName = "${UUID.randomUUID()}.jpg"
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
     * Upload a profile photo to Supabase Storage.
     * Stored in receipts bucket under profiles/{userId}/...
     *
     * @param imageUri The content URI of the image to upload
     * @param userId Current user ID for path partitioning
     * @return Result with the public URL of the uploaded image
     */
    suspend fun uploadProfilePhoto(imageUri: Uri, userId: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            MotiumApplication.logger.i("📤 Uploading profile photo to Supabase Storage", "SupabaseStorage")

            val imageBytes = compressImage(imageUri)
                ?: return@withContext Result.failure(Exception("Failed to compress profile image"))

            MotiumApplication.logger.i("📦 Compressed profile image size: ${imageBytes.size / 1024}KB", "SupabaseStorage")

            val fileName = "${UUID.randomUUID()}.jpg"
            val filePath = "$PROFILE_PHOTOS_PREFIX/$userId/$fileName"

            storage.from(RECEIPTS_BUCKET).upload(filePath, imageBytes)
            val publicUrl = storage.from(RECEIPTS_BUCKET).publicUrl(filePath)

            MotiumApplication.logger.i("✅ Profile photo uploaded successfully: $publicUrl", "SupabaseStorage")
            Result.success(publicUrl)
        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Failed to upload profile photo: ${e.message}", "SupabaseStorage", e)
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

            // Compress the image before upload
            val imageBytes = compressImage(uri)
                ?: return@withContext Result.failure(Exception("Failed to compress image file from $localUri"))

            MotiumApplication.logger.i("📦 Compressed image size: ${imageBytes.size / 1024}KB", "SupabaseStorage")

            // Generate unique filename (always jpg after compression)
            val fileName = "${UUID.randomUUID()}.jpg"
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
            if (photoUrl.startsWith("http://")) {
                MotiumApplication.logger.w("Blocked cleartext receipt photo URL", "SupabaseStorage")
                return@withContext Result.failure(Exception("Cleartext HTTP blocked"))
            }

            MotiumApplication.logger.i("📥 Downloading receipt photo", "SupabaseStorage")

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

    /**
     * Compress an image from URI to fit within size limits.
     * - Resizes to max dimension while maintaining aspect ratio
     * - Corrects rotation based on EXIF data
     * - Compresses to JPEG with quality reduction if needed
     *
     * @param uri The URI of the image to compress
     * @return Compressed image bytes, or null if compression failed
     */
    private fun compressImage(uri: Uri): ByteArray? {
        return try {
            // First, get image dimensions without loading full bitmap
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            }

            val originalWidth = options.outWidth
            val originalHeight = options.outHeight

            if (originalWidth <= 0 || originalHeight <= 0) {
                MotiumApplication.logger.e("❌ Invalid image dimensions: ${originalWidth}x${originalHeight}", "SupabaseStorage")
                return null
            }

            MotiumApplication.logger.i("📐 Original image: ${originalWidth}x${originalHeight}", "SupabaseStorage")

            // Calculate sample size for initial downsampling (power of 2)
            val sampleSize = calculateSampleSize(originalWidth, originalHeight, MAX_IMAGE_DIMENSION)

            // Decode with subsampling
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            var bitmap = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, decodeOptions)
            } ?: return null

            MotiumApplication.logger.i("📐 Decoded bitmap: ${bitmap.width}x${bitmap.height} (sampleSize=$sampleSize)", "SupabaseStorage")

            // Apply EXIF rotation correction
            bitmap = correctImageRotation(uri, bitmap)

            // Scale down to exact max dimension if still too large
            if (bitmap.width > MAX_IMAGE_DIMENSION || bitmap.height > MAX_IMAGE_DIMENSION) {
                val scale = MAX_IMAGE_DIMENSION.toFloat() / maxOf(bitmap.width, bitmap.height)
                val newWidth = (bitmap.width * scale).toInt()
                val newHeight = (bitmap.height * scale).toInt()
                val scaledBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                if (scaledBitmap != bitmap) {
                    bitmap.recycle()
                    bitmap = scaledBitmap
                }
                MotiumApplication.logger.i("📐 Scaled bitmap: ${bitmap.width}x${bitmap.height}", "SupabaseStorage")
            }

            // Compress to JPEG, reducing quality if needed to fit size limit
            var quality = JPEG_QUALITY
            var compressedBytes: ByteArray

            do {
                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
                compressedBytes = outputStream.toByteArray()

                if (compressedBytes.size > MAX_FILE_SIZE_BYTES && quality > 30) {
                    quality -= 10
                    MotiumApplication.logger.i("📦 Size ${compressedBytes.size / 1024}KB > ${MAX_FILE_SIZE_BYTES / 1024}KB, reducing quality to $quality", "SupabaseStorage")
                } else {
                    break
                }
            } while (quality > 30)

            bitmap.recycle()

            MotiumApplication.logger.i("✅ Final compressed size: ${compressedBytes.size / 1024}KB (quality=$quality)", "SupabaseStorage")
            compressedBytes

        } catch (e: Exception) {
            MotiumApplication.logger.e("❌ Image compression failed: ${e.message}", "SupabaseStorage", e)
            null
        }
    }

    /**
     * Calculate sample size for BitmapFactory.Options.inSampleSize
     * Returns the largest power of 2 that keeps both dimensions >= maxDimension
     */
    private fun calculateSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sampleSize = 1
        val maxOriginal = maxOf(width, height)

        while (maxOriginal / sampleSize > maxDimension * 2) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /**
     * Correct image rotation based on EXIF orientation data.
     * Camera photos often have rotation stored in EXIF rather than applied to pixels.
     */
    private fun correctImageRotation(uri: Uri, bitmap: Bitmap): Bitmap {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return bitmap
            val exif = ExifInterface(inputStream)
            inputStream.close()

            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            val rotation = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }

            if (rotation != 0f) {
                MotiumApplication.logger.i("🔄 Rotating image by $rotation degrees", "SupabaseStorage")
                val matrix = Matrix().apply { postRotate(rotation) }
                val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotatedBitmap != bitmap) {
                    bitmap.recycle()
                }
                rotatedBitmap
            } else {
                bitmap
            }
        } catch (e: Exception) {
            MotiumApplication.logger.w("⚠️ Could not read EXIF data: ${e.message}", "SupabaseStorage")
            bitmap
        }
    }
}
