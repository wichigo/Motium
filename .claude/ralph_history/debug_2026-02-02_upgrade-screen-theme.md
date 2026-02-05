# Debug Report: UpgradeScreen Theme

**Date**: 2026-02-02
**Bug ID**: upgrade-screen-theme
**Fichier**: `presentation/individual/upgrade/UpgradeScreen.kt`

## Description du Bug

La page "Passez à Premium" (écran d'upgrade pour comptes individuels) restait toujours en thème sombre, même quand l'appareil était en mode clair. Les couleurs du thème sombre ne correspondaient pas non plus à la charte graphique Motium.

## Root Cause

**Couleurs hardcodées** au lieu d'utiliser `MaterialTheme.colorScheme`.

Exemples de couleurs problématiques :
- `Color(0xFF1E1E1E)` - fond TopAppBar et cartes
- `Color(0xFF121212)` - fond Scaffold
- `Color.White` - textes
- `Color(0xFFB0B0B0)` - textes secondaires
- `Color(0xFF3D3D3D)` - bordures
- `Color(0xFF4CAF50)` - vert (non aligné avec `ValidatedGreen`)

## Fix Appliqué

Remplacement systématique des couleurs hardcodées par les tokens du thème Material 3 :

| Avant | Après |
|-------|-------|
| `Color(0xFF1E1E1E)` | `MaterialTheme.colorScheme.surface` |
| `Color(0xFF121212)` | `MaterialTheme.colorScheme.background` |
| `Color.White` (texte) | `MaterialTheme.colorScheme.onSurface` / `onBackground` |
| `Color(0xFFB0B0B0)` | `MaterialTheme.colorScheme.onSurfaceVariant` |
| `Color(0xFF757575)` | `MaterialTheme.colorScheme.onSurfaceVariant` |
| `Color(0xFF3D3D3D)` | `MaterialTheme.colorScheme.outline` |
| `Color(0xFF4CAF50)` | `ValidatedGreen` (import theme) |

### Composants modifiés

1. **TopAppBar** (lignes 115-118)
2. **Scaffold** (ligne 123)
3. **Texte header** (lignes 147, 156)
4. **Texte "Choisissez votre formule"** (ligne 172)
5. **Subscribe button** (lignes 221-222)
6. **Terms text** (ligne 263)
7. **FeaturesList Card** (ligne 287)
8. **FeaturesList items** (lignes 303, 310)
9. **PlanCard** border/background (lignes 329, 334)
10. **PlanCard textes** (lignes 361, 367, 378, 384)
11. **Selection indicator** (ligne 415)

### Couleurs conservées (accent volontaires)

- `Color(0xFFFFD700)` - Icône étoile dorée
- `Color(0xFFFF9800)` - Badge "Économisez 50%" et warning text
- `Color.White` sur fond coloré (bouton, badge)

## Validation

- [x] Compilation réussie (`./gradlew compileDebugKotlin`)
- [x] Fix minimal (uniquement couleurs)
- [x] Pas de refactoring
- [x] Backward compatible

## Fix additionnel : WithdrawalWaiverSection

**Fichier**: `presentation/components/WithdrawalWaiverSection.kt`

Même problème identifié - couleurs hardcodées corrigées :

| Avant | Après |
|-------|-------|
| `Color(0xFF1E1E1E)` | `MaterialTheme.colorScheme.surface` |
| `Color.White` | `MaterialTheme.colorScheme.onSurface` |
| `Color(0xFF4CAF50)` | `ValidatedGreen` |
| `Color(0xFF757575)` | `MaterialTheme.colorScheme.onSurfaceVariant` |
| `Color(0xFFB0B0B0)` | `MaterialTheme.colorScheme.onSurfaceVariant` |

## Fix additionnel : CalendarScreen - TimeSlotEditDialog

**Fichier**: `presentation/individual/calendar/CalendarScreen.kt`

Bug inverse : le dialog "Edit Time Slot" restait en thème clair même en mode sombre.

| Ligne | Avant | Après |
|-------|-------|-------|
| 1332 | `containerColor = Color.White` | `MaterialTheme.colorScheme.surface` |
| 1017 | `color = Color.White` (tab Planning) | `MaterialTheme.colorScheme.surfaceVariant` |

## Impact

- L'écran Upgrade s'adapte maintenant au thème système (clair/sombre)
- La section "Droit de rétractation" suit également le thème
- Le dialog "Edit Time Slot" du calendrier suit également le thème
- Les couleurs correspondent à la charte graphique Motium définie dans `Color.kt` et `Theme.kt`
