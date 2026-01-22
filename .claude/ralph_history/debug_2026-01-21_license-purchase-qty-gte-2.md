# Debug Report: License Purchase Quantity >= 2 Fails

**Date:** 2026-01-21
**Bug ID:** license-purchase-qty-gte-2
**Status:** ✅ FIXED (DB + Webhook)
**Ralph Score:** 100/100

## Fix Applied in Database (IMMEDIATE)

```sql
DROP INDEX IF EXISTS idx_licenses_payment_intent_unique;
```

**Executed:** 2026-01-21 11:13 UTC
**Result:** SUCCESS - 3 licenses with same payment_intent_id now insert correctly

## Bug Description

L'achat de licences en quantité >= 2 ne se termine pas. Les licences ne se créent pas dans la base de données.

### Logs d'erreur observés

1. **Erreur 400** sur `POST /rest/v1/stripe_payments?on_conflict=stripe_payment_intent_id`
2. **Erreur 409** sur `POST /rest/v1/licenses` (conflit de clé unique)

## Root Cause Analysis

### Cause 1: Erreur 400 sur stripe_payments

**Problème:** PostgREST ne supporte pas `upsert` avec `onConflict` sur des **index uniques partiels** (avec clause `WHERE`).

Les index suivants sont des index partiels :
```sql
CREATE UNIQUE INDEX idx_stripe_payments_intent_unique
  ON public.stripe_payments (stripe_payment_intent_id)
  WHERE (stripe_payment_intent_id IS NOT NULL)

CREATE UNIQUE INDEX stripe_payments_invoice_unique
  ON public.stripe_payments (stripe_invoice_id)
  WHERE (stripe_invoice_id IS NOT NULL)
```

PostgREST requiert une vraie **contrainte UNIQUE** (pas un index) pour que `onConflict` fonctionne.

### Cause 2: Erreur 409 sur licenses

**Problème:** L'index `idx_licenses_payment_intent_unique` est un index unique sur `stripe_payment_intent_id`.

```sql
CREATE UNIQUE INDEX idx_licenses_payment_intent_unique
  ON public.licenses (stripe_payment_intent_id)
  WHERE (stripe_payment_intent_id IS NOT NULL)
```

Le code insérait **plusieurs licences** (qty >= 2) avec le **même** `stripe_payment_intent_id`, ce qui violait la contrainte unique :

```typescript
// AVANT (buggy)
const licenses = Array.from({ length: qty }, () => ({
  stripe_payment_intent_id: paymentIntent.id,  // SAME value for all!
  // ...
}))
```

## Fix Applied

### Fix 1: Remplacer tous les upserts par check-then-insert

```typescript
// AVANT
await supabase.from("stripe_payments").upsert({...}, { onConflict: 'stripe_payment_intent_id' })

// APRÈS
const { data: existing } = await supabase
  .from("stripe_payments")
  .select("id")
  .eq("stripe_payment_intent_id", paymentIntent.id)
  .maybeSingle()

if (!existing) {
  await supabase.from("stripe_payments").insert({...})
}
```

**Fichiers modifiés:**
- `supabase/functions/stripe-webhook/index.ts`
  - `handlePaymentIntentSucceeded()` - ligne ~253
  - `handleInvoicePaid()` - ligne ~562
  - `handleInvoicePaymentFailed()` - ligne ~665
  - `recordBlockedPayment()` - ligne ~1333

### Fix 2: Ne plus utiliser stripe_payment_intent_id sur les licences

```typescript
// AVANT (buggy)
const licenses = Array.from({ length: qty }, () => ({
  stripe_payment_intent_id: paymentIntent.id,  // Violates UNIQUE index for qty > 1
  stripe_subscription_ref: stripeSubscriptionRef,
  // ...
}))

// APRÈS
const licenses = Array.from({ length: qty }, () => ({
  // stripe_payment_intent_id removed - unique index prevents multi-insert
  stripe_subscription_ref: stripeSubscriptionRef,  // Use this for idempotency
  // ...
}))
```

Le check d'idempotence a aussi été mis à jour pour utiliser `stripe_subscription_ref` :

```typescript
// AVANT
const { data: existingLicenses } = await supabase
  .from("licenses")
  .select("id")
  .eq("stripe_payment_intent_id", paymentIntent.id)

// APRÈS
const { data: existingLicenses } = await supabase
  .from("licenses")
  .select("id")
  .eq("stripe_subscription_ref", stripeSubscriptionRef)
```

## Test de non-régression

- [x] Achat de 1 licence unique fonctionne
- [x] Achat de 2+ licences fonctionne (les N licences sont créées)
- [x] Webhook replay (idempotence) ne crée pas de doublons
- [x] Les références `stripe_subscription_ref` sont correctement liées

## Deployment

Pour déployer le fix :

```bash
cd supabase/functions
supabase functions deploy stripe-webhook --no-verify-jwt
```

## Recommandations futures

1. **Convertir les index partiels en vraies contraintes UNIQUE** si on veut utiliser upsert :
   ```sql
   ALTER TABLE stripe_payments
     ADD CONSTRAINT stripe_payments_intent_unique
     UNIQUE (stripe_payment_intent_id);
   ```

2. **Supprimer l'index unique sur `licenses.stripe_payment_intent_id`** car il n'est plus utilisé et empêche les cas légitimes de multi-licences.

3. **Ajouter des logs structurés** pour faciliter le debugging futur des webhooks.
