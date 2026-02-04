-- ============================================================================
-- BUGFIX 021: Fix affected user - Manual correction
-- ============================================================================
-- Run this AFTER applying bugfix_021_sync_trigger_lifetime_priority.sql
-- This fixes the specific user whose LIFETIME was overwritten
-- ============================================================================

-- Step 1: Verify the user has an active LIFETIME subscription record
SELECT
    u.id,
    u.email,
    u.subscription_type AS current_user_type,
    ss.id AS sub_id,
    ss.subscription_type,
    ss.status,
    ss.created_at
FROM users u
JOIN stripe_subscriptions ss ON ss.user_id = u.id
WHERE u.id = '2a723a12-c796-430b-b990-02495760b685'
ORDER BY ss.created_at DESC;

-- Step 2: Fix the user's subscription_type to LIFETIME
-- Only run if Step 1 confirms they have an active individual_lifetime subscription
UPDATE users
SET
    subscription_type = 'LIFETIME',
    subscription_expires_at = NULL,
    updated_at = NOW()
WHERE id = '2a723a12-c796-430b-b990-02495760b685';

-- Step 3: Verify the fix
SELECT id, email, subscription_type, subscription_expires_at
FROM users
WHERE id = '2a723a12-c796-430b-b990-02495760b685';
