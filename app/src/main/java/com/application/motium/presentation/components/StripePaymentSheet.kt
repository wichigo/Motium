package com.application.motium.presentation.components

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.stripe.android.paymentsheet.CreateIntentResult
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import com.stripe.android.paymentsheet.rememberPaymentSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.application.motium.presentation.theme.MotiumPrimary

/**
 * Wrapper composable for Stripe PaymentSheet with Motium styling.
 *
 * Usage:
 * ```
 * StripePaymentSheet(
 *     clientSecret = "pi_xxx_secret_xxx",
 *     customerId = "cus_xxx",
 *     ephemeralKey = "ek_xxx",
 *     merchantName = "Motium",
 *     primaryButtonLabel = "Payer 4,99 €",
 *     onResult = { result ->
 *         when (result) {
 *             is PaymentSheetResult.Completed -> // Success
 *             is PaymentSheetResult.Canceled -> // Canceled
 *             is PaymentSheetResult.Failed -> // Error
 *         }
 *     }
 * )
 * ```
 */
@Composable
fun StripePaymentSheet(
    clientSecret: String,
    customerId: String?,
    ephemeralKey: String?,
    merchantName: String = "Motium",
    primaryButtonLabel: String? = null,
    onResult: (PaymentSheetResult) -> Unit
) {
    val paymentSheet = rememberPaymentSheet(onResult)
    val primaryColor = MotiumPrimary

    LaunchedEffect(clientSecret) {
        val customerConfig = if (customerId != null && ephemeralKey != null) {
            PaymentSheet.CustomerConfiguration(
                id = customerId,
                ephemeralKeySecret = ephemeralKey
            )
        } else null

        val configuration = PaymentSheet.Configuration.Builder(merchantName)
            .appearance(motiumPaymentSheetAppearance(primaryColor))
            .allowsDelayedPaymentMethods(false)
            .apply {
                customerConfig?.let { customer(it) }
                primaryButtonLabel?.let { primaryButtonLabel(it) }
            }
            .build()

        paymentSheet.presentWithPaymentIntent(clientSecret, configuration)
    }
}

/**
 * Creates Motium-styled PaymentSheet appearance.
 * Uses dark theme colors matching the app's design.
 */
private fun motiumPaymentSheetAppearance(primaryColor: Color): PaymentSheet.Appearance {
    // Motium color palette (dark theme)
    val surfaceColor = Color(0xFF1E1E1E)      // Dark surface
    val componentColor = Color(0xFF2D2D2D)    // Component background
    val onSurfaceColor = Color(0xFFFFFFFF)    // White text
    val subtitleColor = Color(0xFFB0B0B0)     // Gray subtitle
    val errorColor = Color(0xFFEF5350)        // Red error
    val placeholderColor = Color(0xFF757575)  // Gray placeholder

    return PaymentSheet.Appearance(
        colorsLight = PaymentSheet.Colors(
            primary = primaryColor.toArgb(),
            surface = surfaceColor.toArgb(),
            component = componentColor.toArgb(),
            componentBorder = Color(0xFF3D3D3D).toArgb(),
            componentDivider = Color(0xFF3D3D3D).toArgb(),
            onComponent = onSurfaceColor.toArgb(),
            subtitle = subtitleColor.toArgb(),
            placeholderText = placeholderColor.toArgb(),
            onSurface = onSurfaceColor.toArgb(),
            appBarIcon = onSurfaceColor.toArgb(),
            error = errorColor.toArgb()
        ),
        colorsDark = PaymentSheet.Colors(
            primary = primaryColor.toArgb(),
            surface = surfaceColor.toArgb(),
            component = componentColor.toArgb(),
            componentBorder = Color(0xFF3D3D3D).toArgb(),
            componentDivider = Color(0xFF3D3D3D).toArgb(),
            onComponent = onSurfaceColor.toArgb(),
            subtitle = subtitleColor.toArgb(),
            placeholderText = placeholderColor.toArgb(),
            onSurface = onSurfaceColor.toArgb(),
            appBarIcon = onSurfaceColor.toArgb(),
            error = errorColor.toArgb()
        ),
        shapes = PaymentSheet.Shapes(
            cornerRadiusDp = 12f,
            borderStrokeWidthDp = 1f
        ),
        primaryButton = PaymentSheet.PrimaryButton(
            colorsLight = PaymentSheet.PrimaryButtonColors(
                background = primaryColor.toArgb(),
                onBackground = Color.White.toArgb(),
                border = Color.Transparent.toArgb()
            ),
            colorsDark = PaymentSheet.PrimaryButtonColors(
                background = primaryColor.toArgb(),
                onBackground = Color.White.toArgb(),
                border = Color.Transparent.toArgb()
            ),
            shape = PaymentSheet.PrimaryButtonShape(
                cornerRadiusDp = 12f,
                borderStrokeWidthDp = 0f
            )
        )
    )
}

/**
 * Helper to format amount in cents to EUR string
 */
fun formatAmountCents(amountCents: Int): String {
    val euros = amountCents / 100.0
    return String.format("%.2f €", euros).replace(".", ",")
}

/**
 * Helper to create primary button label for subscription
 */
fun createSubscriptionButtonLabel(amountCents: Int, isLifetime: Boolean): String {
    val amount = formatAmountCents(amountCents)
    return if (isLifetime) {
        "Payer $amount"
    } else {
        "S'abonner pour $amount/mois"
    }
}

