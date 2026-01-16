-- ========================================
-- INTEGRATION STRIPE - TABLES ET POLICIES
-- ========================================
-- Ce script crée les tables nécessaires pour l'intégration Stripe
-- et configure les RLS policies appropriées.
--
-- Exécuter dans l'ordre dans le SQL Editor de Supabase Dashboard
-- ========================================

-- ========================================
-- PHASE 1: TABLE stripe_subscriptions
-- ========================================
-- Source de vérité pour tous les abonnements (Individual et Pro)

CREATE TABLE IF NOT EXISTS stripe_subscriptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Propriétaire (un seul des deux)
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    pro_account_id UUID REFERENCES pro_accounts(id) ON DELETE CASCADE,

    -- Contrainte: un seul propriétaire à la fois
    CONSTRAINT owner_check CHECK (
        (user_id IS NOT NULL AND pro_account_id IS NULL) OR
        (user_id IS NULL AND pro_account_id IS NOT NULL)
    ),

    -- Identifiants Stripe (stripe_subscription_id peut être NULL pour les achats lifetime)
    stripe_subscription_id TEXT UNIQUE,
    stripe_customer_id TEXT NOT NULL,
    stripe_price_id TEXT,
    stripe_product_id TEXT,

    -- Type d'abonnement
    subscription_type TEXT NOT NULL CHECK (subscription_type IN (
        'individual_monthly',
        'individual_lifetime',
        'pro_license_monthly',
        'pro_license_lifetime'
    )),

    -- Statut (synchronisé via webhooks Stripe)
    status TEXT NOT NULL CHECK (status IN (
        'incomplete',
        'incomplete_expired',
        'trialing',
        'active',
        'past_due',
        'canceled',
        'unpaid',
        'paused'
    )) DEFAULT 'incomplete',

    -- Détails de facturation
    quantity INTEGER DEFAULT 1,
    currency TEXT DEFAULT 'eur',
    unit_amount_cents INTEGER,

    -- Période de facturation
    current_period_start TIMESTAMPTZ,
    current_period_end TIMESTAMPTZ,
    cancel_at_period_end BOOLEAN DEFAULT FALSE,
    canceled_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,

    -- Métadonnées pour logique applicative
    metadata JSONB DEFAULT '{}',

    -- Timestamps
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Index pour les requêtes fréquentes
CREATE INDEX IF NOT EXISTS idx_stripe_subs_user_id
    ON stripe_subscriptions(user_id) WHERE user_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_stripe_subs_pro_account_id
    ON stripe_subscriptions(pro_account_id) WHERE pro_account_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_stripe_subs_stripe_id
    ON stripe_subscriptions(stripe_subscription_id);
CREATE INDEX IF NOT EXISTS idx_stripe_subs_status
    ON stripe_subscriptions(status);
CREATE INDEX IF NOT EXISTS idx_stripe_subs_type
    ON stripe_subscriptions(subscription_type);

-- Trigger pour updated_at automatique
CREATE OR REPLACE FUNCTION update_stripe_subscriptions_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trigger_stripe_subscriptions_updated_at ON stripe_subscriptions;
CREATE TRIGGER trigger_stripe_subscriptions_updated_at
    BEFORE UPDATE ON stripe_subscriptions
    FOR EACH ROW EXECUTE FUNCTION update_stripe_subscriptions_updated_at();

-- ========================================
-- PHASE 2: TABLE stripe_payments
-- ========================================
-- Historique de tous les paiements (récurrents et one-time)

CREATE TABLE IF NOT EXISTS stripe_payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Références (nullable car un paiement peut être orphelin)
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    pro_account_id UUID REFERENCES pro_accounts(id) ON DELETE SET NULL,
    stripe_subscription_ref UUID REFERENCES stripe_subscriptions(id) ON DELETE SET NULL,

    -- Identifiants Stripe
    stripe_payment_intent_id TEXT,
    stripe_invoice_id TEXT,
    stripe_charge_id TEXT,
    stripe_customer_id TEXT NOT NULL,

    -- Type de paiement
    payment_type TEXT NOT NULL CHECK (payment_type IN (
        'subscription_payment',   -- Facture récurrente mensuelle
        'one_time_payment',       -- Achat lifetime (paiement unique)
        'setup_payment'           -- Premier paiement d'abonnement
    )),

    -- Montant
    amount_cents INTEGER NOT NULL,
    amount_received_cents INTEGER,
    currency TEXT DEFAULT 'eur',

    -- Statut du paiement
    status TEXT NOT NULL CHECK (status IN (
        'pending',
        'processing',
        'succeeded',
        'failed',
        'refunded',
        'partially_refunded',
        'disputed',
        'canceled'
    )) DEFAULT 'pending',

    -- Gestion des erreurs
    failure_code TEXT,
    failure_message TEXT,

    -- Détails de facture
    invoice_number TEXT,
    invoice_pdf_url TEXT,
    hosted_invoice_url TEXT,

    -- Période de facturation (pour abonnements)
    period_start TIMESTAMPTZ,
    period_end TIMESTAMPTZ,

    -- Remboursement
    refund_id TEXT,
    refund_amount_cents INTEGER,
    refund_reason TEXT,
    refunded_at TIMESTAMPTZ,

    -- Reçu
    receipt_url TEXT,
    receipt_email TEXT,

    -- Métadonnées
    metadata JSONB DEFAULT '{}',

    -- Timestamps
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    paid_at TIMESTAMPTZ
);

