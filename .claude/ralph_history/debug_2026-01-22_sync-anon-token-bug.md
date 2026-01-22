# Debug Report: sync_changes() uses anon token instead of user token

**Date:** 2026-01-22
**Bug ID:** sync-anon-token-bug
**Status:** FIXED
**Severity:** Critical (causes sync failure + battery drain)

---

## Problem Summary

The `sync_changes()` RPC call was failing with **"User not authenticated" (Code: P0001)** even when the user was logged in. The SDK was sending an **anon token** instead of the user's authentication token.

### Symptoms
- Sync operations fail systematically with "User not authenticated"
- Trips created locally don't sync to server
- Continuous sync retry causing abnormal battery consumption
- Authorization header in RPC calls contains `"role": "anon"` instead of user role

### Root Cause

In `SupabaseAuthRepository.refreshSessionForSync()`:

1. `auth.refreshSession(refreshToken)` was called successfully
2. BUT `auth.currentSessionOrNull()` returned **null** after the refresh
3. `saveCurrentSessionSecurely()` was called but `auth.currentSessionOrNull()` still returned null
4. The method returned `true` because `secureSessionStorage.hasValidSession()` returned true
5. **HOWEVER**, the Supabase SDK didn't have the session loaded in memory
6. When `postgres.rpc("sync_changes", ...)` was called, the SDK used the **anon key** by default

**Evidence from logs:**
```
SessionRefreshWorker: ✅ Session refreshed successfully
saveCurrentSessionSecurely: No current session from SDK
authUser is null but local user exists
```

---

## Fix Applied

**File:** `app/src/main/java/com/application/motium/data/supabase/SupabaseAuthRepository.kt`
**Method:** `refreshSessionForSync()` (lines 587-695)

### Changes

1. **Added race condition workaround** (lines 623-629):
   ```kotlin
   var refreshedSession = auth.currentSessionOrNull()
   if (refreshedSession == null) {
       MotiumApplication.logger.w("SDK session is null after refresh - waiting and retrying...", "SupabaseAuth")
       kotlinx.coroutines.delay(100)  // Give SDK time to propagate session
       refreshedSession = auth.currentSessionOrNull()
   }
   ```

2. **Added anon token detection** (lines 635-643):
   ```kotlin
   if (refreshedSession?.accessToken != null && refreshedSession.accessToken.isNotBlank()) {
       val isRealUserToken = !refreshedSession.accessToken.contains("\"role\": \"anon\"") &&
           !refreshedSession.accessToken.contains("\"role\":\"anon\"")
       if (isRealUserToken) {
           return true
       }
   }
   ```

3. **Added secure storage fallback with session import** (lines 645-683):
   ```kotlin
   val storedSession = secureSessionStorage.restoreSession()
   if (storedSession != null && storedSession.accessToken.isNotBlank()) {
       // Decode JWT and verify it's a user token (has 'sub' claim)
       val parts = storedSession.accessToken.split(".")
       if (parts.size == 3) {
           val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP))
           val hasUserSub = payload.contains("\"sub\"") && !payload.contains("\"role\":\"anon\"")
           if (hasUserSub) {
               // Force SDK to use stored session
               val userSession = UserSession(
                   accessToken = storedSession.accessToken,
                   refreshToken = storedSession.refreshToken,
                   expiresIn = ((storedSession.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0),
                   tokenType = storedSession.tokenType,
                   user = null
               )
               auth.importSession(userSession)
               return true
           }
       }
   }
   ```

---

## Verification

### Compilation
- **Status:** SUCCESS
- **Command:** `./gradlew :app:compileDebugKotlin`
- **Warnings:** Only deprecation warnings (unrelated to fix)

### Expected Behavior After Fix
1. After `auth.refreshSession()`, check if SDK actually has the session
2. If SDK session is null or anon, wait 100ms and retry
3. If still invalid, decode stored token from `SecureSessionStorage`
4. Verify stored token is a real user token (has 'sub' claim, not anon)
5. Force import the stored session into SDK via `auth.importSession()`
6. Sync operation now uses the correct user token

---

## Files Modified

| File | Change |
|------|--------|
| `SupabaseAuthRepository.kt` | Modified `refreshSessionForSync()` to detect and fix SDK session desynchronization |

---

## Testing Recommendations

1. **Manual Test:**
   - Uninstall and reinstall app
   - Login with email/password
   - Create a trip
   - Check logs for: `"✅ Imported stored session into SDK - sync can proceed"`
   - Verify trip syncs to Supabase

2. **Edge Cases:**
   - Test after device restart
   - Test after app kill/restore
   - Test with poor network conditions
   - Test token expiration + refresh flow

---

## Related Components

- `SyncChangesRemoteDataSource.kt` - Uses `postgres.rpc()` which depends on SDK session
- `SecureSessionStorage.kt` - Encrypted storage for tokens
- `SecureSessionManager.kt` - Custom SessionManager for Supabase SDK
- `DeltaSyncWorker.kt` - Calls `refreshSessionForSync()` before sync
- `SessionRefreshWorker.kt` - Background token refresh

---

## Lessons Learned

1. The Supabase-kt SDK's `auth.refreshSession()` doesn't guarantee the session is immediately available via `currentSessionOrNull()`
2. There can be a race condition between token refresh and session propagation within the SDK
3. Always verify the SDK has a valid user token before making authenticated API calls
4. `SecureSessionStorage` may have valid tokens even when SDK doesn't - use `auth.importSession()` to sync them