/**
 * Helper to create primary button label for license purchase
 */
fun createLicenseButtonLabel(amountCents: Int, quantity: Int, isLifetime: Boolean): String {
    val amount = formatAmountCents(amountCents)
    val licenseText = if (quantity == 1) "licence" else "licences"
    return if (isLifetime) {
        "Acheter $quantity $licenseText pour $amount"
    } else {
        "Acheter $quantity $licenseText pour $amount/mois"
    }
}

/**
 * Configuration for deferred payment (IntentConfiguration mode).
 * The PaymentIntent is created AFTER the user enters their card details.
 */
data class DeferredPaymentConfig(
    val amountCents: Long,
    val currency: String = "eur",
    val isSubscription: Boolean = false
)

/**
 * Deferred PaymentSheet - creates PaymentIntent only when user taps Pay.
 * This bypasses the need for email validation upfront.
 *
 * Usage:
 * ```
 * StripeDeferredPaymentSheet(
 *     config = DeferredPaymentConfig(amountCents = 499, currency = "eur"),
 *     merchantName = "Motium",
 *     primaryButtonLabel = "Payer 4,99 €",
 *     onCreateIntent = { paymentMethodId ->
 *         // Call your server to create PaymentIntent with paymentMethodId
 *         // Return the clientSecret
 *         subscriptionManager.confirmPayment(paymentMethodId, ...)
 *     },
 *     onResult = { result ->
 *         when (result) {
 *             is PaymentSheetResult.Completed -> // Success
 *             is PaymentSheetResult.Canceled -> // Canceled
 *             is PaymentSheetResult.Failed -> // Error
 *         }
 *     }
 * )
 * ```
 */
@Composable
fun StripeDeferredPaymentSheet(
    config: DeferredPaymentConfig,
    merchantName: String = "Motium",
    primaryButtonLabel: String? = null,
    onCreateIntent: suspend (paymentMethodId: String) -> Result<String>,
    onResult: (PaymentSheetResult) -> Unit
) {
    // Guard against double calls - Stripe SDK can sometimes call the callback multiple times
    val hasCreatedIntent = remember { mutableStateOf(false) }
    val primaryColor = MotiumPrimary

    // Use rememberUpdatedState to capture latest callbacks without causing recomposition issues
    val currentOnCreateIntent = rememberUpdatedState(onCreateIntent)
    val currentOnResult = rememberUpdatedState(onResult)

    val paymentSheet = rememberPaymentSheet(
        createIntentCallback = { paymentMethod, _ ->
            // Prevent double creation
            if (hasCreatedIntent.value) {
                CreateIntentResult.Failure(
                    cause = Exception("Payment already in progress"),
                    displayMessage = "Paiement déjà en cours"
                )
            } else {
                hasCreatedIntent.value = true
                try {
                    val result = withContext(Dispatchers.IO) {
                        currentOnCreateIntent.value(paymentMethod.id!!)
                    }
                    result.fold(
                        onSuccess = { clientSecret ->
                            CreateIntentResult.Success(clientSecret)
                        },
                        onFailure = { error ->
                            hasCreatedIntent.value = false // Reset on failure to allow retry
                            CreateIntentResult.Failure(
                                cause = error as? Exception ?: Exception(error.message),
                                displayMessage = error.message ?: "Erreur lors de la création du paiement"
                            )
                        }
                    )
                } catch (e: Exception) {
                    hasCreatedIntent.value = false // Reset on failure to allow retry
                    CreateIntentResult.Failure(
                        cause = e,
                        displayMessage = e.message ?: "Erreur inconnue"
                    )
                }
            }
        },
        paymentResultCallback = { result ->
            currentOnResult.value(result)
        }
    )

    // Remember config values to avoid recreating intentConfiguration on every recomposition
    val rememberedAmountCents = remember(config.amountCents) { config.amountCents }
    val rememberedCurrency = remember(config.currency) { config.currency }
    val rememberedIsSubscription = remember(config.isSubscription) { config.isSubscription }
    val rememberedMerchantName = remember(merchantName) { merchantName }
    val rememberedPrimaryButtonLabel = remember(primaryButtonLabel) { primaryButtonLabel }

    // Use Unit as key - we only want to present once when composable enters composition
    LaunchedEffect(Unit) {
        val intentConfiguration = PaymentSheet.IntentConfiguration(
            mode = if (rememberedIsSubscription) {
                PaymentSheet.IntentConfiguration.Mode.Setup(
                    currency = rememberedCurrency,
                    setupFutureUse = PaymentSheet.IntentConfiguration.SetupFutureUse.OffSession
                )
            } else {
                PaymentSheet.IntentConfiguration.Mode.Payment(
                    amount = rememberedAmountCents,
                    currency = rememberedCurrency
                )
            }
        )

        val configuration = PaymentSheet.Configuration.Builder(rememberedMerchantName)
            .appearance(motiumPaymentSheetAppearance(primaryColor))
            .allowsDelayedPaymentMethods(false)
            .apply {
                rememberedPrimaryButtonLabel?.let { primaryButtonLabel(it) }
            }
            .build()

        paymentSheet.presentWithIntentConfiguration(intentConfiguration, configuration)
    }
}
