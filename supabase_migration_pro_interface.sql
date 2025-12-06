-- =============================================================================
-- MOTIUM PRO INTERFACE - SUPABASE MIGRATION
-- =============================================================================
-- This migration creates the necessary tables and RLS policies for the Pro interface.
-- Run this in the Supabase SQL Editor.
-- =============================================================================

-- =============================================================================
-- 1. PRO_ACCOUNTS TABLE
-- =============================================================================
-- Stores Pro (Enterprise) account information

CREATE TABLE IF NOT EXISTS pro_accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    company_name TEXT NOT NULL,
    siret TEXT,
    vat_number TEXT,
    legal_form TEXT CHECK (legal_form IN ('SARL', 'SAS', 'SASU', 'EURL', 'SA', 'EI', 'MICRO', 'OTHER')),
    billing_address TEXT,
    billing_email TEXT,
    stripe_customer_id TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT unique_user_pro_account UNIQUE (user_id)
);

-- Index for faster lookups
CREATE INDEX IF NOT EXISTS idx_pro_accounts_user_id ON pro_accounts(user_id);
CREATE INDEX IF NOT EXISTS idx_pro_accounts_stripe_customer_id ON pro_accounts(stripe_customer_id);

-- RLS Policies for pro_accounts
ALTER TABLE pro_accounts ENABLE ROW LEVEL SECURITY;

-- Pro users can read their own account
CREATE POLICY "Pro users can read own account" ON pro_accounts
    FOR SELECT USING (auth.uid() = user_id);

-- Pro users can update their own account
CREATE POLICY "Pro users can update own account" ON pro_accounts
    FOR UPDATE USING (auth.uid() = user_id);

-- Pro users can insert their own account
CREATE POLICY "Pro users can insert own account" ON pro_accounts
    FOR INSERT WITH CHECK (auth.uid() = user_id);

-- =============================================================================
-- 2. LINKED_ACCOUNTS TABLE
-- =============================================================================
-- Links Individual accounts to Pro accounts

CREATE TABLE IF NOT EXISTS linked_accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pro_account_id UUID NOT NULL REFERENCES pro_accounts(id) ON DELETE CASCADE,
    individual_user_id UUID REFERENCES auth.users(id) ON DELETE SET NULL,
    email TEXT NOT NULL,
    name TEXT,
    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'active', 'revoked')),
    invitation_token TEXT,
    invited_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    activated_at TIMESTAMPTZ,

    -- Sharing preferences (controlled by the Individual user)
    share_professional_trips BOOLEAN NOT NULL DEFAULT true,
    share_personal_trips BOOLEAN NOT NULL DEFAULT false,
    share_vehicle_info BOOLEAN NOT NULL DEFAULT true,
    share_expenses BOOLEAN NOT NULL DEFAULT false,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT unique_pro_individual_link UNIQUE (pro_account_id, email)
);

-- Indexes for faster lookups
CREATE INDEX IF NOT EXISTS idx_linked_accounts_pro_account_id ON linked_accounts(pro_account_id);
CREATE INDEX IF NOT EXISTS idx_linked_accounts_individual_user_id ON linked_accounts(individual_user_id);
CREATE INDEX IF NOT EXISTS idx_linked_accounts_email ON linked_accounts(email);
CREATE INDEX IF NOT EXISTS idx_linked_accounts_invitation_token ON linked_accounts(invitation_token);
CREATE INDEX IF NOT EXISTS idx_linked_accounts_status ON linked_accounts(status);

-- RLS Policies for linked_accounts
ALTER TABLE linked_accounts ENABLE ROW LEVEL SECURITY;

-- Pro users can read linked accounts for their pro_account
CREATE POLICY "Pro users can read linked accounts" ON linked_accounts
    FOR SELECT USING (
        pro_account_id IN (
            SELECT id FROM pro_accounts WHERE user_id = auth.uid()
        )
    );

