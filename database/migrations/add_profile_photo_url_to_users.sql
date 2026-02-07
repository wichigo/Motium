-- Migration: add profile photo support on users
-- Date: 2026-02-06

ALTER TABLE public.users
ADD COLUMN IF NOT EXISTS profile_photo_url TEXT;

COMMENT ON COLUMN public.users.profile_photo_url IS
'Public HTTPS URL of user profile photo displayed in settings.';

-- Remove legacy 5-args overload to avoid RPC ambiguity
DROP FUNCTION IF EXISTS public.create_user_profile_on_signup(UUID, TEXT, TEXT, TEXT, TEXT);

CREATE OR REPLACE FUNCTION public.create_user_profile_on_signup(
    p_auth_id UUID,
    p_name TEXT,
    p_email TEXT,
    p_role TEXT,
    p_device_fingerprint_id TEXT DEFAULT NULL,
    p_profile_photo_url TEXT DEFAULT NULL
)
RETURNS JSON
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_user_id UUID;
    v_now TIMESTAMPTZ := NOW();
    v_trial_ends TIMESTAMPTZ := NOW() + INTERVAL '14 days';
    v_profile_photo_url TEXT;
BEGIN
    -- Verify the auth_id exists in auth.users
    IF NOT EXISTS (SELECT 1 FROM auth.users WHERE id = p_auth_id) THEN
        RETURN json_build_object(
            'success', false,
            'error', 'AUTH_ID_NOT_FOUND',
            'message', 'L''utilisateur n''existe pas dans auth.users'
        );
    END IF;

    -- Resolve profile photo URL from explicit parameter or auth metadata
    SELECT COALESCE(
        NULLIF(TRIM(p_profile_photo_url), ''),
        NULLIF(TRIM(raw_user_meta_data->>'avatar_url'), ''),
        NULLIF(TRIM(raw_user_meta_data->>'picture'), ''),
        NULLIF(TRIM(raw_user_meta_data->>'photo_url'), '')
    )
    INTO v_profile_photo_url
    FROM auth.users
    WHERE id = p_auth_id;

    -- Check if user profile already exists
    IF EXISTS (SELECT 1 FROM public.users WHERE auth_id = p_auth_id) THEN
        SELECT id INTO v_user_id FROM public.users WHERE auth_id = p_auth_id;
        RETURN json_build_object(
            'success', true,
            'user_id', v_user_id,
            'message', 'User profile already exists'
        );
    END IF;

    -- Create the user profile with 14-day trial
    INSERT INTO public.users (
        auth_id,
        name,
        email,
        role,
        subscription_type,
        trial_started_at,
        trial_ends_at,
        device_fingerprint_id,
        profile_photo_url,
        created_at,
        updated_at
    ) VALUES (
        p_auth_id,
        p_name,
        p_email,
        p_role,
        'TRIAL',
        v_now,
        v_trial_ends,
        p_device_fingerprint_id,
        v_profile_photo_url,
        v_now,
        v_now
    )
    RETURNING id INTO v_user_id;

    -- Also auto-confirm the email in auth.users if not already confirmed
    UPDATE auth.users
    SET email_confirmed_at = COALESCE(email_confirmed_at, NOW())
    WHERE id = p_auth_id AND email_confirmed_at IS NULL;

    RETURN json_build_object(
        'success', true,
        'user_id', v_user_id,
        'message', 'User profile created successfully'
    );
END;
$$;

CREATE OR REPLACE FUNCTION public.push_user_change(
    p_user_id UUID,
    p_entity_id UUID,
    p_action TEXT,
    p_payload JSONB,
    p_client_version INTEGER
)
RETURNS TABLE (
    success BOOLEAN,
    error_message TEXT,
    conflict BOOLEAN,
    server_version INTEGER
)
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    existing_user RECORD;
BEGIN
    IF p_action != 'UPDATE' THEN
        RETURN QUERY SELECT false, 'Only UPDATE is allowed for USER'::TEXT, false, NULL::INTEGER;
        RETURN;
    END IF;

    IF p_entity_id != p_user_id THEN
        RETURN QUERY SELECT false, 'Can only update your own profile'::TEXT, false, NULL::INTEGER;
        RETURN;
    END IF;

    SELECT * INTO existing_user FROM users WHERE id = p_entity_id;

    IF NOT FOUND THEN
        RETURN QUERY SELECT false, 'User not found'::TEXT, false, NULL::INTEGER;
        RETURN;
    END IF;

    IF existing_user.version > p_client_version THEN
        RETURN QUERY SELECT false, 'Version conflict'::TEXT, true, existing_user.version;
        RETURN;
    END IF;

    UPDATE users SET
        name = COALESCE(p_payload->>'name', name),
        phone_number = COALESCE(p_payload->>'phone_number', phone_number),
        address = COALESCE(p_payload->>'address', address),
        profile_photo_url = CASE
            WHEN p_payload ? 'profile_photo_url' THEN NULLIF(TRIM(p_payload->>'profile_photo_url'), '')
            ELSE profile_photo_url
        END,
        favorite_colors = COALESCE(public.normalize_favorite_colors(p_payload->'favorite_colors'), favorite_colors),
        consider_full_distance = COALESCE((p_payload->>'consider_full_distance')::BOOLEAN, consider_full_distance),
        version = p_client_version,
        updated_at = now()
    WHERE id = p_entity_id;

    RETURN QUERY SELECT true, NULL::TEXT, false, p_client_version;
END;
$$;
