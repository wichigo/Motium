-- =============================================================================
-- MIGRATION: ADD MISSING RLS POLICIES FOR EXPENSES_TRIPS, PRO_ACCOUNTS, COMPANY_LINKS
-- =============================================================================
-- Date: 2026-01-19
-- Description: Adds Row Level Security policies for tables that were missing them.
-- This fixes the security gap identified in the Ralph Wiggum audit.
--
-- Tables affected:
-- - expenses_trips: User expenses (CRUD by owner only)
-- - pro_accounts: Pro company accounts (read by owner, members; write by owner)
-- - company_links: Links between employees and companies (read by both parties)
-- - vehicles: Vehicle table (also missing proper RLS)
--
-- Run this in the Supabase SQL Editor.
-- =============================================================================

-- =============================================================================
-- 1. EXPENSES_TRIPS RLS POLICIES
-- =============================================================================
-- Users can only CRUD their own expenses

ALTER TABLE expenses_trips ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can view own expenses" ON expenses_trips;
DROP POLICY IF EXISTS "Users can insert own expenses" ON expenses_trips;
DROP POLICY IF EXISTS "Users can update own expenses" ON expenses_trips;
DROP POLICY IF EXISTS "Users can delete own expenses" ON expenses_trips;

-- SELECT: Users can view their own expenses
CREATE POLICY "Users can view own expenses" ON expenses_trips
FOR SELECT
USING (
    user_id IN (SELECT id FROM users WHERE auth_id = auth.uid())
);

-- INSERT: Users can insert their own expenses
CREATE POLICY "Users can insert own expenses" ON expenses_trips
FOR INSERT
WITH CHECK (
    user_id IN (SELECT id FROM users WHERE auth_id = auth.uid())
);

-- UPDATE: Users can update their own expenses
CREATE POLICY "Users can update own expenses" ON expenses_trips
FOR UPDATE
USING (
    user_id IN (SELECT id FROM users WHERE auth_id = auth.uid())
);

-- DELETE: Users can delete their own expenses
CREATE POLICY "Users can delete own expenses" ON expenses_trips
FOR DELETE
USING (
    user_id IN (SELECT id FROM users WHERE auth_id = auth.uid())
);

-- =============================================================================
-- 2. PRO_ACCOUNTS RLS POLICIES
-- =============================================================================
-- Pro accounts can be:
-- - Read by the owner
-- - Read by linked employees (via company_links)
-- - Modified only by the owner

ALTER TABLE pro_accounts ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Pro owner can view their account" ON pro_accounts;
DROP POLICY IF EXISTS "Pro owner can update their account" ON pro_accounts;
DROP POLICY IF EXISTS "Linked employees can view pro account" ON pro_accounts;

-- SELECT: Owner can view their pro account
CREATE POLICY "Pro owner can view their account" ON pro_accounts
FOR SELECT
USING (
    user_id IN (SELECT id FROM users WHERE auth_id = auth.uid())
);

-- SELECT: Linked employees can view the pro account they're linked to
CREATE POLICY "Linked employees can view pro account" ON pro_accounts
FOR SELECT
USING (
    id IN (
        SELECT cl.linked_pro_account_id
        FROM company_links cl
        JOIN users u ON cl.user_id = u.id
        WHERE u.auth_id = auth.uid()
        AND cl.status = 'ACTIVE'
    )
);

-- UPDATE: Only owner can update
CREATE POLICY "Pro owner can update their account" ON pro_accounts
FOR UPDATE
USING (
    user_id IN (SELECT id FROM users WHERE auth_id = auth.uid())
)
WITH CHECK (
    user_id IN (SELECT id FROM users WHERE auth_id = auth.uid())
);

-- INSERT: Only owner can create (user_id must match)
CREATE POLICY "Users can create their pro account" ON pro_accounts
FOR INSERT
WITH CHECK (
    user_id IN (SELECT id FROM users WHERE auth_id = auth.uid())
);

-- DELETE: No delete allowed (soft delete via status change)
-- No policy = no delete through RLS

-- =============================================================================
-- 3. COMPANY_LINKS RLS POLICIES
-- =============================================================================
-- Company links can be:
-- - Read by the employee (user_id)
-- - Read by the Pro owner
-- - Modified by the Pro owner (invite, status changes)
-- - Limited modification by employee (sharing preferences)

