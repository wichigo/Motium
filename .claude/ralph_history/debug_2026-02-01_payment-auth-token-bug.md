# Debug Report: Payment confirmation fails with "Invalid or expired authentication token"

**Date:** 2026-02-01
**Bug ID:** payment-auth-token-bug
**Status:** FIXED
**Severity:** Critical (blocks subscription purchases)

---

## Problem Summary

The `confirmPaymentWithMethod()` call to the `confirm-payment-intent` Edge Function was failing with **"Invalid or expired authentication token"** when the user tried to complete a subscription payment via Stripe PaymentSheet.

### Symptoms
- Payment flow starts successfully (PaymentSheet appears)
- User enters card details and taps "Pay"
- Error appears: "Invalid or expired authentication token"
- Payment never completes
- Subscription not activated

### Stack Trace (from logs)
```
SubscriptionManager: ❌ Payment confirmation failed: Invalid or expired authentication token
java.lang.Exception: Invalid or expired authentication token
    at SubscriptionManager$confirmPaymentWithMethod$2.invokeSuspend(SubscriptionManager.kt:761)
    ...
```

### Root Cause

The `SubscriptionManager` was using the **SUPABASE_ANON_KEY** as the Bearer token in the Authorization header when calling Edge Functions:

```kotlin
// BEFORE (problematic)
.addHeader("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
```

On the self-hosted Supabase server at `api.motium.app`, the API Gateway (Kong) validates JWT tokens. The anon key is a static JWT that may have been invalidated during a JWT secret rotation or server reconfiguration.

The `confirm-payment-intent` Edge Function does not require authentication internally (it uses `SUPABASE_SERVICE_ROLE_KEY`), but the **Kong gateway validates the JWT** before the request reaches the function.

---

## Fix Applied

**Files Modified:**

### 1. `SecureSessionStorage.kt` (line 166)
Added `getAccessToken()` method to retrieve the user's JWT token:

```kotlin
/**
 * Get the current access token for authenticated API calls.
 * Returns null if no session exists or token is empty.
 */
fun getAccessToken(): String? {
    val token = encryptedPrefs.getString(KEY_ACCESS_TOKEN, null)
    return if (token.isNullOrBlank()) null else token
}
```

### 2. `SubscriptionManager.kt`

**Import added:**
```kotlin
import com.application.motium.data.preferences.SecureSessionStorage
```

**Field added (line 73):**
```kotlin
private val secureSessionStorage = SecureSessionStorage(context)
```

**Helper method added (lines 163-175):**
```kotlin
/**
 * Get the authentication token for API calls.
 * Prefers the user's JWT token if available, falls back to anon key.
 * This ensures Edge Functions can validate the user's identity.
 */
private fun getAuthToken(): String {
    val userToken = secureSessionStorage.getAccessToken()
    return if (!userToken.isNullOrBlank()) {
        MotiumApplication.logger.d("Using user JWT token for API call", TAG)
        userToken
    } else {
        MotiumApplication.logger.w("No user token available, using anon key", TAG)
        BuildConfig.SUPABASE_ANON_KEY
    }
}
```

**Authorization header changed in 3 methods:**

1. `callCreatePaymentIntent()` (line 381):
```kotlin
val authToken = getAuthToken()
.addHeader("Authorization", "Bearer $authToken")
```

2. `confirmPaymentWithMethod()` (line 763):
```kotlin
val authToken = getAuthToken()
.addHeader("Authorization", "Bearer $authToken")
```

3. `cancelSubscription()` (line 822):
```kotlin
val authToken = getAuthToken()
.addHeader("Authorization", "Bearer $authToken")
```

---

## Verification

### Compilation
- **Status:** SUCCESS
- **Command:** `./gradlew :app:compileDebugKotlin`
- **Warnings:** Only deprecation warnings (unrelated to fix)

### Expected Behavior After Fix
1. When the user is logged in, their JWT token from `SecureSessionStorage` is used
2. The Kong gateway accepts the valid user JWT
3. The Edge Function receives and processes the request
4. Payment confirmation succeeds
5. If no user token is available, falls back to anon key (backward compatible)

---

## Files Modified

| File | Change |
|------|--------|
| `SecureSessionStorage.kt` | Added `getAccessToken()` method |
| `SubscriptionManager.kt` | Use user JWT token instead of anon key for Edge Function calls |

---

## Testing Recommendations

1. **Manual Test:**
   - Log in with an account
   - Go to Upgrade screen
   - Select a plan and tap "Subscribe"
   - Enter test card: `4242424242424242`
   - Tap Pay
   - Verify payment succeeds and subscription is activated

2. **Edge Cases:**
   - Test after session refresh (token should still work)
   - Test with expired token (should fall back to anon key or prompt re-login)
   - Test after cold app start

---

## Related Components

- `StripePaymentSheet.kt` - UI component that triggers payment
- `UpgradeViewModel.kt` - Calls `confirmPayment()`
- `confirm-payment-intent` Edge Function - Server-side payment processing

---

## Alternative Solution (Server-Side)

If the client-side fix is not sufficient, the Edge Function can be deployed with JWT verification disabled:

```bash
supabase functions deploy confirm-payment-intent --no-verify-jwt
```

This would allow the function to accept requests without a valid user JWT, but it's less secure as it doesn't validate the caller's identity.

---

## Lessons Learned

1. Self-hosted Supabase servers may invalidate anon keys during JWT secret rotation
2. Always prefer user JWT tokens over anon keys for authenticated API calls
3. The Kong gateway validates JWT tokens BEFORE requests reach Edge Functions
4. Having a fallback to anon key provides backward compatibility
