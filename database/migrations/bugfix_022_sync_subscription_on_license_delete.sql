-- =================================================================
-- BUGFIX 022: Sync stripe_subscriptions when a license is deleted
-- =================================================================
-- Goal:
-- - When a monthly license is deleted, immediately recalculate:
--   - stripe_subscriptions.quantity
--   - stripe_subscriptions.unit_amount_cents (TOTAL for all licenses)
-- =================================================================

-- Backfill legacy rows where unit_amount_cents still stores per-license amount.
-- In this project, known per-license monthly amounts are 499 and 600 cents.
UPDATE stripe_subscriptions
SET unit_amount_cents = unit_amount_cents * COALESCE(quantity, 1),
    updated_at = NOW()
WHERE subscription_type = 'pro_license_monthly'
  AND quantity > 1
  AND unit_amount_cents IN (499, 600);

CREATE OR REPLACE FUNCTION sync_subscription_totals_on_license_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_subscription_ref UUID;
    v_subscription_id TEXT;
    v_remaining_monthly_qty INTEGER := 0;
    v_prev_quantity INTEGER := 0;
    v_prev_amount_cents INTEGER := 0;
    v_price_per_license_cents INTEGER := 0;
BEGIN
    -- Only monthly licenses impact recurring subscription quantity.
    IF OLD.is_lifetime THEN
        RETURN OLD;
    END IF;

    -- Resolve subscription reference (prefer FK, fallback to legacy stripe_subscription_id).
    v_subscription_ref := OLD.stripe_subscription_ref;

    IF v_subscription_ref IS NULL AND OLD.stripe_subscription_id IS NOT NULL THEN
        SELECT id, stripe_subscription_id
          INTO v_subscription_ref, v_subscription_id
          FROM stripe_subscriptions
         WHERE stripe_subscription_id = OLD.stripe_subscription_id
         LIMIT 1;
    END IF;

    IF v_subscription_ref IS NULL THEN
        RETURN OLD;
    END IF;

    -- Ensure we also have the Stripe subscription id for legacy-license fallback counting.
    IF v_subscription_id IS NULL THEN
        SELECT stripe_subscription_id
          INTO v_subscription_id
          FROM stripe_subscriptions
         WHERE id = v_subscription_ref;
    END IF;

    -- Recompute the remaining monthly licenses linked to this subscription.
    SELECT COUNT(*)
      INTO v_remaining_monthly_qty
      FROM licenses l
     WHERE l.is_lifetime = false
       AND (
            l.stripe_subscription_ref = v_subscription_ref
            OR (
                l.stripe_subscription_ref IS NULL
                AND v_subscription_id IS NOT NULL
                AND l.stripe_subscription_id = v_subscription_id
            )
       );

    -- Read previous subscription totals to derive unit price robustly.
    SELECT COALESCE(quantity, 0), COALESCE(unit_amount_cents, 0)
      INTO v_prev_quantity, v_prev_amount_cents
      FROM stripe_subscriptions
     WHERE id = v_subscription_ref;

    -- Legacy compatibility:
    -- - If amount looks like per-license (e.g. 499/600) with qty>1, keep it as unit price.
    -- - Otherwise derive unit price from total / quantity.
    IF v_prev_quantity > 1 AND v_prev_amount_cents <= 1000 THEN
        v_price_per_license_cents := v_prev_amount_cents;
    ELSIF v_prev_quantity > 0 THEN
        v_price_per_license_cents := v_prev_amount_cents / v_prev_quantity;
    ELSE
        v_price_per_license_cents := v_prev_amount_cents;
    END IF;

    UPDATE stripe_subscriptions
       SET quantity = v_remaining_monthly_qty,
           unit_amount_cents = GREATEST(v_price_per_license_cents, 0) * GREATEST(v_remaining_monthly_qty, 0),
           updated_at = NOW()
     WHERE id = v_subscription_ref
       AND subscription_type = 'pro_license_monthly';

    RETURN OLD;
END;
$$;

DROP TRIGGER IF EXISTS on_license_delete_sync_subscription_totals ON licenses;
CREATE TRIGGER on_license_delete_sync_subscription_totals
AFTER DELETE ON licenses
FOR EACH ROW
EXECUTE FUNCTION sync_subscription_totals_on_license_delete();

GRANT EXECUTE ON FUNCTION sync_subscription_totals_on_license_delete() TO service_role;
GRANT EXECUTE ON FUNCTION sync_subscription_totals_on_license_delete() TO authenticated;
