# Debug Report: Cancel Subscription "User not found"

**Date**: 2026-02-03
**Bug ID**: cancel-subscription-user-not-found
**Status**: FIXED

## Description du Bug

Lors de la résiliation d'un abonnement via l'écran Settings, l'erreur "User not found" est levée alors que l'utilisateur est bien connecté et a un abonnement actif.

**Logs d'erreur**:
```
SubscriptionManager E ❌ Subscription cancellation failed: User not found
java.lang.Exception: User not found
    at SubscriptionManager$cancelSubscription$2.invokeSuspend(SubscriptionManager.kt:840)
```

## Root Cause

### Incohérence d'ID entre auth_id et public.users.id

Dans l'Edge Function `cancel-subscription/index.ts`, le paramètre `userId` reçu était utilisé de deux manières incohérentes :

1. **Ligne 55 (avant fix)** : Query sur `users` avec `.eq("auth_id", userId)`
   - Suppose que `userId` est l'UUID de Supabase Auth (`auth.users.id`)

2. **Ligne 73 (avant fix)** : Query sur `stripe_subscriptions` avec `.eq("user_id", userId)`
   - Suppose que `userId` est l'UUID de `public.users.id`

**Problème** : Ce sont deux IDs différents !
- `auth_id` = UUID généré par Supabase Auth lors de la création du compte
- `public.users.id` = UUID généré pour la table `users` (FK vers auth_id)

### Côté Android

Dans `SupabaseAuthRepository.kt:1652`:
```kotlin
val resolvedId = id ?: auth_id
```

Le `User.id` peut être soit `public.users.id` (préféré), soit `auth_id` en fallback.

Quand l'app envoyait l'ID de `public.users` (comportement normal), l'Edge Function échouait à la première requête `.eq("auth_id", userId)` car ce n'était pas un `auth_id`.

## Fix Appliqué

**Fichier modifié** : `supabase/functions/cancel-subscription/index.ts`

**Changement** : L'Edge Function essaie maintenant les deux colonnes :

1. D'abord cherche par `auth_id`
2. Si non trouvé, cherche par `id` (public.users.id)
3. Utilise ensuite le `public.users.id` récupéré pour la requête sur `stripe_subscriptions`

```typescript
// First try: userId is auth_id
const { data: userByAuthId, error: authIdError } = await supabase
  .from("users")
  .select("id, stripe_subscription_id, stripe_customer_id")
  .eq("auth_id", userId)
  .single()

if (userByAuthId) {
  user = userByAuthId
} else {
  // Second try: userId is the public.users.id directly
  const { data: userById, error: idError } = await supabase
    .from("users")
    .select("id, stripe_subscription_id, stripe_customer_id")
    .eq("id", userId)
    .single()
  // ...
}

// Use the public.users.id for subsequent queries
const publicUserId = user.id
```

## Test de Reproduction

Pour reproduire le bug (avant fix) :
1. Se connecter avec un compte ayant un abonnement actif
2. Aller dans Paramètres > Abonnement
3. Cliquer sur "Résilier mon abonnement"
4. Observer l'erreur "User not found"

Après fix :
- La résiliation devrait fonctionner correctement
- L'abonnement passe en `cancel_at_period_end: true`

## Non-Régression

- Aucun autre fichier modifié
- Fix backward compatible (supporte les deux formats d'ID)
- Pas de changement de schéma DB requis

## Déploiement

```bash
cd supabase/functions
supabase functions deploy cancel-subscription
```

## Leçons Apprises

1. **Toujours documenter la sémantique des IDs** : Dans un système avec auth séparé (Supabase Auth) et table users, il y a toujours 2 IDs. Les APIs doivent être explicites sur lequel est attendu.

2. **Defensive coding** : L'Edge Function aurait dû supporter les deux formats dès le départ, ou l'app aurait dû toujours envoyer l'`auth_id`.

3. **Nommage explicite** : Renommer le paramètre `userId` en `authIdOrUserId` ou avoir deux paramètres distincts clarifierait l'intention.
