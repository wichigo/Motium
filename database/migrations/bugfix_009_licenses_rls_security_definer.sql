-- =============================================================================
-- BUGFIX: LICENSES RLS POLICY CANNOT ACCESS USERS TABLE DUE TO RLS
-- =============================================================================
-- Problem: The "Licensed users can view their assigned license" policy uses:
--   linked_account_id IN (SELECT id FROM users WHERE auth_id = auth.uid())
--
-- This subquery on `users` is subject to the `users` table's RLS.
-- Even though the user should be able to see their own row, the RLS
-- evaluation order can cause this to fail.
--
-- Solution: Use a SECURITY DEFINER function to get the user's public.users.id
-- bypassing RLS for that specific lookup.
-- =============================================================================

-- Step 1: Make get_user_id_from_auth() a SECURITY DEFINER function
-- This allows the function to bypass RLS when looking up the user's ID
CREATE OR REPLACE FUNCTION get_user_id_from_auth()
RETURNS uuid
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT id FROM public.users WHERE auth_id = auth.uid() LIMIT 1;
$$;

-- Step 2: Drop the problematic policy
DROP POLICY IF EXISTS "Licensed users can view their assigned license" ON licenses;

-- Step 3: Recreate the policy using the SECURITY DEFINER function
-- This policy allows users to view licenses assigned to them
CREATE POLICY "Licensed users can view their assigned license" ON licenses
    FOR SELECT
    USING (
        linked_account_id = get_user_id_from_auth()
    );

-- =============================================================================
-- VERIFICATION
-- =============================================================================

-- Verify the function is SECURITY DEFINER
SELECT
    proname as function_name,
    CASE WHEN prosecdef THEN 'SECURITY DEFINER' ELSE 'SECURITY INVOKER' END as security_type
FROM pg_proc
WHERE proname = 'get_user_id_from_auth'
AND pronamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public');
-- Expected: get_user_id_from_auth | SECURITY DEFINER

-- Verify the policy exists
SELECT policyname, cmd, qual::text as using_clause
FROM pg_policies
WHERE tablename = 'licenses'
AND policyname = 'Licensed users can view their assigned license';
-- Expected: Should show the policy with get_user_id_from_auth() in USING clause

-- =============================================================================
-- MIGRATION COMPLETE
-- =============================================================================
-- After this migration:
-- - Licensed users can properly view their assigned licenses
-- - The RLS policy uses a SECURITY DEFINER function to bypass RLS loops
-- - Pro owners still have full access via their existing policies
-- =============================================================================