-- Pro users can insert linked accounts for their pro_account
CREATE POLICY "Pro users can insert linked accounts" ON linked_accounts
    FOR INSERT WITH CHECK (
        pro_account_id IN (
            SELECT id FROM pro_accounts WHERE user_id = auth.uid()
        )
    );

-- Pro users can update linked accounts for their pro_account
CREATE POLICY "Pro users can update linked accounts" ON linked_accounts
    FOR UPDATE USING (
        pro_account_id IN (
            SELECT id FROM pro_accounts WHERE user_id = auth.uid()
        )
    );

-- Pro users can delete linked accounts for their pro_account
CREATE POLICY "Pro users can delete linked accounts" ON linked_accounts
    FOR DELETE USING (
        pro_account_id IN (
            SELECT id FROM pro_accounts WHERE user_id = auth.uid()
        )
    );

-- Individual users can read their own linked accounts
CREATE POLICY "Individual users can read own links" ON linked_accounts
    FOR SELECT USING (individual_user_id = auth.uid());

-- Individual users can update sharing preferences for their own links
CREATE POLICY "Individual users can update own link preferences" ON linked_accounts
    FOR UPDATE USING (individual_user_id = auth.uid())
    WITH CHECK (individual_user_id = auth.uid());

-- =============================================================================
-- 3. LICENSES TABLE
-- =============================================================================
-- Manages licenses purchased by Pro accounts

CREATE TABLE IF NOT EXISTS licenses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pro_account_id UUID NOT NULL REFERENCES pro_accounts(id) ON DELETE CASCADE,
    linked_account_id UUID REFERENCES linked_accounts(id) ON DELETE SET NULL,

    -- Pricing (in EUR)
    price_monthly_ht DECIMAL(10, 2) NOT NULL DEFAULT 5.00,
    vat_rate DECIMAL(5, 4) NOT NULL DEFAULT 0.20,

    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'active', 'expired', 'cancelled')),
    start_date TIMESTAMPTZ,
    end_date TIMESTAMPTZ,

    -- Stripe integration
    stripe_subscription_id TEXT,
    stripe_subscription_item_id TEXT,
    stripe_price_id TEXT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Indexes for faster lookups
CREATE INDEX IF NOT EXISTS idx_licenses_pro_account_id ON licenses(pro_account_id);
CREATE INDEX IF NOT EXISTS idx_licenses_linked_account_id ON licenses(linked_account_id);
CREATE INDEX IF NOT EXISTS idx_licenses_status ON licenses(status);
CREATE INDEX IF NOT EXISTS idx_licenses_stripe_subscription_id ON licenses(stripe_subscription_id);

-- RLS Policies for licenses
ALTER TABLE licenses ENABLE ROW LEVEL SECURITY;

-- Pro users can read licenses for their pro_account
CREATE POLICY "Pro users can read licenses" ON licenses
    FOR SELECT USING (
        pro_account_id IN (
            SELECT id FROM pro_accounts WHERE user_id = auth.uid()
        )
    );

-- Pro users can insert licenses for their pro_account
CREATE POLICY "Pro users can insert licenses" ON licenses
    FOR INSERT WITH CHECK (
        pro_account_id IN (
            SELECT id FROM pro_accounts WHERE user_id = auth.uid()
        )
    );

-- Pro users can update licenses for their pro_account
CREATE POLICY "Pro users can update licenses" ON licenses
    FOR UPDATE USING (
        pro_account_id IN (
            SELECT id FROM pro_accounts WHERE user_id = auth.uid()
        )
    );

-- =============================================================================
-- 4. AUTO-UPDATE TRIGGER FOR updated_at
-- =============================================================================

CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ language 'plpgsql';

