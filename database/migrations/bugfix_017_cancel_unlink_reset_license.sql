-- =============================================================================
-- BUGFIX 017: Reset license fields when unlink request is cancelled
-- =============================================================================
--
-- BUG: When a monthly license unlink request is cancelled via cancel_unlink_token(),
--      the license fields (unlink_requested_at, unlink_effective_at) are NOT reset.
--      This could cause accidental cancellation when the unlink_effective_at date passes.
--
-- FIX: Modify cancel_unlink_token() to also reset the license fields to NULL
--      when the unlink request is cancelled before the effective date.
--
-- AFFECTED FUNCTION: cancel_unlink_token(TEXT)
-- AFFECTED TABLE: licenses (unlink_requested_at, unlink_effective_at)
-- =============================================================================

-- Drop and recreate the function with the fix
CREATE OR REPLACE FUNCTION cancel_unlink_token(p_token TEXT)
RETURNS JSON
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_record RECORD;
    v_license_updated BOOLEAN := FALSE;
    v_company_link RECORD;
BEGIN
    -- Find token
    SELECT * INTO v_record
    FROM unlink_confirmation_tokens
    WHERE token = p_token;

    IF v_record IS NULL THEN
        RETURN json_build_object('success', false, 'error', 'invalid_token');
    END IF;

    IF v_record.confirmed_at IS NOT NULL THEN
        RETURN json_build_object('success', false, 'error', 'already_confirmed');
    END IF;

    IF v_record.cancelled_at IS NOT NULL THEN
        RETURN json_build_object('success', false, 'error', 'already_cancelled');
    END IF;

    -- Check if token is expired (optional: could still allow cancellation of expired tokens)
    IF v_record.expires_at < NOW() THEN
        RETURN json_build_object('success', false, 'error', 'token_expired');
    END IF;

    -- Get company_link to find the associated license
    SELECT * INTO v_company_link
    FROM company_links
    WHERE id = v_record.company_link_id;

    -- Mark token as cancelled
    UPDATE unlink_confirmation_tokens
    SET cancelled_at = NOW()
    WHERE id = v_record.id;

    -- ==========================================================================
    -- FIX: Reset license unlink fields
    -- ==========================================================================
    -- Find the license associated with this company_link and reset unlink fields
    -- The license is linked via:
    --   - license.linked_account_id = company_link.user_id (the employee)
    --   - license.pro_account_id = company_link.linked_pro_account_id (the company)
    -- ==========================================================================

    IF v_company_link IS NOT NULL AND v_company_link.user_id IS NOT NULL THEN
        UPDATE licenses
        SET
            unlink_requested_at = NULL,
            unlink_effective_at = NULL,
            updated_at = NOW()
        WHERE linked_account_id = v_company_link.user_id
          AND pro_account_id = v_company_link.linked_pro_account_id
          AND unlink_requested_at IS NOT NULL  -- Only if unlink was requested
          AND (unlink_effective_at IS NULL OR unlink_effective_at > NOW());  -- Only if not yet effective

        v_license_updated := FOUND;
    END IF;

    RETURN json_build_object(
        'success', true,
        'license_reset', v_license_updated
    );
END;
$$;

-- Ensure grants are in place
GRANT EXECUTE ON FUNCTION cancel_unlink_token(TEXT) TO service_role;

-- =============================================================================
-- VERIFICATION COMMENT
-- =============================================================================
-- After applying this migration, when cancel_unlink_token() is called:
-- 1. The token is marked as cancelled (cancelled_at = NOW())
-- 2. The associated license has its unlink fields reset:
--    - unlink_requested_at = NULL
--    - unlink_effective_at = NULL
-- 3. The license status remains 'active' (unchanged)
-- 4. The linked_account_id remains unchanged (employee stays linked)
--
-- This prevents accidental license cancellation when the effective date passes,
-- since the unlink_effective_at is now NULL and won't trigger the renewal process.
-- =============================================================================
