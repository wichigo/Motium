package com.application.motium.data.local.entities

/**
 * Sync status for entities in offline-first architecture.
 * Tracks the synchronization state between local Room database and remote Supabase.
 */
enum class SyncStatus {
    /**
     * Entity is fully synchronized with the server.
     * Local and server versions match.
     */
    SYNCED,

    /**
     * Entity has local changes that need to be uploaded to the server.
     * Occurs after local create/update operations.
     */
    PENDING_UPLOAD,

    /**
     * Entity is marked for deletion locally, pending server confirmation.
     * Used for soft-delete with eventual consistency.
     */
    PENDING_DELETE,

    /**
     * Local and server versions conflict.
     * Requires conflict resolution (default: last-write-wins).
     */
    CONFLICT,

    /**
     * Sync operation failed with an error.
     * May require manual intervention or retry after backoff.
     */
    ERROR
}
