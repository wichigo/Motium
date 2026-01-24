# Fix: Sync Not Running When App is Backgrounded

**Date**: 2026-01-24
**Status**: FIXED (v2)
**Issue**: Immediate sync only triggers when going to HomeScreen, not when app is closed after settings change

## Root Cause

When the user modified data in settings and closed the app, `sync_changes()` was never called, even 10+ minutes later. But when reopening the app and going to HomeScreen, sync happened immediately.

The root cause was Android's battery optimizations:
1. **Doze Mode** (API 23+): Delays all background work when device is idle
2. **App Standby Buckets** (API 28+): Apps in "rare" bucket have work deferred for hours
3. **WorkManager default behavior**: Without `setExpedited()`, "immediate" work is just normal work

When the app was backgrounded, WorkManager's `OneTimeWorkRequest` was being delayed by these optimizations, only running when the user returned to the app (which woke it up).

## Solution

### 1. Added `setExpedited()` to immediate sync requests

In `OfflineFirstSyncManager.triggerImmediateSync()`:

```kotlin
// FIX (2026-01-24): Use setExpedited() to bypass Doze mode and App Standby restrictions
val request = OneTimeWorkRequestBuilder<DeltaSyncWorker>()
    .setConstraints(constraints)
    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
    .addTag(IMMEDIATE_SYNC_WORK_NAME)
    .build()
```

**Key**: `OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST` gracefully falls back to normal work if the expedited quota is exhausted, preventing crashes.

### 2. Added `getForegroundInfo()` to DeltaSyncWorker

Expedited work on API 31+ (Android 12+) requires `getForegroundInfo()` to provide a notification when the system promotes the work to a foreground service:

```kotlin
override suspend fun getForegroundInfo(): ForegroundInfo {
    val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    val channel = NotificationChannel(
        NOTIFICATION_CHANNEL_ID,
        "Synchronisation",
        NotificationManager.IMPORTANCE_LOW
    ).apply {
        description = "Synchronisation des données en arrière-plan"
        setShowBadge(false)
    }
    notificationManager.createNotificationChannel(channel)

    val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_popup_sync)
        .setContentTitle("Motium")
        .setContentText("Synchronisation en cours...")
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .build()

    return ForegroundInfo(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    )
}
```

## Files Modified

1. `app/src/main/java/com/application/motium/data/sync/OfflineFirstSyncManager.kt`
   - Added import for `OutOfQuotaPolicy`
   - Updated `triggerImmediateSync()` to use `setExpedited()`

2. `app/src/main/java/com/application/motium/data/sync/DeltaSyncWorker.kt`
   - Added imports: `NotificationChannel`, `NotificationManager`, `NotificationCompat`, `ForegroundInfo`, `ServiceInfo`
   - Added notification constants to companion object
   - Added `getForegroundInfo()` method

3. `app/src/main/java/com/application/motium/presentation/MainActivity.kt` **(v2 - CRITICAL)**
   - Added import for `OfflineFirstSyncManager`
   - Added `triggerImmediateSync()` call in `onResume()` when user is authenticated
   - This ensures sync runs when app returns to foreground from ANY screen, not just HomeScreen

## AndroidManifest Requirements (already present)

- `<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />`
- `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />`

## How Expedited Work Behaves

| API Level | Behavior |
|-----------|----------|
| API 31+ (Android 12+) | Runs as foreground service with notification |
| API 30 and below | Runs immediately bypassing Doze restrictions |

## Verification

After fix:
1. Modify `consider_full_distance` in settings
2. Close app (swipe away or press home)
3. Check logs - sync should run within seconds, not 10+ minutes
4. Verify in database that change is synced

## Technical Notes

- Rate limiting (1 minute) is still in place to prevent battery drain
- The notification is only shown briefly during sync (IMPORTANCE_LOW = silent)
- Falls back gracefully if expedited quota is exhausted
