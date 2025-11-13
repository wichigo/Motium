package com.application.motium.utils

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class GoogleSignInHelper(
    private val context: Context
) {
    private val googleSignInClient: GoogleSignInClient by lazy {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getWebClientId())
            .requestEmail()
            .build()

        GoogleSignIn.getClient(context, gso)
    }

    private var signInLauncher: ActivityResultLauncher<Intent>? = null
    private var pendingSignInCallback: ((Result<String>) -> Unit)? = null

    fun initialize(activity: ComponentActivity) {
        signInLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            handleSignInResult(result)
        }
    }

    fun signIn(callback: (Result<String>) -> Unit) {
        pendingSignInCallback = callback
        val signInIntent = googleSignInClient.signInIntent
        signInLauncher?.launch(signInIntent) ?: run {
            callback(Result.failure(Exception("GoogleSignInHelper not initialized")))
        }
    }

    suspend fun signInSuspend(): Result<String> = suspendCancellableCoroutine { continuation ->
        signIn { result ->
            continuation.resume(result)
        }
    }

    private fun handleSignInResult(result: ActivityResult) {
        val callback = pendingSignInCallback ?: return
        pendingSignInCallback = null

        try {
            val task: Task<GoogleSignInAccount> = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            val account = task.getResult(ApiException::class.java)
            val idToken = account.idToken

            if (idToken != null) {
                callback(Result.success(idToken))
            } else {
                callback(Result.failure(Exception("Failed to get ID token from Google")))
            }
        } catch (e: ApiException) {
            callback(Result.failure(e))
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }

    fun signOut() {
        googleSignInClient.signOut()
    }

    fun revokeAccess() {
        googleSignInClient.revokeAccess()
    }

    fun isSignedIn(): Boolean {
        return GoogleSignIn.getLastSignedInAccount(context) != null
    }

    private fun getWebClientId(): String {
        // This should be your Google Cloud Console Web Client ID
        // For now, we'll use a placeholder - this needs to be configured in your project
        return context.getString(
            context.resources.getIdentifier(
                "default_web_client_id",
                "string",
                context.packageName
            )
        )
    }
}

sealed class GoogleSignInResult {
    data class Success(val idToken: String) : GoogleSignInResult()
    data class Error(val exception: Throwable) : GoogleSignInResult()
    data object Cancelled : GoogleSignInResult()
}