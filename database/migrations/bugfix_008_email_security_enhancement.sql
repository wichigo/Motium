-- =============================================================================
-- BUGFIX-008 - Email Security Enhancement
-- =============================================================================
-- Issues identified by Ralph Wiggum audit (iteration 2):
-- 1. normalize_email() only covers Gmail (Yahoo uses - as tag separator)
-- 2. No blocklist for disposable email domains
-- 3. check_trial_abuse() needs to verify disposable emails
-- =============================================================================

-- =============================================================================
-- FIX 1: Enhanced email normalization covering all major providers
-- =============================================================================
-- Yahoo: john-tag@yahoo.com -> john@yahoo.com (uses - separator)
-- Outlook/Hotmail: john+tag@outlook.com -> john@outlook.com
-- Gmail: j.o.h.n+tag@gmail.com -> john@gmail.com (dots ignored + tags)
-- ProtonMail: john+tag@protonmail.com -> john@protonmail.com
-- iCloud: john+tag@icloud.com -> john@icloud.com

CREATE OR REPLACE FUNCTION normalize_email(email TEXT)
RETURNS TEXT AS $$
DECLARE
    local_part TEXT;
    domain TEXT;
    base_domain TEXT;
BEGIN
    -- Input validation
    IF email IS NULL OR email = '' OR position('@' in email) = 0 THEN
        RETURN LOWER(COALESCE(email, ''));
    END IF;

    -- Split email into local and domain parts
    local_part := SPLIT_PART(email, '@', 1);
    domain := LOWER(SPLIT_PART(email, '@', 2));

    -- Determine base domain for alias providers (normalizes variants)
    base_domain := CASE
        -- Gmail family (includes googlemail.com which redirects to gmail.com)
        WHEN domain IN ('gmail.com', 'googlemail.com') THEN 'gmail.com'

        -- Microsoft family (all redirect to same mailbox system)
        WHEN domain IN (
            'outlook.com', 'outlook.fr', 'outlook.de', 'outlook.co.uk',
            'hotmail.com', 'hotmail.fr', 'hotmail.de', 'hotmail.co.uk',
            'live.com', 'live.fr', 'live.de', 'live.co.uk',
            'msn.com'
        ) THEN 'outlook.com'

        -- Yahoo family (includes all regional variants)
        WHEN domain LIKE 'yahoo.%'
          OR domain IN ('ymail.com', 'rocketmail.com', 'yahoo.com') THEN 'yahoo.com'

        -- iCloud family (all Apple email domains)
        WHEN domain IN ('icloud.com', 'me.com', 'mac.com') THEN 'icloud.com'

        -- ProtonMail family
        WHEN domain IN ('protonmail.com', 'proton.me', 'pm.me') THEN 'protonmail.com'

        -- Fastmail (uses subdomain addressing: john@subdomain.fastmail.com)
        WHEN domain LIKE '%.fastmail.com' OR domain = 'fastmail.com' THEN 'fastmail.com'

        -- Default: keep original domain
        ELSE domain
    END;

    -- Apply provider-specific normalization rules
    CASE base_domain
        -- Gmail: dots are ignored in local part, + is tag separator
        WHEN 'gmail.com' THEN
            local_part := REPLACE(local_part, '.', '');
            local_part := SPLIT_PART(local_part, '+', 1);

        -- Yahoo: uses - AND + as tag separators (both work)
        -- john-spam@yahoo.com and john+spam@yahoo.com both deliver to john@yahoo.com
        WHEN 'yahoo.com' THEN
            local_part := SPLIT_PART(local_part, '-', 1);
            local_part := SPLIT_PART(local_part, '+', 1);

        -- Microsoft (Outlook/Hotmail/Live): + is tag separator
        WHEN 'outlook.com' THEN
            local_part := SPLIT_PART(local_part, '+', 1);

        -- iCloud: + is tag separator
        WHEN 'icloud.com' THEN
            local_part := SPLIT_PART(local_part, '+', 1);

        -- ProtonMail: + is tag separator
        WHEN 'protonmail.com' THEN
            local_part := SPLIT_PART(local_part, '+', 1);

        -- Fastmail: + is tag separator, subdomain is ignored
        WHEN 'fastmail.com' THEN
            local_part := SPLIT_PART(local_part, '+', 1);

        -- Other providers: just remove + tags (common convention)
        ELSE
            local_part := SPLIT_PART(local_part, '+', 1);
    END CASE;

    RETURN LOWER(local_part) || '@' || base_domain;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- =============================================================================
-- FIX 2: Blocked/Disposable email domains table
-- =============================================================================

