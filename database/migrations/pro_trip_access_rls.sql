-- Migration: Allow Pro account owners to view trips of their linked users
-- Date: 2025-12-17
-- Description: Adds RLS policy to trips table so Pro owners can access linked users' trips

-- ============================================================================
-- 1. DROP EXISTING POLICIES THAT MIGHT CONFLICT
-- ============================================================================
DROP POLICY IF EXISTS "Pro owners can view linked users trips" ON trips;
DROP POLICY IF EXISTS "Users can view own trips" ON trips;
DROP POLICY IF EXISTS "Users can insert own trips" ON trips;
DROP POLICY IF EXISTS "Users can update own trips" ON trips;
DROP POLICY IF EXISTS "Users can delete own trips" ON trips;

-- ============================================================================
-- 2. CREATE RLS POLICIES FOR TRIPS TABLE
-- ============================================================================

-- Enable RLS on trips table (if not already enabled)
ALTER TABLE trips ENABLE ROW LEVEL SECURITY;

-- Policy 1: Users can view their own trips
CREATE POLICY "Users can view own trips" ON trips
FOR SELECT
USING (auth.uid() = user_id);

-- Policy 2: Users can insert their own trips
CREATE POLICY "Users can insert own trips" ON trips
FOR INSERT
WITH CHECK (auth.uid() = user_id);

-- Policy 3: Users can update their own trips
CREATE POLICY "Users can update own trips" ON trips
FOR UPDATE
USING (auth.uid() = user_id);

-- Policy 4: Users can delete their own trips
CREATE POLICY "Users can delete own trips" ON trips
FOR DELETE
USING (auth.uid() = user_id);

-- Policy 5: Pro account owners can view trips of their linked users
-- This allows a Pro owner to SELECT trips where:
-- - The trip's user_id belongs to a user linked to the Pro owner's account
-- - The link status is ACTIVE
-- - The company_link has sharing enabled for that trip type
CREATE POLICY "Pro owners can view linked users trips" ON trips
FOR SELECT
USING (
    -- Either the trip belongs to the current user
    auth.uid() = user_id
    OR
    -- Or the current user is the owner of a Pro account that the trip's user is linked to
    EXISTS (
        SELECT 1
        FROM company_links cl
        JOIN pro_accounts pa ON cl.linked_pro_account_id = pa.id
        WHERE cl.user_id = trips.user_id
        AND pa.user_id = auth.uid()
        AND cl.status = 'ACTIVE'
        -- Check sharing preferences on company_links based on trip type
        AND (
            (trips.type = 'PROFESSIONAL' AND cl.share_professional_trips = TRUE)
            OR
            (trips.type = 'PERSONAL' AND cl.share_personal_trips = TRUE)
        )
    )
);

-- ============================================================================
-- 3. VERIFICATION QUERY
-- ============================================================================
-- Run this to verify the policies are created:
-- SELECT schemaname, tablename, policyname, permissive, roles, cmd, qual, with_check
-- FROM pg_policies
-- WHERE tablename = 'trips';

-- ============================================================================
-- 4. ROLLBACK (if needed)
-- ============================================================================
-- To rollback this migration:
-- DROP POLICY IF EXISTS "Pro owners can view linked users trips" ON trips;
-- DROP POLICY IF EXISTS "Users can view own trips" ON trips;
-- DROP POLICY IF EXISTS "Users can insert own trips" ON trips;
-- DROP POLICY IF EXISTS "Users can update own trips" ON trips;
-- DROP POLICY IF EXISTS "Users can delete own trips" ON trips;
