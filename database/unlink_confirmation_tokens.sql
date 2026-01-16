-- Unlink confirmation tokens table
-- Used to confirm account unlinking between collaborator and pro account
-- Prevents accidental unlinks by requiring email confirmation

CREATE TABLE IF NOT EXISTS unlink_confirmation_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_link_id UUID NOT NULL REFERENCES company_links(id) ON DELETE CASCADE,
    employee_user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    pro_account_id UUID NOT NULL REFERENCES pro_accounts(id) ON DELETE CASCADE,
    initiated_by TEXT NOT NULL CHECK (initiated_by IN ('employee', 'pro_account')),
    initiator_email TEXT NOT NULL,
    token TEXT UNIQUE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    confirmed_at TIMESTAMP WITH TIME ZONE,
    cancelled_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Indexes for fast lookups
CREATE INDEX IF NOT EXISTS idx_unlink_confirmation_tokens_token ON unlink_confirmation_tokens(token);
CREATE INDEX IF NOT EXISTS idx_unlink_confirmation_tokens_company_link_id ON unlink_confirmation_tokens(company_link_id);
CREATE INDEX IF NOT EXISTS idx_unlink_confirmation_tokens_employee_user_id ON unlink_confirmation_tokens(employee_user_id);

-- RLS policies
ALTER TABLE unlink_confirmation_tokens ENABLE ROW LEVEL SECURITY;

-- Only service role can manage tokens (Edge Functions)
CREATE POLICY "Service role can manage unlink confirmation tokens"
    ON unlink_confirmation_tokens
    FOR ALL
    USING (auth.role() = 'service_role')
    WITH CHECK (auth.role() = 'service_role');

-- Function to create unlink confirmation token
CREATE OR REPLACE FUNCTION create_unlink_confirmation_token(
    p_company_link_id UUID,
    p_initiated_by TEXT,
    p_initiator_email TEXT
)
RETURNS JSON
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_token TEXT;
    v_company_link RECORD;
    v_employee_email TEXT;
    v_pro_account RECORD;
BEGIN
    -- Get company link details
    SELECT cl.*, u.email as employee_email, u.display_name as employee_name
    INTO v_company_link
    FROM company_links cl
    JOIN users u ON u.id = cl.user_id
    WHERE cl.id = p_company_link_id AND cl.status = 'active';

    IF v_company_link IS NULL THEN
        RETURN json_build_object('success', false, 'error', 'company_link_not_found');
    END IF;

    -- Get pro account details
    SELECT pa.*, u.email as owner_email, u.display_name as owner_name
    INTO v_pro_account
    FROM pro_accounts pa
    JOIN users u ON u.id = pa.user_id
    WHERE pa.id = v_company_link.pro_account_id;

    IF v_pro_account IS NULL THEN
        RETURN json_build_object('success', false, 'error', 'pro_account_not_found');
    END IF;

    -- Generate secure token
    v_token := encode(gen_random_bytes(32), 'hex');

    -- Invalidate any existing pending tokens for this link
    UPDATE unlink_confirmation_tokens
    SET expires_at = NOW()
    WHERE company_link_id = p_company_link_id
      AND confirmed_at IS NULL
      AND cancelled_at IS NULL
      AND expires_at > NOW();

    -- Create new token (valid for 24 hours)
    INSERT INTO unlink_confirmation_tokens (
        company_link_id,
        employee_user_id,
        pro_account_id,
        initiated_by,
        initiator_email,
        token,
        expires_at
    )
    VALUES (
        p_company_link_id,
        v_company_link.user_id,
        v_company_link.pro_account_id,
        p_initiated_by,
        p_initiator_email,
        v_token,
        NOW() + INTERVAL '24 hours'
    );

    RETURN json_build_object(
        'success', true,
        'token', v_token,
        'employee_email', v_company_link.employee_email,
        'employee_name', v_company_link.employee_name,
        'company_name', v_pro_account.company_name,
        'pro_account_email', v_pro_account.owner_email,
        'initiated_by', p_initiated_by
    );
END;
$$;

-- Function to confirm unlink
CREATE OR REPLACE FUNCTION confirm_unlink_token(p_token TEXT)
RETURNS JSON
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_record RECORD;
    v_company_link RECORD;
BEGIN
    -- Find token
    SELECT * INTO v_record
    FROM unlink_confirmation_tokens
    WHERE token = p_token;

    -- Check if token exists
    IF v_record IS NULL THEN
        RETURN json_build_object('success', false, 'error', 'invalid_token');
    END IF;

    -- Check if already confirmed
    IF v_record.confirmed_at IS NOT NULL THEN
        RETURN json_build_object('success', false, 'error', 'already_confirmed');
    END IF;

    -- Check if cancelled
    IF v_record.cancelled_at IS NOT NULL THEN
        RETURN json_build_object('success', false, 'error', 'token_cancelled');
    END IF;

    -- Check if expired
    IF v_record.expires_at < NOW() THEN
        RETURN json_build_object('success', false, 'error', 'token_expired');
    END IF;

    -- Mark token as confirmed
    UPDATE unlink_confirmation_tokens
    SET confirmed_at = NOW()
    WHERE id = v_record.id;

    -- Deactivate the company link (soft delete)
    UPDATE company_links
    SET status = 'inactive', updated_at = NOW()
    WHERE id = v_record.company_link_id;

    -- Get employee email for notification
    SELECT u.email, u.display_name, pa.company_name
    INTO v_company_link
    FROM company_links cl
    JOIN users u ON u.id = cl.user_id
    JOIN pro_accounts pa ON pa.id = cl.pro_account_id
    WHERE cl.id = v_record.company_link_id;

    RETURN json_build_object(
        'success', true,
        'employee_email', v_company_link.email,
        'employee_name', v_company_link.display_name,
        'company_name', v_company_link.company_name,
        'initiated_by', v_record.initiated_by
    );
END;
$$;

-- Function to cancel unlink request
CREATE OR REPLACE FUNCTION cancel_unlink_token(p_token TEXT)
RETURNS JSON
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_record RECORD;
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

    -- Mark as cancelled
    UPDATE unlink_confirmation_tokens
    SET cancelled_at = NOW()
    WHERE id = v_record.id;

    RETURN json_build_object('success', true);
END;
$$;

-- Grant execute to service role
GRANT EXECUTE ON FUNCTION create_unlink_confirmation_token(UUID, TEXT, TEXT) TO service_role;
GRANT EXECUTE ON FUNCTION confirm_unlink_token(TEXT) TO service_role;
GRANT EXECUTE ON FUNCTION cancel_unlink_token(TEXT) TO service_role;