CREATE TABLE IF NOT EXISTS blocked_email_domains (
    domain TEXT PRIMARY KEY,
    reason TEXT DEFAULT 'disposable',
    blocked_at TIMESTAMPTZ DEFAULT NOW(),
    added_by TEXT DEFAULT 'system'
);

-- Insert most common disposable email domains
-- Source: Various blocklists + empirical fraud data
INSERT INTO blocked_email_domains (domain, reason) VALUES
    -- Top disposable email providers
    ('tempmail.com', 'disposable'),
    ('temp-mail.org', 'disposable'),
    ('temp-mail.io', 'disposable'),
    ('tempmailo.com', 'disposable'),
    ('tempail.com', 'disposable'),
    ('guerrillamail.com', 'disposable'),
    ('guerrillamail.org', 'disposable'),
    ('guerrillamail.net', 'disposable'),
    ('guerrillamail.biz', 'disposable'),
    ('guerrillamail.de', 'disposable'),
    ('guerrillamailblock.com', 'disposable'),
    ('sharklasers.com', 'disposable'),
    ('grr.la', 'disposable'),
    ('spam4.me', 'disposable'),
    ('mailinator.com', 'disposable'),
    ('mailinator2.com', 'disposable'),
    ('mailinater.com', 'disposable'),
    ('m4ilinator.com', 'disposable'),
    ('10minutemail.com', 'disposable'),
    ('10minutemail.net', 'disposable'),
    ('10minute-mail.com', 'disposable'),
    ('10minmail.com', 'disposable'),
    ('throwaway.email', 'disposable'),
    ('throwawaymail.com', 'disposable'),
    ('fakeinbox.com', 'disposable'),
    ('trashmail.com', 'disposable'),
    ('trashmail.net', 'disposable'),
    ('trashmail.me', 'disposable'),
    ('discard.email', 'disposable'),
    ('discardmail.com', 'disposable'),
    ('getairmail.com', 'disposable'),
    ('getnada.com', 'disposable'),
    ('mohmal.com', 'disposable'),
    ('yopmail.com', 'disposable'),
    ('yopmail.fr', 'disposable'),
    ('yopmail.net', 'disposable'),
    ('crazymailing.com', 'disposable'),
    ('mytemp.email', 'disposable'),
    ('emailondeck.com', 'disposable'),
    ('burnermail.io', 'disposable'),
    ('tempinbox.com', 'disposable'),
    ('inboxkitten.com', 'disposable'),
    ('maildrop.cc', 'disposable'),
    ('mailnesia.com', 'disposable'),
    ('spamgourmet.com', 'disposable'),
    ('mintemail.com', 'disposable'),
    ('armyspy.com', 'disposable'),
    ('cuvox.de', 'disposable'),
    ('dayrep.com', 'disposable'),
    ('einrot.com', 'disposable'),
    ('fleckens.hu', 'disposable'),
    ('gustr.com', 'disposable'),
    ('jourrapide.com', 'disposable'),
    ('rhyta.com', 'disposable'),
    ('superrito.com', 'disposable'),
    ('teleworm.us', 'disposable'),
    ('spambox.us', 'disposable'),
    ('spamfree24.org', 'disposable'),
    ('spam.la', 'disposable'),
    ('mytrashmail.com', 'disposable'),
    ('mailcatch.com', 'disposable'),
    ('mailexpire.com', 'disposable'),
    ('mailnull.com', 'disposable'),
    ('mailscrap.com', 'disposable'),
    ('disposableaddress.com', 'disposable'),
    ('incognitomail.com', 'disposable'),
    ('incognitomail.org', 'disposable'),
    ('anonymbox.com', 'disposable'),
    ('anonymousemail.me', 'disposable'),
    ('spamavert.com', 'disposable'),
    ('spamherelots.com', 'disposable'),
    ('tempr.email', 'disposable'),
    ('fakemailgenerator.com', 'disposable'),
    ('emailfake.com', 'disposable'),
    ('emkei.cz', 'disposable'),
    ('jetable.org', 'disposable'),
    ('kasmail.com', 'disposable'),
    ('mailforspam.com', 'disposable'),
    ('sofimail.com', 'disposable'),
    ('wegwerfmail.de', 'disposable'),
    ('wegwerfmail.net', 'disposable'),
    ('wegwerfmail.org', 'disposable')
ON CONFLICT (domain) DO NOTHING;

-- =============================================================================
-- FIX 3: Function to check if email is from a disposable domain
-- =============================================================================

CREATE OR REPLACE FUNCTION is_disposable_email(p_email TEXT)
RETURNS BOOLEAN AS $$
DECLARE
    v_domain TEXT;
