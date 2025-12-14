-- =============================================================================
-- CREATE TEST LINKED ACCOUNTS
-- =============================================================================
-- Run this in Supabase SQL Editor to create 3 test linked accounts
-- =============================================================================

-- First, get your pro_account_id (run this separately to find it)
-- SELECT id, company_name FROM pro_accounts;

-- Replace 'YOUR_PRO_ACCOUNT_ID' with your actual pro_account_id below
-- Example: 'a1b2c3d4-e5f6-7890-abcd-ef1234567890'

DO $$
DECLARE
    v_pro_account_id UUID := '0e6cb513-88e1-4518-b29c-a3a68814f8bb';
BEGIN
    RAISE NOTICE 'Using pro_account_id: %', v_pro_account_id;

    -- Create test user 1: Active employee
    INSERT INTO users (
        id,
        email,
        name,
        phone_number,
        department,
        role,
        linked_pro_account_id,
        link_status,
        invited_at,
        link_activated_at,
        share_professional_trips,
        share_personal_trips,
        share_vehicle_info,
        created_at,
        updated_at
    ) VALUES (
        gen_random_uuid(),
        'marie.martin@test.com',
        'Marie Martin',
        '+33 6 12 34 56 78',
        'Commercial',
        'INDIVIDUAL',
        v_pro_account_id,
        'active',
        NOW() - INTERVAL '7 days',
        NOW() - INTERVAL '5 days',
        true,
        false,
        true,
        NOW(),
        NOW()
    )
    ON CONFLICT (email) DO UPDATE SET
        linked_pro_account_id = v_pro_account_id,
        link_status = 'active',
        department = 'Commercial';

    -- Create test user 2: Active employee
    INSERT INTO users (
        id,
        email,
        name,
        phone_number,
        department,
        role,
        linked_pro_account_id,
        link_status,
        invited_at,
        link_activated_at,
        share_professional_trips,
        share_personal_trips,
        share_vehicle_info,
        created_at,
        updated_at
    ) VALUES (
        gen_random_uuid(),
        'pierre.durand@test.com',
        'Pierre Durand',
        '+33 6 98 76 54 32',
        'Technique',
        'INDIVIDUAL',
        v_pro_account_id,
        'active',
        NOW() - INTERVAL '14 days',
        NOW() - INTERVAL '10 days',
        true,
        true,
        true,
        NOW(),
        NOW()
    )
    ON CONFLICT (email) DO UPDATE SET
        linked_pro_account_id = v_pro_account_id,
        link_status = 'active',
        department = 'Technique';

    -- Create test user 3: Pending invitation
    INSERT INTO users (
        id,
        email,
        name,
        phone_number,
        department,
        role,
        linked_pro_account_id,
        link_status,
        invitation_token,
        invited_at,
        share_professional_trips,
        share_personal_trips,
        share_vehicle_info,
        created_at,
        updated_at
    ) VALUES (
        gen_random_uuid(),
        'sophie.bernard@test.com',
        'Sophie Bernard',
        '+33 6 11 22 33 44',
        'RH',
        'INDIVIDUAL',
        v_pro_account_id,
        'pending',
        encode(gen_random_bytes(32), 'hex'),
        NOW() - INTERVAL '1 day',
        true,
        false,
        true,
        NOW(),
        NOW()
    )
    ON CONFLICT (email) DO UPDATE SET
        linked_pro_account_id = v_pro_account_id,
        link_status = 'pending',
        department = 'RH',
        invitation_token = encode(gen_random_bytes(32), 'hex');

    -- Also add some departments to the pro_account
    UPDATE pro_accounts
    SET departments = '["Commercial", "Technique", "RH", "Direction"]'::jsonb
    WHERE id = v_pro_account_id;

    RAISE NOTICE 'Created 3 test linked accounts successfully!';
END $$;

-- Verify the results
SELECT
    u.name,
    u.email,
    u.department,
    u.link_status,
    u.invited_at,
    u.link_activated_at
FROM users u
WHERE u.linked_pro_account_id IS NOT NULL
ORDER BY u.link_status, u.name;
