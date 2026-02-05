-- ============================================================================
-- BUGFIX 018: Améliorer sync_user_subscription_cache pour gérer tous les statuts Stripe
-- ============================================================================
-- Date: 2026-02-03
-- Contexte: Le trigger ne gère pas explicitement les statuts 'trialing' et 'incomplete'
--
-- Statuts Stripe possibles:
-- - incomplete: Paiement initial en attente (après subscription.create, avant payment)
-- - incomplete_expired: Paiement initial échoué après 23h
-- - trialing: En période d'essai Stripe (non utilisé dans Motium, mais possible)
-- - active: Abonnement actif et payé
-- - past_due: Paiement en retard mais subscription active
-- - canceled: Abonnement annulé
-- - unpaid: Paiement définitivement échoué
-- - paused: Abonnement mis en pause (Stripe Billing feature)
--
-- Comportement souhaité:
-- - active: PREMIUM/LIFETIME selon subscription_type
-- - trialing: Traiter comme active (accès accordé pendant l'essai)
-- - incomplete: Ne pas modifier (paiement en cours)
-- - incomplete_expired, canceled, unpaid: EXPIRED
-- - past_due: Garder le type actuel (grace period)
-- - paused: Garder le type actuel (pause temporaire)
-- ============================================================================

-- Mise à jour de la fonction trigger
CREATE OR REPLACE FUNCTION sync_user_subscription_cache()
RETURNS TRIGGER AS $$
BEGIN
    -- Uniquement pour les abonnements individuels (pas Pro qui ont user_id = NULL)
    IF NEW.user_id IS NOT NULL THEN
        UPDATE users SET
            subscription_type = CASE
                -- Statuts actifs: accès accordé
                WHEN NEW.status IN ('active', 'trialing') THEN
                    CASE
                        WHEN NEW.subscription_type = 'individual_lifetime' THEN 'LIFETIME'
                        WHEN NEW.subscription_type = 'individual_monthly' THEN 'PREMIUM'
                        ELSE 'PREMIUM'
                    END
                -- Statuts terminés: accès révoqué
                WHEN NEW.status IN ('canceled', 'unpaid', 'incomplete_expired') THEN 'EXPIRED'
                -- Statuts en attente: ne pas modifier (paiement en cours ou grace period)
                WHEN NEW.status IN ('past_due', 'incomplete', 'paused') THEN subscription_type
                -- Fallback: ne pas modifier
                ELSE subscription_type
            END,
            subscription_expires_at = CASE
                -- LIFETIME n'expire jamais
                WHEN NEW.subscription_type = 'individual_lifetime' THEN NULL
                -- Pour trialing, utiliser trial_end de la subscription si disponible
                WHEN NEW.status = 'trialing' THEN COALESCE(NEW.current_period_end, subscription_expires_at)
                -- Pour incomplete, ne pas modifier
                WHEN NEW.status = 'incomplete' THEN subscription_expires_at
                -- Sinon, utiliser la fin de période
                ELSE NEW.current_period_end
            END,
            updated_at = NOW()
        WHERE id = NEW.user_id;

        -- Log pour debug (visible dans Supabase logs)
        RAISE NOTICE 'sync_user_subscription_cache: user_id=%, status=%, sub_type=%, period_end=%',
            NEW.user_id, NEW.status, NEW.subscription_type, NEW.current_period_end;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Le trigger existe déjà, pas besoin de le recréer
-- Il référence la fonction qui vient d'être mise à jour

-- ============================================================================
-- VERIFICATION
-- ============================================================================
-- Exécuter après la migration pour vérifier:
-- SELECT prosrc FROM pg_proc WHERE proname = 'sync_user_subscription_cache';
