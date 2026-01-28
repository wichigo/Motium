# Debug: License Assignment Not Syncing to Supabase

**Date:** 2026-01-28
**Bug ID:** license_sync_to_supabase
**Status:** FIXED

## Description du Bug

L'assignation de licence fonctionne localement (Room DB) mais ne se synchronise pas vers Supabase :
- `licenses.linked_account_id` reste NULL dans Supabase
- `licenses.status` reste "available" dans Supabase
- `company_links.status` reste "INACTIVE"
- `users.subscription_type` reste "EXPIRED"

L'app affiche correctement : licence active, statut licencié, collaborateur avec licence.

## Root Cause (3 niveaux)

### Niveau 1 - LicenseDao (local)
`LicenseDao.assignLicense()` ne mettait pas à jour le `status` de "available" vers "active" localement.

### Niveau 2 - Payload de sync
`OfflineFirstLicenseRepository.assignLicenseToAccount()` n'incluait que `linked_account_id` dans le payload, pas `status = "active"`.

### Niveau 3 - SQL function + Trigger (ROOT CAUSE PRINCIPALE)
Le trigger `check_license_assignment_authorization` exige que `company_links.status = 'ACTIVE'` avant d'autoriser une assignation de licence. Or les company_links étaient INACTIVE dans Supabase (précédemment unlinked).

La fonction `push_license_change()` ne gérait pas :
- Le changement de `status` vers "active" depuis le payload
- La réactivation des company_links INACTIVE
- La mise à jour de `users.subscription_type`

## Fix Appliqué

### 1. LicenseDao.kt (ligne 44)
```diff
- @Query("UPDATE licenses SET linkedAccountId = :userId, linkedAt = :linkedAt, syncStatus = 'PENDING_UPLOAD', localUpdatedAt = :now, version = version + 1 WHERE id = :licenseId")
+ @Query("UPDATE licenses SET linkedAccountId = :userId, linkedAt = :linkedAt, status = 'active', syncStatus = 'PENDING_UPLOAD', localUpdatedAt = :now, version = version + 1 WHERE id = :licenseId")
```

### 2. OfflineFirstLicenseRepository.kt (ligne 244)
```diff
  val payload = buildJsonObject {
      put("linked_account_id", linkedAccountId)
+     put("status", "active")
  }.toString()
```

### 3. push_license_change() SQL function
- Ajout réactivation company_link AVANT l'UPDATE licence (évite le blocage du trigger)
- Gestion `status = 'active'` depuis le payload
- Mise à jour automatique `users.subscription_type = 'LICENSED'`

### 4. Nettoyage processed_sync_operations
Suppression des entrées échouées avec "is not an active member" pour permettre le retry.

## Fichiers Modifiés
- `app/src/main/java/com/application/motium/data/local/dao/LicenseDao.kt`
- `app/src/main/java/com/application/motium/data/repository/OfflineFirstLicenseRepository.kt`
- Supabase SQL: `push_license_change()` function

## Test de Validation
1. Désassigner une licence existante dans l'app
2. Réassigner la licence à un collaborateur
3. Vérifier dans Supabase :
   - `licenses.linked_account_id` = UUID du collaborateur
   - `licenses.status` = "active"
   - `company_links.status` = "ACTIVE"
   - `users.subscription_type` = "LICENSED"