-- Index pour les requêtes fréquentes
CREATE INDEX IF NOT EXISTS idx_stripe_payments_user_id
    ON stripe_payments(user_id) WHERE user_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_stripe_payments_pro_account_id
    ON stripe_payments(pro_account_id) WHERE pro_account_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_stripe_payments_subscription_ref
    ON stripe_payments(stripe_subscription_ref) WHERE stripe_subscription_ref IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_stripe_payments_intent_id
    ON stripe_payments(stripe_payment_intent_id) WHERE stripe_payment_intent_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_stripe_payments_invoice_id
    ON stripe_payments(stripe_invoice_id) WHERE stripe_invoice_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_stripe_payments_status
    ON stripe_payments(status);
CREATE INDEX IF NOT EXISTS idx_stripe_payments_created_at
    ON stripe_payments(created_at DESC);

-- Trigger pour updated_at automatique
CREATE OR REPLACE FUNCTION update_stripe_payments_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trigger_stripe_payments_updated_at ON stripe_payments;
CREATE TRIGGER trigger_stripe_payments_updated_at
    BEFORE UPDATE ON stripe_payments
    FOR EACH ROW EXECUTE FUNCTION update_stripe_payments_updated_at();

-- ========================================
-- PHASE 3: RLS POLICIES
-- ========================================

-- Activer RLS sur stripe_subscriptions
ALTER TABLE stripe_subscriptions ENABLE ROW LEVEL SECURITY;

-- Supprimer les anciennes policies si elles existent
DROP POLICY IF EXISTS "Users can view own subscriptions" ON stripe_subscriptions;
DROP POLICY IF EXISTS "Pro owners can view pro subscriptions" ON stripe_subscriptions;
DROP POLICY IF EXISTS "Service role manages subscriptions" ON stripe_subscriptions;

-- Les utilisateurs peuvent voir leurs propres abonnements
CREATE POLICY "Users can view own subscriptions" ON stripe_subscriptions
    FOR SELECT
    USING (
        user_id IN (SELECT id FROM users WHERE auth_id = auth.uid())
    );

-- Les propriétaires de comptes Pro peuvent voir les abonnements Pro
CREATE POLICY "Pro owners can view pro subscriptions" ON stripe_subscriptions
    FOR SELECT
    USING (
        pro_account_id IN (
            SELECT id FROM pro_accounts WHERE user_id IN (
                SELECT id FROM users WHERE auth_id = auth.uid()
            )
        )
    );

-- Seul le service_role peut gérer les abonnements (webhooks)
CREATE POLICY "Service role manages subscriptions" ON stripe_subscriptions
    FOR ALL
    TO service_role
    USING (true)
    WITH CHECK (true);

-- Activer RLS sur stripe_payments
ALTER TABLE stripe_payments ENABLE ROW LEVEL SECURITY;

-- Supprimer les anciennes policies si elles existent
DROP POLICY IF EXISTS "Users can view own payments" ON stripe_payments;
DROP POLICY IF EXISTS "Pro owners can view pro payments" ON stripe_payments;
DROP POLICY IF EXISTS "Service role manages payments" ON stripe_payments;

-- Les utilisateurs peuvent voir leurs propres paiements
CREATE POLICY "Users can view own payments" ON stripe_payments
    FOR SELECT
    USING (
        user_id IN (SELECT id FROM users WHERE auth_id = auth.uid())
    );

-- Les propriétaires de comptes Pro peuvent voir les paiements Pro
CREATE POLICY "Pro owners can view pro payments" ON stripe_payments
    FOR SELECT
    USING (
        pro_account_id IN (
            SELECT id FROM pro_accounts WHERE user_id IN (
                SELECT id FROM users WHERE auth_id = auth.uid()
            )
        )
    );