ALTER TABLE company_links ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Employees can view their links" ON company_links;
DROP POLICY IF EXISTS "Pro owners can view their links" ON company_links;
DROP POLICY IF EXISTS "Pro owners can insert links" ON company_links;
DROP POLICY IF EXISTS "Pro owners can update links" ON company_links;
DROP POLICY IF EXISTS "Employees can update sharing preferences" ON company_links;

-- SELECT: Employees can view their own company links
CREATE POLICY "Employees can view their links" ON company_links
FOR SELECT
USING (
    user_id IN (SELECT id FROM users WHERE auth_id = auth.uid())
);

-- SELECT: Pro owners can view all links to their pro account
CREATE POLICY "Pro owners can view their links" ON company_links
FOR SELECT
USING (
    linked_pro_account_id IN (
        SELECT pa.id
        FROM pro_accounts pa
        JOIN users u ON pa.user_id = u.id
        WHERE u.auth_id = auth.uid()
    )
);

-- INSERT: Pro owners can create invitation links
CREATE POLICY "Pro owners can insert links" ON company_links
FOR INSERT
WITH CHECK (
    linked_pro_account_id IN (
        SELECT pa.id
        FROM pro_accounts pa
        JOIN users u ON pa.user_id = u.id
        WHERE u.auth_id = auth.uid()
    )
);

-- UPDATE: Pro owners can update links (status, etc.)
CREATE POLICY "Pro owners can update links" ON company_links
FOR UPDATE
USING (
    linked_pro_account_id IN (
        SELECT pa.id
        FROM pro_accounts pa
        JOIN users u ON pa.user_id = u.id
        WHERE u.auth_id = auth.uid()
    )
);

-- UPDATE: Employees can update their own sharing preferences
CREATE POLICY "Employees can update sharing preferences" ON company_links
FOR UPDATE
USING (
    user_id IN (SELECT id FROM users WHERE auth_id = auth.uid())
)
WITH CHECK (
    user_id IN (SELECT id FROM users WHERE auth_id = auth.uid())
);

-- DELETE: No direct delete (soft delete via unlinked_at)

-- =============================================================================
-- 4. VEHICLES RLS POLICIES
-- =============================================================================
-- Vehicles can only be CRUD by their owner

ALTER TABLE vehicles ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can view own vehicles" ON vehicles;
DROP POLICY IF EXISTS "Users can insert own vehicles" ON vehicles;
DROP POLICY IF EXISTS "Users can update own vehicles" ON vehicles;
DROP POLICY IF EXISTS "Users can delete own vehicles" ON vehicles;

-- SELECT: Users can view their own vehicles
CREATE POLICY "Users can view own vehicles" ON vehicles
FOR SELECT
USING (
    user_id IN (SELECT id FROM users WHERE auth_id = auth.uid())
);

-- INSERT: Users can insert their own vehicles
CREATE POLICY "Users can insert own vehicles" ON vehicles
FOR INSERT
WITH CHECK (
    user_id IN (SELECT id FROM users WHERE auth_id = auth.uid())
);

-- UPDATE: Users can update their own vehicles
CREATE POLICY "Users can update own vehicles" ON vehicles
FOR UPDATE
USING (
    user_id IN (SELECT id FROM users WHERE auth_id = auth.uid())
);

-- DELETE: Users can delete their own vehicles
CREATE POLICY "Users can delete own vehicles" ON vehicles
FOR DELETE
USING (
    user_id IN (SELECT id FROM users WHERE auth_id = auth.uid())
);

-- =============================================================================
-- 5. VERIFICATION QUERIES
-- =============================================================================

-- Verify RLS is enabled on all tables
SELECT tablename, rowsecurity
FROM pg_tables
WHERE schemaname = 'public'
AND tablename IN ('expenses_trips', 'pro_accounts', 'company_links', 'vehicles');
-- Expected: rowsecurity = true for all

-- Verify policies are created
SELECT
    tablename,
    policyname,
    cmd
FROM pg_policies
WHERE tablename IN ('expenses_trips', 'pro_accounts', 'company_links', 'vehicles')
ORDER BY tablename, cmd;

-- =============================================================================
-- MIGRATION COMPLETE
-- =============================================================================
-- After running this migration:
-- 1. expenses_trips: Users can only access their own expenses
-- 2. pro_accounts: Owners can CRUD, employees can read
-- 3. company_links: Both parties can read, Pro can manage, employee can update prefs
-- 4. vehicles: Users can only access their own vehicles
-- =============================================================================
