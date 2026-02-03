-- ============================================
-- BUGFIX 020: Synchroniser ended_at avec cancel_at_period_end
-- ============================================
-- Date: 2026-02-03
--
-- OBJECTIF:
-- Maintenir la cohérence entre les 3 colonnes de date d'expiration:
-- 1. stripe_subscriptions.current_period_end (source Stripe)
-- 2. stripe_subscriptions.ended_at (copie si résiliation)
-- 3. users.subscription_expires_at (sync via trigger existant)
--
-- LOGIQUE:
-- - cancel_at_period_end = TRUE  → ended_at = current_period_end
-- - cancel_at_period_end = FALSE → ended_at = NULL, canceled_at = NULL
--
-- NOTE: Ce trigger s'exécute AVANT sync_user_subscription_cache()
-- car il modifie les valeurs qui seront lues par ce dernier.

-- ========================================
-- FONCTION: Synchronise ended_at basé sur cancel_at_period_end
-- ========================================
CREATE OR REPLACE FUNCTION sync_ended_at_on_cancel()
RETURNS TRIGGER AS $$
BEGIN
    -- Seulement si cancel_at_period_end a changé
    IF OLD.cancel_at_period_end IS DISTINCT FROM NEW.cancel_at_period_end THEN
        IF NEW.cancel_at_period_end = TRUE THEN
            -- Résiliation demandée: copier current_period_end vers ended_at
            NEW.ended_at := NEW.current_period_end;
        ELSE
            -- Réactivation: réinitialiser l'état de résiliation
            NEW.ended_at := NULL;
            NEW.canceled_at := NULL;
        END IF;
    END IF;

    -- Synchroniser aussi quand current_period_end change ET cancel_at_period_end est true
    -- (renouvellement automatique de la date de fin)
    IF NEW.cancel_at_period_end = TRUE
       AND OLD.current_period_end IS DISTINCT FROM NEW.current_period_end THEN
        NEW.ended_at := NEW.current_period_end;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ========================================
-- TRIGGER: BEFORE UPDATE pour modifier NEW avant le commit
-- ========================================
DROP TRIGGER IF EXISTS trigger_sync_ended_at_on_cancel ON stripe_subscriptions;
CREATE TRIGGER trigger_sync_ended_at_on_cancel
    BEFORE UPDATE ON stripe_subscriptions
    FOR EACH ROW
    EXECUTE FUNCTION sync_ended_at_on_cancel();

-- ========================================
-- MIGRATION: Mettre à jour les données existantes
-- ========================================
-- Pour les subscriptions avec cancel_at_period_end = TRUE mais ended_at NULL
UPDATE stripe_subscriptions
SET ended_at = current_period_end
WHERE cancel_at_period_end = TRUE
  AND ended_at IS NULL
  AND current_period_end IS NOT NULL;
