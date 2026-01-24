# Fix: VERSION_CONFLICT Infinite Loop in sync_changes()

**Date**: 2026-01-24
**Status**: FIXED
**Issue**: Sync status stuck on "1 en attente" for 5+ minutes

## Root Cause

When `sync_changes()` returned VERSION_CONFLICT for a USER update:

1. `LocalUserRepository.updateUser()` increments local version (`newVersion = currentEntity.version + 1`)
2. This version is sent to server in the payload
3. Server has version 51, but client had older version (e.g., 50)
4. Server rejects with VERSION_CONFLICT because `client_version != server.version`
5. `resetEntityForPull()` deletes the pending operation and resets SyncMetadata timestamp to 0
6. **BUG**: The local `UserEntity.version` was NOT updated from the server's version
7. Any subsequent user interaction that triggers `updateUser()` reads the OLD local version and increments it
8. The cycle repeats forever

## Evidence

From `processed_sync_operations` table:
```
2026-01-24T14:12:41 - VERSION_CONFLICT (server v51)
2026-01-24T13:18:44 - VERSION_CONFLICT (server v51)
```

Logs showed:
```
Atomic sync: 1 pending operations, lastSync: 1769263587965
Push failed: USER:68c581f8-4f81-457c-9364-8d9ea3d183e0 - VERSION_CONFLICT: Version conflict (server v51)
Reset SyncMetadata.USER lastSyncTimestamp to 0 to force delta re-fetch
Delta sync completed successfully
```

But next sync still had 1 pending operation because local version was never updated.

## Solution

### 1. Added `resetForPull()` method to UserDao

```kotlin
@Query("""
    UPDATE users
    SET syncStatus = :syncStatus,
        serverUpdatedAt = :serverUpdatedAt,
        localUpdatedAt = :serverUpdatedAt,
        version = :serverVersion
    WHERE id = :userId
""")
suspend fun resetForPull(
    userId: String,
    syncStatus: String,
    serverUpdatedAt: Long,
    serverVersion: Int
)
```

### 2. Updated `resetEntityForPull()` to accept and use server version

```kotlin
private suspend fun resetEntityForPull(entityType: String, entityId: String, serverVersion: Int? = null) {
    // ...
    PendingOperationEntity.TYPE_USER -> {
        if (serverVersion != null) {
            // Use new resetForPull that updates version to prevent conflict loop
            database.userDao().resetForPull(entityId, SyncStatus.SYNCED.name, epochForForcePull, serverVersion)
            MotiumApplication.logger.i(
                "Reset USER:$entityId version to $serverVersion (from server)",
                TAG
            )
        } else {
            database.userDao().updateSyncStatus(entityId, SyncStatus.SYNCED.name, epochForForcePull)
        }
    }
    // ...
}
```

### 3. Updated `processPushResults()` to pass server version

```kotlin
"VERSION_CONFLICT" -> {
    // ...
    resetEntityForPull(op.entityType, op.entityId, result.serverVersion)
    // ...
}
```

## Files Modified

1. `app/src/main/java/com/application/motium/data/local/dao/UserDao.kt`
   - Added `resetForPull()` method

2. `app/src/main/java/com/application/motium/data/sync/DeltaSyncWorker.kt`
   - Updated `resetEntityForPull()` signature to accept `serverVersion: Int?`
   - Updated VERSION_CONFLICT handling to pass `result.serverVersion`

## Verification

After fix:
1. App launches with 0 pending operations
2. Full sync pulls 22 changes including 1 user change
3. Home screen shows "Synchronisé" (synced)
4. No more VERSION_CONFLICT loop

## Technical Note

The `SyncChangesRemoteDataSource.PushResult` already had `serverVersion` field populated by the SQL function `sync_changes()`. The fix was to use this information to update the local entity version.