-- Apply trigger to pro_accounts
DROP TRIGGER IF EXISTS update_pro_accounts_updated_at ON pro_accounts;
CREATE TRIGGER update_pro_accounts_updated_at
    BEFORE UPDATE ON pro_accounts
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- Apply trigger to linked_accounts
DROP TRIGGER IF EXISTS update_linked_accounts_updated_at ON linked_accounts;
CREATE TRIGGER update_linked_accounts_updated_at
    BEFORE UPDATE ON linked_accounts
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- Apply trigger to licenses
DROP TRIGGER IF EXISTS update_licenses_updated_at ON licenses;
CREATE TRIGGER update_licenses_updated_at
    BEFORE UPDATE ON licenses
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- =============================================================================
-- 5. HELPER FUNCTIONS
-- =============================================================================

-- Function to generate a unique invitation token
CREATE OR REPLACE FUNCTION generate_invitation_token()
RETURNS TEXT AS $$
BEGIN
    RETURN encode(gen_random_bytes(32), 'hex');
END;
$$ LANGUAGE plpgsql;

-- Function to get license summary for a Pro account
CREATE OR REPLACE FUNCTION get_license_summary(p_pro_account_id UUID)
RETURNS TABLE (
    total_licenses BIGINT,
    active_licenses BIGINT,
    pending_licenses BIGINT,
    available_licenses BIGINT,
    monthly_cost_ht DECIMAL,
    monthly_cost_ttc DECIMAL
) AS $$
BEGIN
    RETURN QUERY
    SELECT
        COUNT(*)::BIGINT as total_licenses,
        COUNT(*) FILTER (WHERE status = 'active')::BIGINT as active_licenses,
        COUNT(*) FILTER (WHERE status = 'pending')::BIGINT as pending_licenses,
        COUNT(*) FILTER (WHERE status IN ('active', 'pending') AND linked_account_id IS NULL)::BIGINT as available_licenses,
        COALESCE(SUM(price_monthly_ht) FILTER (WHERE status IN ('active', 'pending')), 0) as monthly_cost_ht,
        COALESCE(SUM(price_monthly_ht * (1 + vat_rate)) FILTER (WHERE status IN ('active', 'pending')), 0) as monthly_cost_ttc
    FROM licenses
    WHERE pro_account_id = p_pro_account_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- =============================================================================
-- 6. SAMPLE DATA (OPTIONAL - FOR TESTING)
-- =============================================================================
-- Uncomment below to insert sample data for testing

/*
-- Insert a sample Pro account (replace 'your-user-id' with an actual user ID)
INSERT INTO pro_accounts (user_id, company_name, siret, legal_form, billing_email)
VALUES ('your-user-id', 'Test Company SARL', '12345678901234', 'SARL', 'billing@testcompany.com');

-- Insert sample linked accounts
INSERT INTO linked_accounts (pro_account_id, email, name, status)
SELECT id, 'employee1@test.com', 'John Doe', 'active'
FROM pro_accounts WHERE company_name = 'Test Company SARL';

INSERT INTO linked_accounts (pro_account_id, email, name, status, invitation_token)
SELECT id, 'employee2@test.com', 'Jane Smith', 'pending', generate_invitation_token()
FROM pro_accounts WHERE company_name = 'Test Company SARL';

-- Insert sample licenses
INSERT INTO licenses (pro_account_id, status, price_monthly_ht, vat_rate)
SELECT id, 'active', 5.00, 0.20
FROM pro_accounts WHERE company_name = 'Test Company SARL';

INSERT INTO licenses (pro_account_id, status, price_monthly_ht, vat_rate)
SELECT id, 'pending', 5.00, 0.20
FROM pro_accounts WHERE company_name = 'Test Company SARL';
*/

-- =============================================================================
-- MIGRATION COMPLETE
-- =============================================================================
-- Run this SQL in your Supabase SQL Editor.
-- After running, verify the tables exist:
--   SELECT * FROM pro_accounts;
--   SELECT * FROM linked_accounts;
--   SELECT * FROM licenses;
-- =============================================================================
