# Debug Report: Stripe payment_type et stripe_price_id incorrects

**Date**: 2026-02-03
**Bug ID**: stripe-payment-type-price-id-bug
**Statut**: ✅ FIXÉ

---

## Description du Bug

Lors de la souscription à un abonnement mensuel :
1. `stripe_payments.payment_type` = `'one_time_payment'` au lieu de `'subscription_payment'`
2. `stripe_subscriptions.stripe_price_id` = `NULL` au lieu du Price ID Stripe

---

## Root Cause Analysis

### Bug 1: `payment_type = 'one_time_payment'`

**Fichier**: `supabase/functions/stripe-webhook/index.ts`
**Fonction**: `handlePaymentIntentSucceeded()`

**Cause**: Quand un utilisateur paie un abonnement mensuel, Stripe envoie deux events :
1. `customer.subscription.created` → `handleSubscriptionUpdate()`
2. `payment_intent.succeeded` → `handlePaymentIntentSucceeded()`

Le PaymentIntent de la première invoice d'une subscription déclenche `handlePaymentIntentSucceeded()` qui insère **toujours** `payment_type: "one_time_payment"` (ligne 266) sans vérifier si le paiement vient d'une subscription.

L'event `invoice.paid` arrive ensuite avec `handleInvoicePaid()` qui aurait inséré le bon `payment_type: "subscription_payment"`, mais le record existe déjà.

### Bug 2: `stripe_price_id = NULL`

**Fichier**: `supabase/functions/create-payment-intent/index.ts`

**Cause**: Le code utilisait `price_data` inline pour créer les subscriptions :

```typescript
// AVANT (BUG)
items: [
  {
    price_data: {
      currency: "eur",
      product: productId,
      unit_amount: priceConfig,
      recurring: { interval: "month" },
    },
    quantity: quantity,
  },
],
```

Avec `price_data`, Stripe crée un prix éphémère à chaque appel. Ce prix n'a pas d'ID stable et n'est pas correctement propagé dans `subscription.items.data[0].price.id`.

L'utilisateur a des Price ID prédéfinis dans Stripe mais ils n'étaient pas utilisés.

---

## Fix Appliqué

### Fix 1: Skip les PaymentIntents de subscriptions

**Fichier**: `supabase/functions/stripe-webhook/index.ts:178-189`

```typescript
async function handlePaymentIntentSucceeded(
  supabase: any,
  paymentIntent: Stripe.PaymentIntent
) {
  // FIX BUG: Skip PaymentIntents that belong to subscriptions (invoices)
  // These are handled by handleInvoicePaid() with correct payment_type="subscription_payment"
  if (paymentIntent.invoice) {
    console.log(`ℹ️ PaymentIntent ${paymentIntent.id} is linked to invoice ${paymentIntent.invoice} - skipping`)
    return
  }
  // ... rest of function
}
```

**Logique**: Un PaymentIntent avec un champ `invoice` non-null est forcément lié à une subscription. On skip et laisse `handleInvoicePaid()` créer le record avec le bon `payment_type`.

### Fix 2: Utiliser les Price ID prédéfinis

**Fichier**: `supabase/functions/create-payment-intent/index.ts`

Ajout des Price ID :
```typescript
const PRICE_IDS = {
  individual_monthly: "price_1Siz4GCsRT1u49RIG9npZVR4",
  individual_lifetime: "price_1SgUbHCsRT1u49RIfI1RbQCY",
  pro_license_monthly: "price_1SgUYGCsRT1u49RImUY0mvZQ",
  pro_license_lifetime: "price_1SgUeYCsRT1u49RItR5LyYGU",
}
```

Modification de la création de subscription :
```typescript
// APRÈS (FIX)
const stripePriceId = PRICE_IDS[priceType as PriceType]

const subscription = await stripe.subscriptions.create({
  customer: customerId,
  items: [
    {
      price: stripePriceId,  // Use predefined Price ID
      quantity: quantity,
    },
  ],
  // ...
})
```

---

## Fichiers Modifiés

| Fichier | Modification |
|---------|--------------|
| `supabase/functions/stripe-webhook/index.ts` | Ajout check `paymentIntent.invoice` au début de `handlePaymentIntentSucceeded()` |
| `supabase/functions/create-payment-intent/index.ts` | Ajout `PRICE_IDS`, remplacement `price_data` par `price: stripePriceId` |

---

## Test de Validation

### Avant le fix :
- Créer un abonnement mensuel
- `stripe_payments.payment_type` = `'one_time_payment'` ❌
- `stripe_subscriptions.stripe_price_id` = `NULL` ❌

### Après le fix :
- Créer un abonnement mensuel
- `stripe_payments.payment_type` = `'subscription_payment'` ✅
- `stripe_subscriptions.stripe_price_id` = `'price_1Siz4GCsRT1u49RIG9npZVR4'` ✅

---

## Déploiement

```bash
# Déployer les Edge Functions
supabase functions deploy stripe-webhook --no-verify-jwt
supabase functions deploy create-payment-intent
```

---

## Non-régression

- ✅ Les paiements lifetime (one-time) continuent de fonctionner (`handlePaymentIntentSucceeded()` est appelé car `invoice` est null)
- ✅ Les renewals de subscription continuent de fonctionner (`handleInvoicePaid()` gère tous les paiements d'invoice)
- ✅ Les Price ID correspondent aux produits existants dans Stripe

---

## Leçons Apprises

1. **Ne jamais utiliser `price_data` inline en production** - toujours créer des Price dans Stripe Dashboard et utiliser leurs IDs
2. **Vérifier la source d'un PaymentIntent** - un PaymentIntent peut venir d'un paiement direct ou d'une invoice de subscription
3. **Les events Stripe peuvent se chevaucher** - `payment_intent.succeeded` et `invoice.paid` peuvent arriver dans n'importe quel ordre
