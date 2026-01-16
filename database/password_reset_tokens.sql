-- Password Reset Tokens Table
-- Used for custom password reset flow with Resend emails
-- Tokens expire after 1 hour and can only be used once

CREATE TABLE IF NOT EXISTS public.password_reset_tokens (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE,
  email TEXT NOT NULL,
  token TEXT UNIQUE NOT NULL,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  used_at TIMESTAMP WITH TIME ZONE,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),

  -- Constraints
  CONSTRAINT token_not_empty CHECK (length(token) > 0),
  CONSTRAINT email_not_empty CHECK (length(email) > 0)
);

-- Indexes for fast lookup
CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_token ON public.password_reset_tokens(token);
CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_email ON public.password_reset_tokens(email);
CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_expires_at ON public.password_reset_tokens(expires_at);

-- Enable RLS
ALTER TABLE public.password_reset_tokens ENABLE ROW LEVEL SECURITY;

-- Policy: Only service role can access (called from Edge Functions)
CREATE POLICY "Service role only" ON public.password_reset_tokens
  FOR ALL
  USING (auth.role() = 'service_role');

-- Function to clean up expired tokens (run periodically)
CREATE OR REPLACE FUNCTION public.cleanup_expired_password_reset_tokens()
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
  deleted_count INTEGER;
BEGIN
  DELETE FROM public.password_reset_tokens
  WHERE expires_at < NOW() OR used_at IS NOT NULL;

  GET DIAGNOSTICS deleted_count = ROW_COUNT;
  RETURN deleted_count;
END;
$$;

-- Function to create a password reset token
CREATE OR REPLACE FUNCTION public.create_password_reset_token(
  p_email TEXT,
  p_token TEXT,
  p_expires_in_hours INTEGER DEFAULT 1
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
  v_user_id UUID;
  v_token_id UUID;
BEGIN
  -- Find user by email
  SELECT id INTO v_user_id
  FROM auth.users
  WHERE email = p_email;

  IF v_user_id IS NULL THEN
    RAISE EXCEPTION 'User not found with email: %', p_email;
  END IF;

  -- Invalidate any existing unused tokens for this user
  UPDATE public.password_reset_tokens
  SET used_at = NOW()
  WHERE user_id = v_user_id AND used_at IS NULL;

  -- Create new token
  INSERT INTO public.password_reset_tokens (user_id, email, token, expires_at)
  VALUES (v_user_id, p_email, p_token, NOW() + (p_expires_in_hours || ' hours')::INTERVAL)
  RETURNING id INTO v_token_id;

  RETURN v_token_id;
END;
$$;

-- Function to validate and consume a password reset token
CREATE OR REPLACE FUNCTION public.validate_password_reset_token(p_token TEXT)
RETURNS TABLE(user_id UUID, email TEXT, is_valid BOOLEAN, error_message TEXT)
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
  v_record RECORD;
BEGIN
  -- Find the token
  SELECT prt.user_id, prt.email, prt.expires_at, prt.used_at
  INTO v_record
  FROM public.password_reset_tokens prt
  WHERE prt.token = p_token;

  IF v_record IS NULL THEN
    RETURN QUERY SELECT NULL::UUID, NULL::TEXT, FALSE, 'Token invalide ou inexistant'::TEXT;
    RETURN;
  END IF;

  IF v_record.used_at IS NOT NULL THEN
    RETURN QUERY SELECT NULL::UUID, NULL::TEXT, FALSE, 'Ce lien a deja ete utilise'::TEXT;
    RETURN;
  END IF;

  IF v_record.expires_at < NOW() THEN
    RETURN QUERY SELECT NULL::UUID, NULL::TEXT, FALSE, 'Ce lien a expire'::TEXT;
    RETURN;
  END IF;

  -- Mark token as used
  UPDATE public.password_reset_tokens
  SET used_at = NOW()
  WHERE token = p_token;

  RETURN QUERY SELECT v_record.user_id, v_record.email, TRUE, NULL::TEXT;
END;
$$;

COMMENT ON TABLE public.password_reset_tokens IS 'Stores password reset tokens for custom email flow via Resend';
COMMENT ON FUNCTION public.cleanup_expired_password_reset_tokens IS 'Removes expired and used password reset tokens';
COMMENT ON FUNCTION public.create_password_reset_token IS 'Creates a new password reset token for a user';
COMMENT ON FUNCTION public.validate_password_reset_token IS 'Validates and consumes a password reset token';