-- Seul le service_role peut gérer les paiements (webhooks)
CREATE POLICY "Service role manages payments" ON stripe_payments
    FOR ALL
    TO service_role
    USING (true)
    WITH CHECK (true);

-- ========================================
-- PHASE 4: TRIGGER SYNCHRONISATION
-- ========================================
-- Synchronise users.subscription_type quand stripe_subscriptions change
--
-- IMPORTANT: SOURCE DE VERITE UNIQUE
-- ==================================
-- La table `stripe_subscriptions` est la SOURCE DE VERITE pour tous les abonnements.
-- Ce trigger est le SEUL responsable de la synchronisation de `users.subscription_type`.
--
-- POURQUOI:
-- - Evite les race conditions entre les webhooks Stripe et les updates directs
-- - Garantit la coherence des donnees (un seul chemin de mise a jour)
-- - Simplifie le debugging (un seul endroit a verifier)
--
-- WORKFLOW:
-- 1. Le webhook Stripe (stripe-webhook/index.ts) recoit un evenement
-- 2. Le webhook met a jour UNIQUEMENT la table `stripe_subscriptions`
-- 3. Ce trigger se declenche automatiquement (AFTER INSERT OR UPDATE)
-- 4. Ce trigger synchronise `users.subscription_type` et `users.subscription_expires_at`
--
-- NE JAMAIS faire d'update direct sur `users.subscription_type` depuis les webhooks!
-- ==================================

CREATE OR REPLACE FUNCTION sync_user_subscription_cache()
RETURNS TRIGGER AS $$
BEGIN
    -- Uniquement pour les abonnements individuels (pas Pro)
    IF NEW.user_id IS NOT NULL THEN
        UPDATE users SET
            subscription_type = CASE
                WHEN NEW.status = 'active' THEN
                    CASE
                        WHEN NEW.subscription_type = 'individual_lifetime' THEN 'LIFETIME'
                        WHEN NEW.subscription_type = 'individual_monthly' THEN 'PREMIUM'
                        ELSE 'PREMIUM'
                    END
                WHEN NEW.status IN ('canceled', 'unpaid', 'incomplete_expired') THEN 'EXPIRED'
                WHEN NEW.status = 'past_due' THEN subscription_type -- Garder le type actuel
                ELSE subscription_type
            END,
            subscription_expires_at = CASE
                WHEN NEW.subscription_type = 'individual_lifetime' THEN NULL -- Jamais expire
                ELSE NEW.current_period_end
            END,
            updated_at = NOW()
        WHERE id = NEW.user_id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS trigger_sync_user_subscription ON stripe_subscriptions;
CREATE TRIGGER trigger_sync_user_subscription
    AFTER INSERT OR UPDATE ON stripe_subscriptions
    FOR EACH ROW EXECUTE FUNCTION sync_user_subscription_cache();

-- ========================================
-- PHASE 5: MODIFICATIONS TABLE users
-- ========================================

-- Supprimer monthly_trip_count si elle existe (obsolète)
ALTER TABLE users DROP COLUMN IF EXISTS monthly_trip_count;

-- Ajouter stripe_customer_id si elle n'existe pas
ALTER TABLE users ADD COLUMN IF NOT EXISTS stripe_customer_id TEXT;

-- Ajouter stripe_subscription_id si elle n'existe pas (legacy, pour référence)
ALTER TABLE users ADD COLUMN IF NOT EXISTS stripe_subscription_id TEXT;

-- Index sur stripe_customer_id pour recherches rapides
CREATE INDEX IF NOT EXISTS idx_users_stripe_customer_id
    ON users(stripe_customer_id) WHERE stripe_customer_id IS NOT NULL;

-- Commentaires pour documentation
COMMENT ON COLUMN users.subscription_type IS
    'Cache du statut d''abonnement. Source de vérité: stripe_subscriptions. Synchronisé via trigger.';
COMMENT ON COLUMN users.subscription_expires_at IS
    'Cache de la date d''expiration. NULL pour LIFETIME. Synchronisé via trigger.';
COMMENT ON COLUMN users.stripe_subscription_id IS
    'DEPRECATED: Utiliser stripe_subscriptions.stripe_subscription_id à la place.';

-- ========================================
-- PHASE 6: MODIFICATIONS TABLE licenses
-- ========================================

-- Ajouter colonne FK vers stripe_subscriptions
ALTER TABLE licenses
    ADD COLUMN IF NOT EXISTS stripe_subscription_ref UUID
        REFERENCES stripe_subscriptions(id) ON DELETE SET NULL;

