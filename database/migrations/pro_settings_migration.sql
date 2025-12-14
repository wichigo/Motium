-- =============================================================================
-- MOTIUM PRO - SIMPLIFIED MIGRATION
-- =============================================================================
-- This migration:
-- 1. Adds departments to pro_accounts table
-- 2. Adds favorite_colors to users table (max 5 favorite colors)
-- 3. Adds department field to users table (for employee assignment)
-- 4. Drops the unused settings table
-- Run this in the Supabase SQL Editor.
-- =============================================================================

-- =============================================================================
-- 1. ADD DEPARTMENTS TO PRO_ACCOUNTS TABLE
-- =============================================================================
-- Departments list (JSON array of strings) for organizing employees

ALTER TABLE pro_accounts ADD COLUMN IF NOT EXISTS departments JSONB DEFAULT '[]'::jsonb;

-- =============================================================================
-- 2. ADD FAVORITE_COLORS TO USERS TABLE
-- =============================================================================
-- App customization - up to 5 favorite colors per user (JSON array of hex strings)
-- Example: ["#16a34a", "#3b82f6", "#ef4444", "#f59e0b", "#8b5cf6"]

ALTER TABLE users ADD COLUMN IF NOT EXISTS favorite_colors JSONB DEFAULT '[]'::jsonb;

-- =============================================================================
-- 3. ADD DEPARTMENT FIELD TO USERS TABLE
-- =============================================================================
-- Department assigned by Pro account to linked employees

ALTER TABLE users ADD COLUMN IF NOT EXISTS department TEXT;

-- =============================================================================
-- 4. DROP UNUSED SETTINGS TABLE
-- =============================================================================
-- The settings table is not used. Data is stored in:
-- - auto_tracking_settings: for tracking mode
-- - work_schedules: for work hours
-- - vehicles: for default vehicle (isDefault)
-- - users: for other preferences

-- First, drop dependent objects
DROP TRIGGER IF EXISTS update_settings_updated_at ON settings;
DROP POLICY IF EXISTS "Users can view own settings" ON settings;
DROP POLICY IF EXISTS "Users can manage own settings" ON settings;
DROP INDEX IF EXISTS idx_settings_user_id;

-- Now drop the table
DROP TABLE IF EXISTS settings;

-- =============================================================================
-- 5. HELPER FUNCTIONS
-- =============================================================================

-- Function to get departments for a pro account
CREATE OR REPLACE FUNCTION get_pro_departments(p_pro_account_id UUID)
RETURNS JSONB AS $$
DECLARE
    v_departments JSONB;
BEGIN
    SELECT departments INTO v_departments
    FROM pro_accounts
    WHERE id = p_pro_account_id;

    RETURN COALESCE(v_departments, '[]'::jsonb);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Function to update departments for a pro account
CREATE OR REPLACE FUNCTION update_pro_departments(
    p_pro_account_id UUID,
    p_departments JSONB
)
RETURNS BOOLEAN AS $$
BEGIN
    UPDATE pro_accounts
    SET departments = p_departments
    WHERE id = p_pro_account_id;

    RETURN FOUND;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- =============================================================================
-- MIGRATION COMPLETE
-- =============================================================================
-- Changes made:
--
-- pro_accounts table:
--   + departments (JSONB): List of department names
--
-- users table:
--   + favorite_colors (JSONB): Up to 5 favorite colors (hex strings)
--   + department (TEXT): Assigned department for employees
--
-- NOTE: name and phone_number already exist in users table, no need to add
--
-- Removed:
--   - settings table (unused, data is in other tables)
--
-- Functions added:
--   - get_pro_departments(pro_account_id)
--   - update_pro_departments(pro_account_id, departments)
-- =============================================================================
