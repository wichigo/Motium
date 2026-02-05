# Debug Report: canceled_at et ended_at non remplis après résiliation

**Date**: 2026-02-03
**Bug ID**: subscription-cancellation-timestamps
**Statut**: ✅ FIXÉ

---

## Description du Bug

Lors de la résiliation d'un abonnement mensuel (individual ou pro license) via l'app :
- `stripe_subscriptions.canceled_at` reste NULL
- `stripe_subscriptions.ended_at` reste NULL

---

## Root Cause Analysis

**Fichier**: `supabase/functions/stripe-webhook/index.ts`
**Fonction**: `handleSubscriptionUpdate()`

### Flow bugué :

```
1. User clique "Résilier" dans l'app
2. cancel-subscription Edge Function appelée
   → Met canceled_at = NOW() en base ✅
   → Appelle Stripe avec cancel_at_period_end = true
3. Stripe envoie webhook customer.subscription.updated
4. handleSubscriptionUpdate() traite le webhook
   → Écrase canceled_at avec subscription.canceled_at de Stripe
   → Stripe retourne null car subscription pas encore terminée
   → canceled_at devient NULL ❌
```

### Cause racine :

Quand `cancel_at_period_end = true`, Stripe ne remplit `subscription.canceled_at` que quand la subscription est **vraiment terminée** (à la fin de période), pas au moment de la demande d'annulation.

Le code écrasait avec `null` :
```typescript
// AVANT (BUG)
canceled_at: subscription.canceled_at
  ? new Date(subscription.canceled_at * 1000).toISOString()
  : null,  // ← Écrase avec NULL !
```

---

## Fix Appliqué

**Fichier**: `supabase/functions/stripe-webhook/index.ts`

```typescript
// APRÈS (FIX)
canceled_at: subscription.canceled_at
  ? new Date(subscription.canceled_at * 1000).toISOString()
  : subscription.cancel_at_period_end
    ? new Date().toISOString()  // Date de demande d'annulation
    : null,

ended_at: subscription.ended_at
  ? new Date(subscription.ended_at * 1000).toISOString()
  : (subscription as any).cancel_at
    ? new Date((subscription as any).cancel_at * 1000).toISOString()
    : null,
```

### Logique :

| Champ | Valeur Stripe | Condition | Valeur en DB |
|-------|---------------|-----------|--------------|
| `canceled_at` | timestamp | - | Date Stripe |
| `canceled_at` | null | `cancel_at_period_end = true` | NOW() (date demande) |
| `canceled_at` | null | `cancel_at_period_end = false` | null |
| `ended_at` | timestamp | - | Date Stripe |
| `ended_at` | null | `cancel_at` existe | Date prévue de fin |
| `ended_at` | null | pas de `cancel_at` | null |

---

## Fichiers Modifiés

| Fichier | Modification |
|---------|--------------|
| `supabase/functions/stripe-webhook/index.ts` | Fix logique `canceled_at` et `ended_at` dans `handleSubscriptionUpdate()` |

---

## Déploiement

```bash
supabase functions deploy stripe-webhook --no-verify-jwt
```

---

## Validation

1. Créer un abonnement mensuel
2. Résilier via l'app (Paramètres → Votre abonnement → Résilier)
3. Vérifier en base :
   - `cancel_at_period_end` = `true` ✅
   - `canceled_at` = date de la demande ✅
   - `ended_at` = date de fin de période ✅

---

## Non-régression

- ✅ Annulation immédiate (`cancelImmediately = true`) : utilise `subscription.canceled_at` de Stripe
- ✅ Subscription active sans annulation : `canceled_at` et `ended_at` restent null
- ✅ Subscription terminée naturellement : `ended_at` de Stripe utilisé
