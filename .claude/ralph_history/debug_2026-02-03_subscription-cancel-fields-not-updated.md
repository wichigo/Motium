# Debug Report: Subscription Cancel Fields Not Updated

**Date:** 2026-02-03
**Bug ID:** subscription-cancel-fields-not-updated
**Severity:** High
**Status:** FIXED

## Description du Bug

Lors de la résiliation d'un abonnement mensuel (Individual ou Pro license), les champs suivants dans `stripe_subscriptions` restaient à leurs valeurs par défaut:
- `cancel_at_period_end` restait FALSE (devrait être TRUE)
- `canceled_at` restait NULL (devrait contenir la date d'annulation)
- `ended_at` restait NULL (devrait contenir la date de fin programmée)

## Reproduction

1. App Android → "Votre abonnement" popup → "Résilier l'abonnement"
2. Stripe recevait correctement l'annulation (`cancel_at_period_end: true`, `canceled_at` défini)
3. La base de données Supabase n'était pas mise à jour

## Root Cause

### Erreur HTTP 406 "Not Acceptable"

Les logs gateway montraient des erreurs 406 sur les requêtes GET:
```
GET /rest/v1/stripe_subscriptions?select=id&stripe_subscription_id=eq.sub_xxx → 406
```

**Cause technique:** Utilisation de `.single()` au lieu de `.maybeSingle()` dans les requêtes Supabase.

Dans PostgREST (backend Supabase), `.single()` retourne une erreur 406 "Not Acceptable" quand:
- 0 lignes sont retournées (le cas ici)
- Plus de 1 ligne est retournée

`.maybeSingle()` retourne `null` quand 0 lignes sont retournées, permettant au code de continuer.

### Impact sur le code

Dans `stripe-webhook/index.ts`, fonction `handleSubscriptionUpdate`:

```typescript
// AVANT (bugué)
const { data: existing } = await supabase
  .from("stripe_subscriptions")
  .select("id")
  .eq("stripe_subscription_id", subscription.id)
  .single()  // ← Génère erreur 406 si 0 résultats
```

Quand la requête échouait avec 406:
1. `existing` devenait `undefined`
2. Le code tentait un INSERT au lieu d'un UPDATE
3. L'INSERT échouait silencieusement (contrainte UNIQUE sur `stripe_subscription_id`)
4. Les champs `cancel_at_period_end`, `canceled_at`, `ended_at` n'étaient jamais mis à jour

## Fix Appliqué

### 1. stripe-webhook/index.ts (ligne 921-927)

```typescript
// APRÈS (fixé)
// FIX BUG: Use .maybeSingle() instead of .single() to avoid 406 error
// when the subscription doesn't exist yet. .single() throws 406 "Not Acceptable"
// when 0 rows are returned, while .maybeSingle() returns null gracefully.
const { data: existing } = await supabase
  .from("stripe_subscriptions")
  .select("id")
  .eq("stripe_subscription_id", subscription.id)
  .maybeSingle()
```

### 2. cancel-subscription/index.ts (lignes 58-76)

```typescript
// FIX: Use .maybeSingle() instead of .single() to avoid 406 error when 0 results
const { data: userByAuthId, error: authIdError } = await supabase
  .from("users")
  .select("id, stripe_subscription_id, stripe_customer_id")
  .eq("auth_id", userId)
  .maybeSingle()  // ← Changé de .single()

// ... et pareil pour les autres requêtes
```

### 3. cancel-subscription/index.ts - Vérification du PATCH (lignes 133-156)

```typescript
// FIX: Add .select() to verify rows were updated (PostgREST returns 204 even if 0 rows match)
const { data: updatedRows, error: updateError } = await supabase
  .from("stripe_subscriptions")
  .update({...})
  .eq("stripe_subscription_id", stripeSubscriptionId)
  .select("id")

if (!updatedRows || updatedRows.length === 0) {
  console.warn(`No rows updated for stripe_subscription_id: ${stripeSubscriptionId}`)
}
```

## Tests Ajoutés

4 nouveaux tests dans `stripe-webhook/index.test.ts`:

1. `BUG FIX: .maybeSingle() returns null instead of 406 error`
2. `BUG FIX: existing check uses correct logic after fix`
3. `BUG FIX: subscription update flow after .maybeSingle() fix`
4. `BUG FIX: cancel-subscription PATCH verifies rows updated`

## Validation

- [x] Tests unitaires passent (51/51)
- [x] Logique de mise à jour des champs cancel_at_period_end, canceled_at, ended_at vérifiée
- [x] Code de cancel-subscription amélioré pour détecter les échecs silencieux
- [x] Pas de régression sur les autres fonctionnalités

## Fichiers Modifiés

| Fichier | Changement |
|---------|------------|
| `supabase/functions/stripe-webhook/index.ts` | `.single()` → `.maybeSingle()` ligne 925 |
| `supabase/functions/cancel-subscription/index.ts` | `.single()` → `.maybeSingle()` lignes 62, 72, 100 |
| `supabase/functions/cancel-subscription/index.ts` | Ajout `.select()` et vérification des lignes mises à jour |
| `supabase/functions/stripe-webhook/index.test.ts` | 4 nouveaux tests pour le bug fix |

## Déploiement

Pour appliquer le fix en production:

```bash
# Déployer les Edge Functions
supabase functions deploy stripe-webhook --no-verify-jwt
supabase functions deploy cancel-subscription
```

## Leçons Apprises

1. **`.single()` vs `.maybeSingle()`**: Toujours utiliser `.maybeSingle()` quand on s'attend à 0 ou 1 résultat
2. **PATCH sans `.select()`**: PostgREST retourne 204 même si 0 lignes sont mises à jour - ajouter `.select()` pour vérifier
3. **Logs gateway**: Les erreurs 406 dans les logs gateway indiquent souvent un problème de `.single()` sur requêtes vides
