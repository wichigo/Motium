# Debug Report: Dark Theme Fixes (Dialog + Calendar)

**Date:** 2026-02-03
**Bug ID:** dark-theme-fixes
**Fichiers:** `BackgroundLocationPermissionDialog.kt`, `AddTripScreen.kt`
**Statut:** FIXED

---

## Description du Bug

Le dialog "Localisation en arrière-plan" (`BackgroundLocationPermissionDialog`) affichait un fond blanc même en mode sombre, car la couleur du container était hardcodée à `Color.White`.

## Reproduction

1. Activer le mode sombre sur le téléphone
2. Ouvrir l'app Motium
3. Observer que le popup de permission de localisation a un fond blanc

## Root Cause

**Ligne 44-46 (avant fix):**
```kotlin
colors = CardDefaults.cardColors(
    containerColor = Color.White,  // Couleur hardcodée !
),
```

La couleur était hardcodée au lieu d'utiliser le thème Material.

## Fix Appliqué

**Diff minimal:**
```diff
- containerColor = Color.White,
+ containerColor = MaterialTheme.colorScheme.surface,
```

Suppression de l'import inutilisé:
```diff
- import androidx.compose.ui.graphics.Color
```

## Validation

- [x] Compilation réussie (`./gradlew compileDebugKotlin`)
- [x] Fix minimal (1 ligne modifiée + 1 import supprimé)
- [x] Utilise les couleurs du thème Material 3
- [x] Compatible mode clair et sombre

## Couleurs Appliquées

| Mode | Couleur Surface |
|------|-----------------|
| Clair | `#FFFFFF` (SurfaceLight) |
| Sombre | `#1a2332` (SurfaceDark) |

## Non-Régression

Le dialog utilise déjà `MaterialTheme.colorScheme.onSurfaceVariant` pour les textes et `MaterialTheme.colorScheme.surfaceVariant` pour la Card d'instructions, donc tout le reste s'adapte correctement au thème.

---

## Bug #2: Calendrier AddTripScreen

### Description

Le calendrier inline dans `AddTripScreen.kt` avait plusieurs `Color.White` hardcodés.

### Fix Appliqué

**Diff:**
```diff
- containerColor = Color.White
+ containerColor = MaterialTheme.colorScheme.surface

- .background(Color.White)
+ (supprimé - inutile, Card gère déjà le fond)

- else -> Color(0xFF1F2937)
+ else -> MaterialTheme.colorScheme.onSurface

- isSelected -> Color.White
+ isSelected -> MaterialTheme.colorScheme.onPrimary
```

### Validation

- [x] Compilation réussie
- [x] Fix minimal (4 modifications)
- [x] Utilise les couleurs du thème Material 3
- [x] Compatible mode clair et sombre

### Note: EditTripScreen

Vérifié : `EditTripScreen.kt` n'a pas de calendrier inline, seulement des champs date read-only qui utilisent déjà le thème. ✅
