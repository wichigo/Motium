package com.application.motium.utils

import android.content.Intent
import android.net.Uri
import com.application.motium.MotiumApplication

/**
 * Singleton handler for deep link processing in Motium app.
 * Stores pending deep link tokens for processing after authentication.
 */
object DeepLinkHandler {

    private const val TAG = "DeepLinkHandler"

    /**
     * Types of deep links supported by the app.
     */
    enum class DeepLinkType {
        COMPANY_LINK,      // Company invitation link
        PASSWORD_RESET,    // Password reset link
        EMAIL_VERIFY,      // Email verification link
        UNLINK_CONFIRM     // Account unlink confirmation link
    }

    /**
     * Pending company link invitation token.
     * Set when app is opened via deep link, consumed after user is authenticated.
     */
    @Volatile
    var pendingLinkToken: String? = null
        private set

    /**
     * Pending password reset token.
     * Set when app is opened via password reset deep link.
     */
    @Volatile
    var pendingResetToken: String? = null
        private set

    /**
     * Pending email verification token.
     * Set when app is opened via email verification deep link.
     */
    @Volatile
    var pendingVerifyToken: String? = null
        private set

    /**
     * Pending unlink confirmation token.
     * Set when app is opened via unlink confirmation deep link.
     */
    @Volatile
    var pendingUnlinkToken: String? = null
        private set

    /**
     * Check if there's a pending company link to activate.
     */
    fun hasPendingLink(): Boolean = pendingLinkToken != null

    /**
     * Check if there's a pending password reset token.
     */
    fun hasPendingReset(): Boolean = pendingResetToken != null

    /**
     * Check if there's a pending email verification token.
     */
    fun hasPendingVerify(): Boolean = pendingVerifyToken != null

    /**
     * Check if there's a pending unlink confirmation token.
     */
    fun hasPendingUnlink(): Boolean = pendingUnlinkToken != null

    /**
     * Consume and return the pending link token.
     * Token is cleared after consumption to prevent duplicate processing.
     */
    fun consumePendingToken(): String? {
        val token = pendingLinkToken
        pendingLinkToken = null
        if (token != null) {
            MotiumApplication.logger.i("Consumed pending link token", TAG)
        }
        return token
    }

    /**
     * Consume and return the pending password reset token.
     * Token is cleared after consumption to prevent duplicate processing.
     */
    fun consumePendingResetToken(): String? {
        val token = pendingResetToken
        pendingResetToken = null
        if (token != null) {
            MotiumApplication.logger.i("Consumed pending reset token", TAG)
        }
        return token
    }

    /**
     * Consume and return the pending email verification token.
     * Token is cleared after consumption to prevent duplicate processing.
     */
    fun consumePendingVerifyToken(): String? {
        val token = pendingVerifyToken
        pendingVerifyToken = null
        if (token != null) {
            MotiumApplication.logger.i("Consumed pending verify token", TAG)
        }
        return token
    }

    /**
     * Consume and return the pending unlink confirmation token.
     * Token is cleared after consumption to prevent duplicate processing.
     */
    fun consumePendingUnlinkToken(): String? {
        val token = pendingUnlinkToken
        pendingUnlinkToken = null
        if (token != null) {
            MotiumApplication.logger.i("Consumed pending unlink token", TAG)
        }
        return token
    }

