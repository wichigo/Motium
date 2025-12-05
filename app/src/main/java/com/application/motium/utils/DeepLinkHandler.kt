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
     * Pending company link invitation token.
     * Set when app is opened via deep link, consumed after user is authenticated.
     */
    @Volatile
    var pendingLinkToken: String? = null
        private set

    /**
     * Check if there's a pending company link to activate.
     */
    fun hasPendingLink(): Boolean = pendingLinkToken != null

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
     * Handle an incoming intent and extract any deep link data.
     * Supports both HTTPS URLs and custom scheme.
     *
     * Supported formats:
     * - https://motium.app/link?token=xxx
     * - https://motium.app/link/xxx
     * - motium://link?token=xxx
     * - motium://link/xxx
     */
    fun handleIntent(intent: Intent): Boolean {
        val data = intent.data ?: return false

        MotiumApplication.logger.d("Processing deep link: $data", TAG)

        return when {
            isCompanyLinkUri(data) -> {
                val token = extractToken(data)
                if (token != null) {
                    pendingLinkToken = token
                    MotiumApplication.logger.i("Stored pending link token from deep link", TAG)
                    true
                } else {
                    MotiumApplication.logger.w("Company link URI without token: $data", TAG)
                    false
                }
            }
            else -> {
                MotiumApplication.logger.d("Unknown deep link format: $data", TAG)
                false
            }
        }
    }

    /**
     * Check if URI is a company link invitation.
     */
    private fun isCompanyLinkUri(uri: Uri): Boolean {
        val path = uri.path ?: return false

        // Check for motium.app host or motium scheme
        val isValidHost = uri.host == "motium.app" || uri.scheme == "motium"

        // Check for /link path
        val isLinkPath = path.startsWith("/link")

        return isValidHost && isLinkPath
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
        MotiumApplication.logger.d("Cleared pending deep link data", TAG)
    }
}