BEGIN
    IF p_email IS NULL OR p_email = '' THEN
        RETURN false;
    END IF;

    v_domain := LOWER(SPLIT_PART(p_email, '@', 2));
    RETURN EXISTS (SELECT 1 FROM blocked_email_domains WHERE domain = v_domain);
END;
$$ LANGUAGE plpgsql STABLE;

GRANT EXECUTE ON FUNCTION is_disposable_email(TEXT) TO authenticated;

-- =============================================================================
-- FIX 4: Enhanced check_trial_abuse with disposable email check
-- =============================================================================

CREATE OR REPLACE FUNCTION check_trial_abuse(p_email TEXT, p_device_fingerprint TEXT)
RETURNS JSONB AS $$
DECLARE
    v_normalized_email TEXT;
    v_existing_user RECORD;
    v_existing_by_fingerprint RECORD;
BEGIN
    -- NEW: Check disposable email FIRST (fastest rejection)
    IF is_disposable_email(p_email) THEN
        RETURN jsonb_build_object(
            'allowed', false,
            'reason', 'DISPOSABLE_EMAIL',
            'message', 'Les adresses email temporaires/jetables ne sont pas autorisées pour créer un compte'
        );
    END IF;

    -- Normalize email to detect aliases
    v_normalized_email := normalize_email(p_email);

    -- Check if normalized email already has an account
    SELECT * INTO v_existing_user FROM users
    WHERE normalize_email(email) = v_normalized_email
    LIMIT 1;

    IF FOUND THEN
        RETURN jsonb_build_object(
            'allowed', false,
            'reason', 'EMAIL_ALIAS_EXISTS',
            'message', 'Un compte existe déjà avec cet email ou un alias de cet email',
            'existing_email', v_existing_user.email
        );
    END IF;

    -- Check if device fingerprint already used for trial
    IF p_device_fingerprint IS NOT NULL AND p_device_fingerprint != '' THEN
        SELECT * INTO v_existing_by_fingerprint FROM users
        WHERE device_fingerprint_id = p_device_fingerprint
          AND subscription_type IN ('TRIAL', 'EXPIRED')  -- Had or has trial
        LIMIT 1;

        IF FOUND THEN
            RETURN jsonb_build_object(
                'allowed', false,
                'reason', 'DEVICE_ALREADY_TRIALED',
                'message', 'Cet appareil a déjà bénéficié d''un essai gratuit'
            );
        END IF;
    END IF;

    RETURN jsonb_build_object(
        'allowed', true
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- =============================================================================
-- FIX 5: Add trigger to prevent registration with blocked emails
-- =============================================================================

CREATE OR REPLACE FUNCTION prevent_blocked_email_registration()
RETURNS TRIGGER AS $$
BEGIN
    IF is_disposable_email(NEW.email) THEN
        RAISE EXCEPTION 'Registration with disposable email addresses is not allowed'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS check_blocked_email_on_insert ON users;
CREATE TRIGGER check_blocked_email_on_insert
    BEFORE INSERT ON users
    FOR EACH ROW
    EXECUTE FUNCTION prevent_blocked_email_registration();

-- =============================================================================
-- VERIFICATION QUERIES
-- =============================================================================
-- Test enhanced email normalization:
-- SELECT normalize_email('John.Doe+test@gmail.com');  -- Expected: johndoe@gmail.com
-- SELECT normalize_email('john-spam@yahoo.com');       -- Expected: john@yahoo.com
-- SELECT normalize_email('john+tag@outlook.fr');       -- Expected: john@outlook.com
-- SELECT normalize_email('test+alias@proton.me');      -- Expected: test@protonmail.com

-- Test disposable email check:
-- SELECT is_disposable_email('test@tempmail.com');     -- Expected: true
-- SELECT is_disposable_email('test@gmail.com');        -- Expected: false

-- Test trial abuse with disposable:
-- SELECT check_trial_abuse('test@yopmail.com', 'device123');
-- Expected: {"allowed": false, "reason": "DISPOSABLE_EMAIL", ...}

-- =============================================================================
-- SUMMARY
-- =============================================================================
-- 1. Enhanced normalize_email() to cover Gmail, Yahoo (- separator), Outlook,
--    ProtonMail, iCloud, Fastmail with proper domain family normalization
-- 2. Added blocked_email_domains table with 80+ disposable domains
-- 3. Added is_disposable_email() function for fast lookups
-- 4. Enhanced check_trial_abuse() to reject disposable emails first
-- 5. Added trigger to prevent registration with blocked emails at DB level
-- =============================================================================
