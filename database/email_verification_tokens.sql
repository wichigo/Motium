-- Email verification tokens table
-- Used for custom email verification via Resend (bypassing Supabase Auth limits)

CREATE TABLE IF NOT EXISTS email_verification_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE,
    email TEXT NOT NULL,
    token TEXT UNIQUE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    verified_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Indexes for fast lookups
CREATE INDEX IF NOT EXISTS idx_email_verification_tokens_token ON email_verification_tokens(token);
CREATE INDEX IF NOT EXISTS idx_email_verification_tokens_email ON email_verification_tokens(email);
CREATE INDEX IF NOT EXISTS idx_email_verification_tokens_user_id ON email_verification_tokens(user_id);

-- RLS policies
ALTER TABLE email_verification_tokens ENABLE ROW LEVEL SECURITY;

-- Only service role can manage tokens (Edge Functions)
CREATE POLICY "Service role can manage email verification tokens"
    ON email_verification_tokens
    FOR ALL
    USING (auth.role() = 'service_role')
    WITH CHECK (auth.role() = 'service_role');

-- Function to create email verification token (called by Edge Function)
CREATE OR REPLACE FUNCTION create_email_verification_token(
    p_user_id UUID,
    p_email TEXT
)
RETURNS TEXT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_token TEXT;
BEGIN
    -- Generate secure token
    v_token := encode(gen_random_bytes(32), 'hex');

    -- Invalidate any existing tokens for this email
    UPDATE email_verification_tokens
    SET expires_at = NOW()
    WHERE email = p_email AND verified_at IS NULL AND expires_at > NOW();

    -- Create new token (valid for 24 hours)
    INSERT INTO email_verification_tokens (user_id, email, token, expires_at)
    VALUES (p_user_id, p_email, v_token, NOW() + INTERVAL '24 hours');

    RETURN v_token;
END;
$$;

-- Function to verify email token
CREATE OR REPLACE FUNCTION verify_email_token(p_token TEXT)
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
    FROM email_verification_tokens
    WHERE token = p_token;

    -- Check if token exists
    IF v_record IS NULL THEN
        RETURN json_build_object('success', false, 'error', 'invalid_token');
    END IF;

    -- Check if already verified
    IF v_record.verified_at IS NOT NULL THEN
        RETURN json_build_object('success', false, 'error', 'already_verified', 'email', v_record.email);
    END IF;

    -- Check if expired
    IF v_record.expires_at < NOW() THEN
        RETURN json_build_object('success', false, 'error', 'token_expired');
    END IF;

    -- Mark as verified
    UPDATE email_verification_tokens
    SET verified_at = NOW()
    WHERE id = v_record.id;

    -- Update user's email_verified status in users table
    UPDATE users
    SET email_verified = true, updated_at = NOW()
    WHERE id = v_record.user_id;

    RETURN json_build_object(
        'success', true,
        'email', v_record.email,
        'user_id', v_record.user_id
    );
END;
$$;

-- Grant execute to service role
GRANT EXECUTE ON FUNCTION create_email_verification_token(UUID, TEXT) TO service_role;
GRANT EXECUTE ON FUNCTION verify_email_token(TEXT) TO service_role;
