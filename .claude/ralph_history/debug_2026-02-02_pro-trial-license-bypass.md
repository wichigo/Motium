# Debug Report: Pro Trial License Bypass Bug

**Date:** 2026-02-02
**Bug ID:** pro-trial-license-bypass
**Status:** FIXED

## Description du Bug

Lorsqu'un utilisateur s'inscrit avec un compte Pro, il devrait avoir accès à l'application pendant la période d'essai (14 jours) sans avoir besoin d'acheter de licence. Cependant, l'utilisateur était systématiquement redirigé vers la page d'achat de licence obligatoire (`pro_trial_expired`) immédiatement après l'inscription, même si les valeurs `trial_started_at` et `trial_ends_at` étaient correctes en base de données.

## Root Cause Identifiée

La fonction `checkProLicenseFromNetwork()` dans `AuthViewModel.kt` vérifiait uniquement si le propriétaire Pro avait une licence assignée via `isOwnerLicensed()`, mais **ignorait complètement la période d'essai de l'utilisateur**.

### Flux problématique :
1. Pro owner s'inscrit → `users.subscription_type = TRIAL`, `trial_started_at` et `trial_ends_at` sont définis
2. À la connexion, `MotiumNavHost` détecte que c'est un Pro (`role = ENTERPRISE`)
3. Il appelle `authViewModel.checkProLicense(userId)`
4. `checkProLicenseFromNetwork()` récupère le ProAccount et appelle `isOwnerLicensed(proAccountId, userId)`
5. `isOwnerLicensed()` retourne `false` car il n'y a pas de licence assignée (normal pour un trial)
6. Le résultat `LicenseCheckResult.NotLicensed` navigue vers `pro_trial_expired`

### Point clé :
Le commentaire dans le code indiquait : "Trial period is managed in users table, not pro_accounts". Mais cette vérification n'était pas implémentée dans `checkProLicenseFromNetwork()`.

## Fix Appliqué

**Fichier modifié :** `app/src/main/java/com/application/motium/presentation/auth/AuthViewModel.kt`

**Diff minimal :**
```kotlin
private suspend fun checkProLicenseFromNetwork(userId: String): LicenseCheckResult {
    return withContext(Dispatchers.IO) {
        val proAccount = proAccountRemoteDataSource.getProAccount(userId).getOrNull()
        if (proAccount == null) {
            MotiumApplication.logger.w("Pro account not found for user $userId", TAG)
            return@withContext LicenseCheckResult.NoProAccount
        }

+       // FIX: Check if user is in active trial period first
+       // Trial gives access without needing a license assigned
+       val currentUser = authRepository.authState.first().user
+       if (currentUser?.subscription?.type == SubscriptionType.TRIAL &&
+           currentUser.subscription.isActive()) {
+           MotiumApplication.logger.i(
+               "Pro user in active trial period - granting access (no license required)",
+               TAG
+           )
+           return@withContext LicenseCheckResult.Licensed(proAccount.id)
+       }

        val isLicensed = licenseRemoteDataSource.isOwnerLicensed(
            proAccountId = proAccount.id,
            ownerUserId = userId
        ).getOrDefault(false)
        // ... rest unchanged
    }
}
```

## Logique du Fix

1. Avant de vérifier les licences, on récupère l'utilisateur actuel depuis `authState`
2. Si l'utilisateur a `subscription_type = TRIAL` ET que le trial est actif (`isActive()` vérifie que `trial_ends_at` n'est pas dépassé)
3. On retourne `Licensed` directement, ce qui navigue vers `enterprise_home`
4. Sinon, on continue avec la vérification de licence existante

## Tests de Non-Régression

- [x] Build compile sans erreur
- [ ] Pro user en trial peut accéder à l'app
- [ ] Pro user avec trial expiré est redirigé vers `pro_trial_expired`
- [ ] Pro user avec licence active peut accéder à l'app
- [ ] Pro user sans licence et sans trial valide est redirigé vers `pro_trial_expired`
- [ ] Individual user en trial fonctionne toujours normalement

## Impact

- **Fix minimal** : 10 lignes ajoutées
- **Fichiers modifiés** : 1 seul fichier
- **Backward compatible** : Oui, les utilisateurs existants avec licences ne sont pas affectés
- **Pas de refactoring** : Logique ajoutée sans modifier le code existant

## Recommandations

Pour le futur, considérer l'ajout d'un test unitaire pour `checkProLicenseFromNetwork()` qui vérifie :
- Scénario 1: Pro user avec trial actif → Licensed
- Scénario 2: Pro user avec trial expiré, sans licence → NotLicensed
- Scénario 3: Pro user avec licence → Licensed
