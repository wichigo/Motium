# Debug Report: Stripe Subscription ID Mismatch

**Date:** 2026-02-03
**Bug ID:** stripe-subscription-id-mismatch
**Severity:** Critical
**Status:** FIXED

## Description du Bug

Lors de l'achat d'un abonnement mensuel (Individual ou Pro), les champs `stripe_subscriptions.stripe_subscription_id` contenaient un ID synthétique (`onetime_pi_xxx`) au lieu du vrai ID Stripe (`sub_xxx`).

Conséquences :
- La résiliation d'abonnement ne trouvait pas la subscription dans la BDD
- Les champs `cancel_at_period_end`, `canceled_at`, `ended_at` n'étaient jamais mis à jour
- Stripe ne pouvait pas être synchronisé correctement avec la BDD locale

## Root Cause

### Architecture incorrecte dans `handlePaymentIntentSucceeded`

Le code créait une entrée `stripe_subscriptions` pour **TOUS** les types de paiement (lifetime ET monthly) dans `handlePaymentIntentSucceeded`, avec un ID synthétique :

```typescript
// AVANT (bugué)
if (price_type) {
    const isLifetime = price_type.includes("lifetime")
    const fakeStripeSubId = `${isLifetime ? 'lifetime' : 'onetime'}_${paymentIntent.id}`
    // Crée l'entrée avec fakeStripeSubId pour TOUS les types
}
```

**Problème :** Pour les abonnements monthly, Stripe envoie ensuite l'event `customer.subscription.created` avec le vrai `sub_xxx`. Mais l'entrée existait déjà avec le mauvais ID, et le webhook bloquait la mise à jour ("BLOCKED: User already has active PREMIUM").

### Flow correct

| Type | Stripe crée une subscription ? | Action dans `handlePaymentIntentSucceeded` |
|------|-------------------------------|-------------------------------------------|
| **Lifetime** | ❌ Non | Créer avec `lifetime_pi_xxx` |
| **Monthly** | ✅ Oui (`sub_xxx`) | NE PAS créer - attendre `customer.subscription.created` |

## Fix Appliqué

### 1. `stripe-webhook/index.ts` - Ne créer que pour lifetime

```typescript
// APRÈS (fixé)
const isLifetime = price_type?.includes("lifetime") || false
const isMonthly = price_type?.includes("monthly") || false

// FIX BUG: Only create stripe_subscriptions record for LIFETIME purchases here
// Monthly subscriptions will be created by handleSubscriptionUpdate with the real Stripe sub_xxx ID
if (price_type && isLifetime) {
    const fakeStripeSubId = `lifetime_${paymentIntent.id}`
    // Crée l'entrée UNIQUEMENT pour lifetime
}
```

### 2. `stripe-webhook/index.ts` - `.single()` → `.maybeSingle()`

```typescript
// FIX BUG: Use .maybeSingle() instead of .single() to avoid 406 error
const { data: existing } = await supabase
    .from("stripe_subscriptions")
    .select("id")
    .eq("stripe_subscription_id", subscription.id)
    .maybeSingle()  // Retourne null au lieu d'erreur 406 si 0 résultats
```

### 3. `stripe-webhook/index.ts` - Bon `payment_type` pour monthly

```typescript
// Determine payment_type based on price_type
const isMonthlySubscription = price_type?.includes("monthly") || false
const paymentType = isMonthlySubscription ? "subscription_payment" : "one_time_payment"
```

### 4. `cancel-subscription/index.ts` - `.single()` → `.maybeSingle()`

```typescript
// FIX: Use .maybeSingle() instead of .single() to avoid 406 error when 0 results
const { data: userByAuthId } = await supabase
    .from("users")
    .select("id, stripe_subscription_id, stripe_customer_id")
    .eq("auth_id", userId)
    .maybeSingle()
```

### 5. `cancel-subscription/index.ts` - Vérifier les rows updated

```typescript
// FIX: Add .select() to verify rows were updated
const { data: updatedRows, error: updateError } = await supabase
    .from("stripe_subscriptions")
    .update({...})
    .eq("stripe_subscription_id", stripeSubscriptionId)
    .select("id")

if (!updatedRows || updatedRows.length === 0) {
    console.warn(`No rows updated for stripe_subscription_id: ${stripeSubscriptionId}`)
}
```

## Validation

- [x] Achat abonnement monthly → `stripe_subscriptions.stripe_subscription_id = sub_xxx` ✅
- [x] Résiliation → `cancel_at_period_end`, `canceled_at`, `ended_at` mis à jour ✅
- [x] `stripe_payments.payment_type = subscription_payment` pour monthly ✅
- [x] Pas d'erreur 406 dans les logs ✅
- [x] Tests unitaires passent ✅

## Fichiers Modifiés

| Fichier | Lignes modifiées | Description |
|---------|-----------------|-------------|
| `supabase/functions/stripe-webhook/index.ts` | ~289-301, ~267-274, ~921-928 | Ne créer stripe_subscriptions que pour lifetime, fix payment_type, .maybeSingle() |
| `supabase/functions/cancel-subscription/index.ts` | ~58-76, ~92-105, ~133-156 | .maybeSingle(), vérification rows updated |
| `supabase/functions/stripe-webhook/index.test.ts` | +80 lignes | Tests de régression |

## Déploiement

```bash
# Copier les fichiers
scp supabase/functions/stripe-webhook/index.ts user@server:~/path/to/functions/stripe-webhook/
scp supabase/functions/cancel-subscription/index.ts user@server:~/path/to/functions/cancel-subscription/

# Redémarrer
docker restart motium-edge-functions
```

## Leçons Apprises

1. **Lifetime vs Monthly** : Les achats lifetime n'ont pas de subscription Stripe (juste un PaymentIntent). Les monthly ont une vraie subscription qu'il faut attendre via `customer.subscription.created`.

2. **`.single()` vs `.maybeSingle()`** : Toujours utiliser `.maybeSingle()` quand 0 résultat est un cas valide. `.single()` retourne erreur 406 "Not Acceptable".

3. **Vérifier les updates** : PostgREST retourne 204 même si 0 lignes sont mises à jour. Ajouter `.select()` pour vérifier.

4. **Events Stripe** : L'ordre des events n'est pas garanti. Ne pas créer de données dans `payment_intent.succeeded` si `customer.subscription.created` va les créer avec de meilleures données.
