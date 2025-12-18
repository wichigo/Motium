-- =============================================================================
-- MOTIUM PRO - SYNC COMPANY NAME TRIGGER
-- =============================================================================
-- This migration ensures company_links.company_name always matches
-- pro_accounts.company_name automatically.
--
-- Run this in the Supabase SQL Editor.
-- =============================================================================

-- =============================================================================
-- 1. TRIGGER FUNCTION: Sync company_name to company_links
-- =============================================================================
-- This function is called whenever pro_accounts.company_name is updated.
-- It updates all related company_links records.

CREATE OR REPLACE FUNCTION sync_company_name_to_links()
RETURNS TRIGGER AS $$
BEGIN
    -- Only proceed if company_name actually changed
    IF OLD.company_name IS DISTINCT FROM NEW.company_name THEN
        UPDATE company_links
        SET
            company_name = NEW.company_name,
            updated_at = NOW()
        WHERE linked_pro_account_id = NEW.id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- =============================================================================
-- 2. CREATE TRIGGER ON pro_accounts
-- =============================================================================
-- Fires after UPDATE on pro_accounts when company_name changes

DROP TRIGGER IF EXISTS trigger_sync_company_name ON pro_accounts;

CREATE TRIGGER trigger_sync_company_name
    AFTER UPDATE OF company_name ON pro_accounts
    FOR EACH ROW
    EXECUTE FUNCTION sync_company_name_to_links();

-- =============================================================================
-- 3. SYNC EXISTING DATA
-- =============================================================================
-- Update all existing company_links to match their pro_accounts.company_name

UPDATE company_links cl
SET
    company_name = pa.company_name,
    updated_at = NOW()
FROM pro_accounts pa
WHERE cl.linked_pro_account_id = pa.id
  AND cl.company_name IS DISTINCT FROM pa.company_name;

-- =============================================================================
-- 4. OPTIONAL: Prevent direct updates to company_links.company_name
-- =============================================================================
-- This trigger prevents manual updates to company_name in company_links.
-- The only way to change it is by updating pro_accounts.company_name.

CREATE OR REPLACE FUNCTION prevent_company_name_update()
RETURNS TRIGGER AS $$
BEGIN
    -- Allow the sync trigger to update (it sets updated_at at the same time)
    -- But prevent manual updates where only company_name changes
    IF OLD.company_name IS DISTINCT FROM NEW.company_name
       AND OLD.updated_at = NEW.updated_at THEN
        RAISE EXCEPTION 'Cannot directly update company_name in company_links. Update pro_accounts.company_name instead.';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trigger_prevent_company_name_update ON company_links;

CREATE TRIGGER trigger_prevent_company_name_update
    BEFORE UPDATE OF company_name ON company_links
    FOR EACH ROW
    EXECUTE FUNCTION prevent_company_name_update();

-- =============================================================================
-- MIGRATION COMPLETE
-- =============================================================================
-- Changes made:
--
-- Functions added:
--   - sync_company_name_to_links(): Syncs company_name when pro_accounts changes
--   - prevent_company_name_update(): Prevents direct edits to company_links.company_name
--
-- Triggers added:
--   - trigger_sync_company_name (on pro_accounts): Auto-sync on UPDATE
--   - trigger_prevent_company_name_update (on company_links): Block direct edits
--
-- Data fixed:
--   - All existing company_links.company_name synced with pro_accounts
-- =============================================================================
