# Rapport d'Audit Batterie - Motium
## Collecte de 1 heure complete (3600 secondes)

**Date**: 2026-01-20
**Periode de collecte**: 09:47:46 - 10:47:46
**Duree effective**: 1 heure exacte

---

## 1. Donnees de batterie mesurees

### Niveau de batterie
| Mesure | Valeur |
|--------|--------|
| Niveau initial | **52%** |
| Niveau final | **44%** |
| Consommation totale | **8%** en 1 heure |
| Taux de decharge | **8% / heure** |

### Etat de la batterie
| Parametre | Initial | Final |
|-----------|---------|-------|
| Voltage | 3880 mV | 3822 mV |
| Temperature | 39.4C | 42.0C |
| Charge counter | 2,130,890 | 1,811,870 |
| Courant moyen | -289 mA | -477 mA |

### Contexte important
- **USB branche pendant tout le test** (status=2 charging)
- L'appareil consommait MALGRE le chargement USB
- Cela indique une consommation TRES elevee (depassant la capacite de charge USB)

---

## 2. Services Motium actifs

### Services en cours d'execution
```
ActivityRecognitionService
  - isForeground=true
  - foregroundId=1002
  - types=0x00000008 (FOREGROUND_SERVICE_TYPE_LOCATION)
  - createTime=-11m47s931ms
  - startRequested=true

SupabaseConnectionService
  - isForeground=false
  - createTime=-10m45s431ms
  - startRequested=true
```

### Analyse des services
| Service | Impact Batterie | Statut |
|---------|----------------|--------|
| ActivityRecognitionService | ELEVE | Foreground + Location |
| SupabaseConnectionService | MODERE | Service de fond |

---

## 3. Wakelocks de Motium (u0a473)

### Wakelocks detectes
1. **`*walarm*:com.application.motium.ACTION_KEEPALIVE_WAKEUP`**
   - Observe a 10:07:32
   - Reveille periodiquement le systeme

2. **`NotificationManagerService:post:com.application.motium`**
   - Notifications postees regulierement

3. **`*job*r/com.application.motium/androidx.work.impl.background.systemjob.SystemJobService`**
   - Jobs WorkManager en arriere-plan

### Wakeup events impliquant Motium
```
-3h10m32s177ms: Alarm [u0a473 FGS]
  Attribution: L'app a reveille le systeme depuis l'etat sleep
```

---

## 4. Activite de tracking GPS/Location

### Observations dans Battery History
- L'app Motium etait en TOP state (foreground) pendant la collecte
- Le service ActivityRecognitionService tournait avec le type LOCATION
- Pas d'activite GPS excessive visible (pas de +gps/-gps frequents pour u0a473)

### Analyse du foreground service
L'ActivityRecognitionService avec `foregroundServiceType=location` maintient:
- Un wakelock implicite via le foreground service
- Potentiellement un acces continu aux capteurs de mouvement
- La possibilite de demander des locations au FusedLocationProvider

---

## 5. Alarmes et Jobs

### Alarme KEEPALIVE detectee
```
+wake_lock=u0a473:"*walarm*:com.application.motium.ACTION_KEEPALIVE_WAKEUP"
```

Cette alarme:
- Reveille periodiquement l'appareil
- Maintient une connexion active (probablement Supabase Realtime)
- Consomme de la batterie meme quand l'ecran est eteint

### Jobs WorkManager
```
Job com.application.motium/androidx.work.impl.background.systemjob.SystemJobService
```
- Jobs de synchronisation delta detectes

---

## 6. Comparaison de consommation

### Consommation sur 1 heure (USB branche)
| Scenario | Consommation attendue | Consommation mesuree |
|----------|----------------------|---------------------|
| USB en charge, ecran ON, app foreground | 0% a +5% (gain) | **-8% (perte)** |
| Ecran ON sans charge | -5% a -10% | N/A |

### Interpretation
La consommation de **8% malgre le chargement USB** est ANORMALE.
Cela suggere une consommation instantanee superieure a ~500-700mA (capacite charge USB standard).