-- Ajouter colonne stripe_payment_intent_id pour idempotency sur les achats one-time
ALTER TABLE licenses
    ADD COLUMN IF NOT EXISTS stripe_payment_intent_id TEXT;

-- Index pour la FK
CREATE INDEX IF NOT EXISTS idx_licenses_stripe_sub_ref
    ON licenses(stripe_subscription_ref) WHERE stripe_subscription_ref IS NOT NULL;

-- Index pour stripe_payment_intent_id (idempotency lookup)
CREATE INDEX IF NOT EXISTS idx_licenses_stripe_payment_intent
    ON licenses(stripe_payment_intent_id) WHERE stripe_payment_intent_id IS NOT NULL;

-- Commentaires
COMMENT ON COLUMN licenses.stripe_subscription_ref IS
    'Référence vers stripe_subscriptions.id pour les détails d''abonnement de cette licence.';
COMMENT ON COLUMN licenses.stripe_subscription_id IS
    'DEPRECATED: Utiliser stripe_subscription_ref à la place.';

-- ========================================
-- PHASE 7: FONCTION UTILITAIRE
-- ========================================
-- Récupère le statut d'abonnement actif d'un utilisateur

CREATE OR REPLACE FUNCTION get_user_active_subscription(p_user_id UUID)
RETURNS TABLE (
    subscription_id UUID,
    subscription_type TEXT,
    status TEXT,
    is_lifetime BOOLEAN,
    expires_at TIMESTAMPTZ,
    days_remaining INTEGER
) AS $$
BEGIN
    RETURN QUERY
    SELECT
        ss.id AS subscription_id,
        ss.subscription_type,
        ss.status,
        ss.subscription_type LIKE '%lifetime%' AS is_lifetime,
        ss.current_period_end AS expires_at,
        CASE
            WHEN ss.subscription_type LIKE '%lifetime%' THEN NULL
            WHEN ss.current_period_end IS NULL THEN NULL
            ELSE GREATEST(0, EXTRACT(DAY FROM ss.current_period_end - NOW())::INTEGER)
        END AS days_remaining
    FROM stripe_subscriptions ss
    WHERE ss.user_id = p_user_id
      AND ss.status IN ('active', 'trialing', 'past_due')
    ORDER BY
        CASE ss.subscription_type
            WHEN 'individual_lifetime' THEN 1
            ELSE 2
        END,
        ss.current_period_end DESC NULLS LAST
    LIMIT 1;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Accès pour les utilisateurs authentifiés
GRANT EXECUTE ON FUNCTION get_user_active_subscription(UUID) TO authenticated;

-- ========================================
-- PHASE 8: MODIFICATIONS TABLE pro_accounts
-- ========================================
-- Ajout des colonnes pour la gestion des subscriptions unifiées

-- Jour de facturation mensuel (1-28) choisi à la création du compte
ALTER TABLE pro_accounts
    ADD COLUMN IF NOT EXISTS billing_anchor_day INTEGER CHECK (billing_anchor_day >= 1 AND billing_anchor_day <= 28);

-- ID de la subscription Stripe principale pour ce compte Pro
ALTER TABLE pro_accounts
    ADD COLUMN IF NOT EXISTS stripe_subscription_id TEXT;

-- Index pour lookup rapide par subscription
CREATE INDEX IF NOT EXISTS idx_pro_accounts_stripe_subscription
    ON pro_accounts(stripe_subscription_id) WHERE stripe_subscription_id IS NOT NULL;

-- Commentaires
COMMENT ON COLUMN pro_accounts.billing_anchor_day IS
    'Jour du mois (1-28) où toutes les licences du compte sont facturées. Choisi à la création.';
COMMENT ON COLUMN pro_accounts.stripe_subscription_id IS
    'ID de la subscription Stripe principale regroupant toutes les licences mensuelles du compte.';

-- ========================================
-- VERIFICATION
-- ========================================
-- Exécuter ces requêtes pour vérifier que tout est bien créé

-- Vérifier les tables
-- SELECT tablename, rowsecurity FROM pg_tables
-- WHERE schemaname = 'public' AND tablename IN ('stripe_subscriptions', 'stripe_payments');

-- Vérifier les policies
-- SELECT * FROM pg_policies
-- WHERE tablename IN ('stripe_subscriptions', 'stripe_payments');

-- Vérifier les triggers
-- SELECT trigger_name, event_manipulation, action_statement
-- FROM information_schema.triggers
-- WHERE trigger_schema = 'public';

-- ========================================
-- FIN DU SCRIPT
-- ========================================