    /**
     * Handle an incoming intent and extract any deep link data.
     * Supports both HTTPS URLs and custom scheme.
     *
     * Supported formats:
     * - https://motium.org/link?token=xxx (company invitation)
     * - https://motium.org/link/xxx (company invitation)
     * - https://motium.org/reset?token=xxx (password reset)
     * - https://motium.org/verify?token=xxx (email verification)
     * - https://motium.org/unlink?token=xxx (unlink confirmation)
     * - motium://link?token=xxx (company invitation)
     * - motium://link/xxx (company invitation)
     * - motium://reset?token=xxx (password reset)
     * - motium://verify?token=xxx (email verification)
     * - motium://unlink?token=xxx (unlink confirmation)
     *
     * @return The type of deep link processed, or null if not handled
     */
    fun handleIntent(intent: Intent): DeepLinkType? {
        val data = intent.data ?: return null

        MotiumApplication.logger.d("Processing deep link: $data", TAG)

        return when {
            isPasswordResetUri(data) -> {
                val token = extractToken(data)
                if (token != null) {
                    pendingResetToken = token
                    MotiumApplication.logger.i("Stored pending password reset token from deep link", TAG)
                    DeepLinkType.PASSWORD_RESET
                } else {
                    MotiumApplication.logger.w("Password reset URI without token: $data", TAG)
                    null
                }
            }
            isEmailVerifyUri(data) -> {
                val token = extractToken(data)
                if (token != null) {
                    pendingVerifyToken = token
                    MotiumApplication.logger.i("Stored pending verify token from deep link", TAG)
                    DeepLinkType.EMAIL_VERIFY
                } else {
                    MotiumApplication.logger.w("Email verify URI without token: $data", TAG)
                    null
                }
            }
            isUnlinkConfirmUri(data) -> {
                val token = extractToken(data)
                if (token != null) {
                    pendingUnlinkToken = token
                    MotiumApplication.logger.i("Stored pending unlink token from deep link", TAG)
                    DeepLinkType.UNLINK_CONFIRM
                } else {
                    MotiumApplication.logger.w("Unlink confirm URI without token: $data", TAG)
                    null
                }
            }
            isCompanyLinkUri(data) -> {
                val token = extractToken(data)
                if (token != null) {
                    pendingLinkToken = token
                    MotiumApplication.logger.i("Stored pending link token from deep link", TAG)
                    DeepLinkType.COMPANY_LINK
                } else {
                    MotiumApplication.logger.w("Company link URI without token: $data", TAG)
                    null
                }
            }
            else -> {
                MotiumApplication.logger.d("Unknown deep link format: $data", TAG)
                null
            }
        }
    }

    /**
     * Check if URI is a password reset link.
     */
    private fun isPasswordResetUri(uri: Uri): Boolean {
        val path = uri.path ?: return false

        // Check for motium.org host or motium scheme
        val isValidHost = uri.host == "motium.org" || uri.scheme == "motium"

        // Check for /reset path
        val isResetPath = path.startsWith("/reset")

        return isValidHost && isResetPath
    }

    /**
     * Check if URI is a company link invitation.
     */
    private fun isCompanyLinkUri(uri: Uri): Boolean {
        val path = uri.path ?: return false

        // Check for motium.org host or motium scheme
        val isValidHost = uri.host == "motium.org" || uri.scheme == "motium"

        // Check for /link path
        val isLinkPath = path.startsWith("/link")

        return isValidHost && isLinkPath
    }

    /**
     * Check if URI is an email verification link.
     */
    private fun isEmailVerifyUri(uri: Uri): Boolean {
        val path = uri.path ?: return false

        // Check for motium.org host or motium scheme
        val isValidHost = uri.host == "motium.org" || uri.scheme == "motium"

        // Check for /verify path
        val isVerifyPath = path.startsWith("/verify")

        return isValidHost && isVerifyPath
    }

    /**
     * Check if URI is an unlink confirmation link.
     */
    private fun isUnlinkConfirmUri(uri: Uri): Boolean {
        val path = uri.path ?: return false

        // Check for motium.org host or motium scheme
        val isValidHost = uri.host == "motium.org" || uri.scheme == "motium"

        // Check for /unlink path
        val isUnlinkPath = path.startsWith("/unlink")

        return isValidHost && isUnlinkPath
    }

    /**
     * Extract token from URI.
     * Supports both query parameter and path segment formats.
     */
    private fun extractToken(uri: Uri): String? {
        // Try query parameter first: ?token=xxx
        val queryToken = uri.getQueryParameter("token")
        if (!queryToken.isNullOrBlank()) {
            return queryToken
        }

        // Try path segment: /link/xxx
        val pathSegments = uri.pathSegments
        if (pathSegments.size >= 2 && pathSegments[0] == "link") {
            val pathToken = pathSegments[1]
            if (pathToken.isNotBlank()) {
                return pathToken
            }
        }

        return null
    }

    /**
     * Clear any pending deep link data.
     * Called on logout or when manually clearing state.
     */
    fun clearPendingData() {
        pendingLinkToken = null
        pendingResetToken = null
        pendingVerifyToken = null
        pendingUnlinkToken = null
        MotiumApplication.logger.d("Cleared pending deep link data", TAG)
    }
}
