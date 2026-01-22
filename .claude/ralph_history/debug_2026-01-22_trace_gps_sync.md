# Ralph Debug Report - trace_gps Sync Bug

**Date:** 2026-01-22
**Bug ID:** trace_gps_sync
**Severity:** High (Data Loss)
**Status:** FIXED ✅

---

## Bug Description

Les trajets créés via auto-tracking perdaient leur `trace_gps` (coordonnées GPS du parcours) après synchronisation avec Supabase. La carte du trajet disparaissait car `trace_gps` devenait `NULL`.

### Symptômes
- Trajet auto-tracking créé localement → carte visible
- Après `sync_changes()` → carte disparaît
- `trace_gps` devient `NULL` dans Room après le pull

---

## Root Cause

**Mismatch de noms de champs JSON entre PUSH et PULL**

### PUSH (TripRepository.kt:379-394)
```kotlin
putJsonArray("trace_gps") {
    put("lat", loc.latitude)      // ← Clé abrégée
    put("lon", loc.longitude)     // ← Clé abrégée
    put("ts", loc.timestamp)      // ← Clé abrégée
    put("acc", loc.accuracy)      // ← Clé abrégée
}
```

### PULL (ChangeEntityMapper.kt:67 → GpsPoint)
```kotlin
data class GpsPoint(
    val latitude: Double,    // ← Attendait "latitude", pas "lat"
    val longitude: Double,   // ← Attendait "longitude", pas "lon"
    val timestamp: Long,     // ← Attendait "timestamp", pas "ts"
    val accuracy: Float?     // ← Attendait "accuracy", pas "acc"
)
```

### Flux du bug
1. **PUSH**: Payload envoyé avec `{lat, lon, ts, acc}` → stocké correctement en DB
2. **PULL**: Serveur renvoie `{lat, lon, ts, acc}`
3. **PARSE**: `GpsPoint` cherche `{latitude, longitude, timestamp, accuracy}`
4. **FAIL**: Désérialisation échoue silencieusement → `emptyList()`
5. **SAVE**: `locations = []` → `trace_gps` devient vide

---

## Fix Appliqué

**Fichier:** `TripRemoteDataSource.kt:36-46`

**Diff minimal:**
```diff
 @Serializable
 data class GpsPoint(
+    @kotlinx.serialization.SerialName("lat")
     val latitude: Double,
+    @kotlinx.serialization.SerialName("lon")
     val longitude: Double,
+    @kotlinx.serialization.SerialName("ts")
     val timestamp: Long,
+    @kotlinx.serialization.SerialName("acc")
-    val accuracy: Float?
+    val accuracy: Float? = null
 )
```

**Explication:**
- Ajout de `@SerialName` pour mapper les clés JSON abrégées (`lat`, `lon`, `ts`, `acc`) vers les propriétés Kotlin
- Les noms de propriétés restent lisibles (`latitude`, `longitude`, etc.)
- `accuracy` devient optionnelle avec valeur par défaut pour robustesse

---

## Validation

- [x] Build réussi (`assembleDebug`)
- [x] Pas de régression de syntaxe
- [x] Aucun autre usage de `GpsPoint` affecté (TripSimulator.GpsPoint est une classe différente)
- [ ] Test manuel recommandé: créer trajet auto-tracking → sync → vérifier carte

---

## Fichiers Modifiés

| Fichier | Lignes | Description |
|---------|--------|-------------|
| `TripRemoteDataSource.kt` | 36-46 | Ajout annotations `@SerialName` à `GpsPoint` |

---

## Impact

- **Fix minimal**: Aucun changement de comportement pour le code existant
- **Backward compatible**: Les propriétés Kotlin gardent les mêmes noms
- **Forward compatible**: Le format JSON `{lat, lon, ts, acc}` est maintenant correctement parsé

---

## Recommandations Futures

1. **Ajouter des logs** dans `parseTraceGps()` pour détecter les échecs de parsing
2. **Considérer la migration** vers des noms complets côté PUSH pour cohérence
3. **Ajouter un test unitaire** pour valider le round-trip PUSH → PULL de `trace_gps`
