-- =============================================================================
-- BUGFIX 023: Schedule automatic processing of expired license unlinks
-- =============================================================================
-- Goal:
-- - Run process-expired-license-unlinks automatically on server side
-- - No dependency on Android worker or webhook timing
-- - Monthly licenses: DELETE at effective date
-- - Lifetime licenses: return to pool (available) at effective date
--
-- This migration:
-- 1) Creates/updates a SQL helper that calls the Edge Function
-- 2) Schedules a pg_cron job every 5 minutes
-- =============================================================================

-- Required extensions
CREATE EXTENSION IF NOT EXISTS pg_cron;
CREATE EXTENSION IF NOT EXISTS pg_net;

-- Helper function called by cron
CREATE OR REPLACE FUNCTION public.run_process_expired_license_unlinks_cron()
RETURNS bigint
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_url TEXT;
    v_service_key TEXT;
    v_request_id BIGINT;
BEGIN
    -- Read from Postgres settings (works without vault extension)
    v_url := current_setting('app.settings.supabase_url', true);
    v_service_key := current_setting('app.settings.service_role_key', true);

    IF v_url IS NULL OR v_url = '' THEN
        RAISE EXCEPTION '[CRON] Missing app.settings.supabase_url';
    END IF;

    IF v_service_key IS NULL OR v_service_key = '' THEN
        RAISE EXCEPTION '[CRON] Missing app.settings.service_role_key';
    END IF;

    SELECT net.http_post(
        url := v_url || '/functions/v1/process-expired-license-unlinks',
        headers := jsonb_build_object(
            'Content-Type', 'application/json',
            'Authorization', 'Bearer ' || v_service_key,
            'apikey', v_service_key
        ),
        body := '{}'::jsonb
    )
    INTO v_request_id;

    RAISE LOG '[CRON] process-expired-license-unlinks triggered, request_id=%', v_request_id;
    RETURN v_request_id;
END;
$$;

-- Ensure no duplicate schedule
DO $$
DECLARE
    v_job_id BIGINT;
BEGIN
    SELECT jobid INTO v_job_id
    FROM cron.job
    WHERE jobname = 'process_expired_license_unlinks_every_5m'
    LIMIT 1;

    IF v_job_id IS NOT NULL THEN
        PERFORM cron.unschedule(v_job_id);
    END IF;
END;
$$;

-- Schedule every 5 minutes (UTC)
SELECT cron.schedule(
    'process_expired_license_unlinks_every_5m',
    '*/5 * * * *',
    $$SELECT public.run_process_expired_license_unlinks_cron();$$
);

-- Optional manual trigger:
-- SELECT public.run_process_expired_license_unlinks_cron();

-- If needed, set settings manually (adapt values):
-- ALTER DATABASE postgres SET app.settings.supabase_url = 'https://YOUR-PROJECT.supabase.co';
-- ALTER DATABASE postgres SET app.settings.service_role_key = 'YOUR_SERVICE_ROLE_KEY';
-- Then reconnect and run:
-- SELECT current_setting('app.settings.supabase_url', true);
-- SELECT current_setting('app.settings.service_role_key', true);

-- Optional verification:
-- SELECT jobid, jobname, schedule, active
-- FROM cron.job
-- WHERE jobname = 'process_expired_license_unlinks_every_5m';
