# Debug Report: Email Not Verified Blocking Login

**Date:** 2026-01-29
**Bug ID:** email-not-verified-blocking
**Status:** FIXED

## Bug Description

When a user registers but doesn't verify their email, and then tries to log in, the app gets stuck on the "Connexion en cours..." (Connecting...) loading screen indefinitely.

### Root Cause

1. User registers → Supabase creates account in `auth.users` with unconfirmed email
2. User tries to login → `auth.signInWith(Email)` **succeeds** (Supabase allows login with unconfirmed email)
3. Code tries to fetch/create user profile → **fails** because RLS policies require confirmed email OR the profile doesn't exist yet
4. App stays stuck in loading state because `authState.isLoading` is true and profile retrieval failed

### Expected Behavior

- Show a dialog on the login screen explaining that email verification is required
- Provide a "Resend verification email" button
- Don't allow the user to proceed until email is verified

## Fix Applied

### 1. `SupabaseAuthRepository.signIn()` (lines 836-848)

Added check for `authUser.isEmailConfirmed` immediately after successful authentication:

```kotlin
// SECURITY: Check if email is confirmed before allowing full login
if (!authUser.isEmailConfirmed) {
    MotiumApplication.logger.w(
        "⚠️ Email not confirmed for ${authUser.email} - blocking login",
        "SupabaseAuth"
    )
    // Sign out the user to prevent session persistence
    try { auth.signOut() } catch (e: Exception) {}
    _authState.value = _authState.value.copy(isLoading = false)
    return AuthResult.Error("EMAIL_NOT_VERIFIED:${authUser.email}:${authUser.id}")
}
```

### 2. `LoginUiState` (AuthViewModel.kt)

Added new state fields:

```kotlin
data class LoginUiState(
    // ... existing fields ...
    val emailNotVerified: Boolean = false,
    val unverifiedEmail: String? = null,
    val unverifiedUserId: String? = null
)
```

### 3. `AuthViewModel.signIn()` (lines 344-367)

Added detection of `EMAIL_NOT_VERIFIED` error:

```kotlin
if (result.message.startsWith("EMAIL_NOT_VERIFIED:")) {
    val parts = result.message.split(":")
    val unverifiedEmail = parts.getOrNull(1) ?: email
    val unverifiedUserId = parts.getOrNull(2)
    _loginState.value = _loginState.value.copy(
        isLoading = false,
        emailNotVerified = true,
        unverifiedEmail = unverifiedEmail,
        unverifiedUserId = unverifiedUserId,
        error = null
    )
}
```

### 4. `AuthViewModel.resendVerificationEmail()` (new function)

Added function to resend verification email:

```kotlin
fun resendVerificationEmail(
    onSuccess: () -> Unit = {},
    onError: (String) -> Unit = {}
) {
    // Uses EmailRepository.requestEmailVerification()
}
```

### 5. `LoginScreen.kt` (lines 180-265)

Added `AlertDialog` that shows when `loginState.emailNotVerified` is true:

- Icon: Email icon
- Title: "Vérifiez votre email"
- Message: Explains that email verification is required
- Shows the email address that needs verification
- "Compris" button to dismiss
- "Renvoyer l'email" button to resend verification email
- Shows success/error feedback for resend action

### 6. `MotiumNavHost.kt` (lines 143-151) - BUGFIX #2

Fixed the issue where the dialog appeared and disappeared immediately.

**Root cause:** The `LaunchedEffect` was calling `resetLoginState()` when `!authState.isAuthenticated`, which reset `emailNotVerified` to false immediately after it was set.

**Fix:**
```kotlin
// BUGFIX: Don't reset login state if email verification dialog is showing
// This prevents the dialog from disappearing immediately after being shown
if (!loginState.emailNotVerified) {
    authViewModel.resetLoginState()
}
```

Also added `loginState.emailNotVerified` to the `LaunchedEffect` key to observe changes.

## Files Modified

| File | Lines Changed | Type |
|------|---------------|------|
| `SupabaseAuthRepository.kt` | +13 | Logic |
| `AuthViewModel.kt` | +65 | Logic + State |
| `LoginScreen.kt` | +85 | UI |
| `MotiumNavHost.kt` | +6 | Navigation bugfix |

## Testing

- [x] Code compiles successfully
- [ ] Manual testing needed: Register new account, don't verify, try to login
- [ ] Verify dialog appears with correct email
- [ ] Verify dialog stays visible (doesn't flash and disappear)
- [ ] Verify "Resend email" button works
- [ ] Verify user can dismiss dialog and retry after verifying

## Regression Risk

**LOW** - Changes are isolated to:
- Login flow only (not signup, logout, or session restoration)
- New state fields with default values (no impact on existing state)
- New UI element that only appears in specific error case
- Navigation reset condition only checks for email verification dialog

## Notes

- The `EMAIL_NOT_VERIFIED` error format includes email and userId to enable resend functionality
- The user is signed out after failed verification check to prevent session persistence
- The existing `EmailRepository.requestEmailVerification()` is reused for resending emails
- The navigation `LaunchedEffect` now observes `loginState.emailNotVerified` and skips `resetLoginState()` when dialog should be visible
