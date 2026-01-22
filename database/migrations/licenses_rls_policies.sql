-- =============================================================================
-- MIGRATION: ADD RLS POLICIES FOR LICENSES TABLE
-- =============================================================================
-- This migration adds Row Level Security policies for the licenses table.
-- Without these policies, users cannot access their licenses via Supabase.
--
-- Run this in the Supabase SQL Editor.
-- =============================================================================

-- =============================================================================
-- 1. ENABLE RLS ON LICENSES TABLE
-- =============================================================================
ALTER TABLE licenses ENABLE ROW LEVEL SECURITY;

-- =============================================================================
-- 2. DROP EXISTING POLICIES (IF ANY)
-- =============================================================================
DROP POLICY IF EXISTS "Pro owners can view their licenses" ON licenses;
DROP POLICY IF EXISTS "Pro owners can insert licenses" ON licenses;
DROP POLICY IF EXISTS "Pro owners can update their licenses" ON licenses;
DROP POLICY IF EXISTS "Pro owners can delete their licenses" ON licenses;
DROP POLICY IF EXISTS "Licensed users can view their assigned license" ON licenses;
DROP POLICY IF EXISTS "Service role can manage all licenses" ON licenses;

-- =============================================================================
-- 3. CREATE POLICIES FOR PRO ACCOUNT OWNERS
-- =============================================================================

-- Pro account owners can SELECT licenses belonging to their pro_account
CREATE POLICY "Pro owners can view their licenses" ON licenses
    FOR SELECT
    USING (
        pro_account_id IN (
            SELECT pa.id FROM pro_accounts pa
            JOIN users u ON pa.user_id = u.id
            WHERE u.auth_id = auth.uid()
        )
    );

-- Pro account owners can INSERT new licenses for their pro_account
CREATE POLICY "Pro owners can insert licenses" ON licenses
    FOR INSERT
    WITH CHECK (
        pro_account_id IN (
            SELECT pa.id FROM pro_accounts pa
            JOIN users u ON pa.user_id = u.id
            WHERE u.auth_id = auth.uid()
        )
    );

-- Pro account owners can UPDATE licenses belonging to their pro_account
CREATE POLICY "Pro owners can update their licenses" ON licenses
    FOR UPDATE
    USING (
        pro_account_id IN (
            SELECT pa.id FROM pro_accounts pa
            JOIN users u ON pa.user_id = u.id
            WHERE u.auth_id = auth.uid()
        )
    )
    WITH CHECK (
        pro_account_id IN (
            SELECT pa.id FROM pro_accounts pa
            JOIN users u ON pa.user_id = u.id
            WHERE u.auth_id = auth.uid()
        )
    );

-- Pro account owners can DELETE licenses from their pro_account
CREATE POLICY "Pro owners can delete their licenses" ON licenses
    FOR DELETE
    USING (
        pro_account_id IN (
            SELECT pa.id FROM pro_accounts pa
            JOIN users u ON pa.user_id = u.id
            WHERE u.auth_id = auth.uid()
        )
    );

-- =============================================================================
-- 4. CREATE POLICY FOR LICENSED USERS (COLLABORATORS)
-- =============================================================================

-- Users with an assigned license can view their own license
CREATE POLICY "Licensed users can view their assigned license" ON licenses
    FOR SELECT
    USING (
        linked_account_id IN (
            SELECT id FROM users WHERE auth_id = auth.uid()
        )
    );

-- =============================================================================
-- 5. SECURITY: PREVENT UNAUTHORIZED LINKED_ACCOUNT_ID MODIFICATIONS
-- =============================================================================
-- A Pro can only assign licenses to users who:
-- 1. Have an active company_link with their pro_account
-- 2. OR the license is being unassigned (linked_account_id = NULL)
--
-- This prevents:
-- - "Stealing" licenses by assigning to arbitrary users
-- - Assigning licenses to users not in the company

CREATE OR REPLACE FUNCTION check_license_assignment_authorization()
RETURNS TRIGGER AS $$
BEGIN
    -- If linked_account_id is being set (assignment)
    IF NEW.linked_account_id IS NOT NULL AND
       (OLD.linked_account_id IS NULL OR OLD.linked_account_id != NEW.linked_account_id) THEN

        -- Check that the target user is either:
        -- 1. The owner of the pro_account (can self-assign)
        -- 2. An active member with a company_link
        IF NOT EXISTS (
            -- Case 1: Target user is the pro_account owner
            SELECT 1 FROM pro_accounts pa
            WHERE pa.id = NEW.pro_account_id
            AND pa.user_id = NEW.linked_account_id
        ) AND NOT EXISTS (
            -- Case 2: Target user has an active company_link
            SELECT 1 FROM company_links cl
            WHERE cl.linked_pro_account_id = NEW.pro_account_id
            AND cl.user_id = NEW.linked_account_id
            AND cl.status = 'ACTIVE'
        ) THEN
            RAISE EXCEPTION 'Cannot assign license: user % is not an active member of pro_account %',
                NEW.linked_account_id, NEW.pro_account_id;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS check_license_assignment ON licenses;
CREATE TRIGGER check_license_assignment
    BEFORE INSERT OR UPDATE ON licenses
    FOR EACH ROW
    EXECUTE FUNCTION check_license_assignment_authorization();

-- =============================================================================
-- 6. VERIFICATION QUERIES
-- =============================================================================

-- Verify RLS is enabled
SELECT tablename, rowsecurity
FROM pg_tables
WHERE schemaname = 'public'
AND tablename = 'licenses';
-- Expected: rowsecurity = true

-- Verify policies are created
SELECT
    schemaname,
    tablename,
    policyname,
    permissive,
    cmd,
    qual
FROM pg_policies
WHERE tablename = 'licenses'
ORDER BY cmd;
-- Expected: 5 policies (4 for owners, 1 for licensed users)

-- =============================================================================
-- MIGRATION COMPLETE
-- =============================================================================
-- After running this migration:
-- 1. Pro account owners can manage their licenses
-- 2. Collaborators can view their assigned licenses
-- 3. Data is properly isolated between users
-- =============================================================================
