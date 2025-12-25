-- ============================================
-- FIX: Update subscription_type for users with active licenses
-- ============================================
-- This script fixes the data inconsistency where users have active Pro licenses
-- but their subscription_type is still FREE/TRIAL/EXPIRED.
--
-- Run this ONCE to fix existing data, then deploy the trigger
-- (sync_licenses_trigger.sql) to prevent future inconsistencies.

-- Step 1: Show current state (for verification)
SELECT
    u.id,
    u.email,
    u.name,
    u.role,
    u.subscription_type,
    u.subscription_expires_at,
    l.id as license_id,
    l.status as license_status,
    l.linked_at
FROM users u
INNER JOIN licenses l ON l.linked_account_id = u.id
WHERE l.status = 'active'
ORDER BY u.email;

-- Step 2: Update subscription_type to LICENSED for all users with active licenses
UPDATE users
SET
    subscription_type = 'LICENSED',
    updated_at = NOW()
WHERE id IN (
    SELECT DISTINCT linked_account_id
    FROM licenses
    WHERE status = 'active'
    AND linked_account_id IS NOT NULL
)
AND subscription_type != 'LICENSED';

-- Step 3: Verify the fix
SELECT
    u.id,
    u.email,
    u.subscription_type as new_subscription_type,
    l.status as license_status
FROM users u
INNER JOIN licenses l ON l.linked_account_id = u.id
WHERE l.status = 'active'
ORDER BY u.email;

-- ============================================
-- SPECIFIC FIX for user wyldelphe petit
-- ============================================
-- If the above general fix doesn't work, use this specific query:

-- UPDATE users
-- SET
--     subscription_type = 'LICENSED',
--     updated_at = NOW()
-- WHERE id = '68c581f8-4f81-457c-9364-8d9ea3d183e0';

-- Verify specific user
-- SELECT id, email, subscription_type FROM users WHERE id = '68c581f8-4f81-457c-9364-8d9ea3d183e0';
