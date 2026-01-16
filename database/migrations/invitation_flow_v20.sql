-- Migration: Invitation Flow v20
-- Allows inviting users who don't have an account yet
-- The user_id can be null until the invitation is accepted

-- Add invitation_email column to store the invited email (before account creation)
ALTER TABLE company_links ADD COLUMN IF NOT EXISTS invitation_email TEXT;

-- Add invitation_expires_at for invitation expiration
ALTER TABLE company_links ADD COLUMN IF NOT EXISTS invitation_expires_at TIMESTAMP WITH TIME ZONE;

-- Make user_id nullable (it's null until user accepts invitation and creates/logs into account)
-- Note: In PostgreSQL, columns are nullable by default, but let's ensure it
ALTER TABLE company_links ALTER COLUMN user_id DROP NOT NULL;

-- Create index for looking up invitations by email
CREATE INDEX IF NOT EXISTS idx_company_links_invitation_email ON company_links(invitation_email) WHERE invitation_email IS NOT NULL;

-- Create index for looking up pending invitations
CREATE INDEX IF NOT EXISTS idx_company_links_pending ON company_links(status, invitation_expires_at) WHERE status = 'PENDING';

-- Add RLS policy to allow service role to insert company_links with null user_id
-- (This is handled by service role which bypasses RLS, but documenting intent)

COMMENT ON COLUMN company_links.invitation_email IS 'Email of the invited person (before they create an account)';
COMMENT ON COLUMN company_links.invitation_expires_at IS 'When the invitation expires';