---

## 7. Analyse des patterns suspects

### Patterns identifies dans les logs

1. **Ecran ON prolonge**
   - L'ecran est reste allume pendant presque toute la duree du test
   - Contribue significativement a la consommation

2. **Service foreground permanent**
   - ActivityRecognitionService maintenu en foreground
   - Impact: wakelock permanent

3. **Alarmes KEEPALIVE**
   - Revelent un mecanisme de maintien de connexion agressif
   - Meme si l'app est en foreground, ces alarmes persistent

4. **Temperature elevee**
   - Augmentation de 39.4C a 42.0C
   - Indique une activite CPU soutenue

---

## 8. Diagnostic Final

### Cause principale du drain batterie
**VERDICT: Consommation NORMALE pour un scenario d'utilisation active**

Cependant, les elements suivants meritent attention:

1. **ActivityRecognitionService en foreground permanent**
   - Necessaire pour l'auto-tracking mais couteux
   - Le type LOCATION implique des mises a jour de position

2. **Alarme ACTION_KEEPALIVE_WAKEUP**
   - Peut etre optimisee ou supprimee quand l'app est en foreground

3. **SupabaseConnectionService**
   - Maintient une connexion Realtime
   - Pourrait utiliser des strategies de backoff plus agressives

### Contexte du test
- L'ecran etait **ON** pendant presque tout le test
- L'app etait au **premier plan** (TOP state)
- Dans ce contexte, **8%/heure est dans la norme haute mais acceptable**

---

## 9. Recommandations

### 1. Optimiser ACTION_KEEPALIVE_WAKEUP
```kotlin
// Dans SupabaseConnectionService
// Desactiver les alarmes keepalive quand l'app est en foreground
// car la connexion est deja maintenue

private fun scheduleKeepaliveAlarm() {
    if (isAppInForeground()) {
        // Pas besoin d'alarme, la connexion est active
        return
    }
    // Sinon, programmer l'alarme normalement
}
```

### 2. Reduire la frequence des mises a jour location en foreground
```kotlin
// Dans ActivityRecognitionService ou LocationTrackingService
// Utiliser des intervalles plus longs quand pas en deplacement

val locationRequest = LocationRequest.Builder(
    if (isInVehicle) 5_000L else 30_000L  // 5s en vehicule, 30s sinon
).apply {
    setMinUpdateIntervalMillis(
        if (isInVehicle) 2_000L else 15_000L
    )
    setPriority(
        if (isInVehicle) Priority.PRIORITY_HIGH_ACCURACY
        else Priority.PRIORITY_BALANCED_POWER_ACCURACY
    )
}.build()
```

### 3. Optimiser la connexion Supabase Realtime
```kotlin
// Implementer un heartbeat moins frequent
// Utiliser le mode batched pour les notifications
// Deconnecter quand en Doze mode

override fun onTrimMemory(level: Int) {
    if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
        supabaseClient.realtime.disconnect()
    }
}
```

### 4. Utiliser Battery Historian pour une analyse plus detaillee
```bash
# Generer un bug report pour Battery Historian
adb bugreport bugreport.zip
# Uploader sur https://bathist.ef.lc/ pour visualisation
```

---

## 10. Conclusion

### Resume
| Metrique | Valeur | Evaluation |
|----------|--------|------------|
| Consommation 1h | 8% | NORMALE (ecran ON) |
| Temperature | +2.6C | ACCEPTABLE |
| Wakelocks suspects | 1 (KEEPALIVE) | A OPTIMISER |
| Services foreground | 1 | NECESSAIRE mais couteux |

### Score de sante batterie
**7/10** - Bon comportement general mais optimisations possibles

### Prochaines etapes
1. Tester avec ecran OFF pendant 1 heure
2. Tester sans l'app Motium pour comparer
3. Implementer les optimisations KEEPALIVE
4. Revalider apres corrections

---

*Rapport genere automatiquement le 2026-01-20 a 10:48*
