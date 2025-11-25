# 📅 Guide d'Intégration du Planning et Auto-Tracking Intelligent

## 📋 Vue d'ensemble

Ce système permet de gérer les horaires professionnels et d'activer l'auto-tracking uniquement pendant ces créneaux horaires.

## 🗄️ Structure de la Base de Données

### Table `work_schedules`

Stocke les créneaux horaires professionnels par utilisateur et par jour de la semaine.

```sql
work_schedules
├── id (UUID)
├── user_id (UUID) - Référence à auth.users
├── day_of_week (INTEGER) - 1=Lundi, 7=Dimanche (ISO 8601)
├── start_hour (INTEGER) - 0-23
├── start_minute (INTEGER) - 0-59
├── end_hour (INTEGER) - 0-23
├── end_minute (INTEGER) - 0-59
├── is_active (BOOLEAN) - Actif ou non
└── created_at, updated_at
```

### Table `auto_tracking_settings`

Stocke les préférences d'auto-tracking par utilisateur.

```sql
auto_tracking_settings
├── id (UUID)
├── user_id (UUID) - Référence unique
├── tracking_mode (TEXT)
│   ├── 'ALWAYS' - Toujours actif
│   ├── 'WORK_HOURS_ONLY' - Uniquement pendant les horaires pro
│   └── 'DISABLED' - Désactivé
├── min_trip_distance_meters (INTEGER)
├── min_trip_duration_seconds (INTEGER)
└── created_at, updated_at
```

## 🔧 Installation dans Supabase

### Étape 1: Appliquer le SQL

1. Ouvrez votre projet Supabase
2. Allez dans **SQL Editor**
3. Copiez-collez le contenu de `work_schedules.sql`
4. Cliquez sur **Run**

### Étape 2: Vérifier l'installation

```sql
-- Vérifier que les tables existent
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public'
  AND table_name IN ('work_schedules', 'auto_tracking_settings');

-- Vérifier que les fonctions existent
SELECT routine_name
FROM information_schema.routines
WHERE routine_schema = 'public'
  AND routine_name IN ('is_in_work_hours', 'get_today_work_schedules', 'should_autotrack');
```

## 📱 Intégration Android

### Mapping des jours de la semaine

⚠️ **IMPORTANT**: Différence entre Android et PostgreSQL

- **Android Calendar**: SUNDAY=1, MONDAY=2, ..., SATURDAY=7
- **PostgreSQL ISO 8601**: MONDAY=1, TUESDAY=2, ..., SUNDAY=7

**Fonction de conversion:**

```kotlin
// Dans CalendarScreen.kt ou un utilitaire
fun androidDayToIsoDay(androidDay: Int): Int {
    return when (androidDay) {
        Calendar.SUNDAY -> 7
        Calendar.MONDAY -> 1
        Calendar.TUESDAY -> 2
        Calendar.WEDNESDAY -> 3
        Calendar.THURSDAY -> 4
        Calendar.FRIDAY -> 5
        Calendar.SATURDAY -> 6
        else -> 1
    }
}

fun isoDayToAndroidDay(isoDay: Int): Int {
    return when (isoDay) {
        1 -> Calendar.MONDAY
        2 -> Calendar.TUESDAY
        3 -> Calendar.WEDNESDAY
        4 -> Calendar.THURSDAY
        5 -> Calendar.FRIDAY
        6 -> Calendar.SATURDAY
        7 -> Calendar.SUNDAY
        else -> Calendar.MONDAY
    }
}
```

### Structure des données existante

Dans `CalendarScreen.kt`, la structure `TimeSlot` existe déjà:

```kotlin
data class TimeSlot(
    val id: String = java.util.UUID.randomUUID().toString(),
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int
)
```

### Exemple de Repository pour Work Schedules

```kotlin
// À créer: WorkScheduleRepository.kt

data class WorkSchedule(
    val id: String,
    val userId: String,
    val dayOfWeek: Int, // 1-7 (ISO format)
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val isActive: Boolean = true
)

class WorkScheduleRepository(private val context: Context) {

    private val client = SupabaseClient.client
    private val postgres = client.postgrest

    // Charger les horaires d'un utilisateur
    suspend fun getWorkSchedules(userId: String): List<WorkSchedule> {
        return try {
            val response = postgres.from("work_schedules")
                .select {
                    filter {
                        eq("user_id", userId)
                    }
                }.decodeList<WorkScheduleDto>()

            response.map { it.toWorkSchedule() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Sauvegarder un créneau
    suspend fun saveWorkSchedule(schedule: WorkSchedule): Boolean {
        return try {
            val dto = WorkScheduleDto(
                user_id = schedule.userId,
                day_of_week = schedule.dayOfWeek,
                start_hour = schedule.startHour,
                start_minute = schedule.startMinute,
                end_hour = schedule.endHour,
                end_minute = schedule.endMinute,
                is_active = schedule.isActive
            )

            postgres.from("work_schedules").insert(dto)
            true
        } catch (e: Exception) {
            false
        }
    }

    // Supprimer un créneau
    suspend fun deleteWorkSchedule(scheduleId: String): Boolean {
        return try {
            postgres.from("work_schedules")
                .delete {
                    filter {
                        eq("id", scheduleId)
                    }
                }
            true
        } catch (e: Exception) {
            false
        }
    }

    // Vérifier si on est dans les horaires pro maintenant
    suspend fun isInWorkHours(userId: String): Boolean {
        return try {
            val response = postgres.rpc("is_in_work_hours") {
                parameter("p_user_id", userId)
            }
            response.body<Boolean>()
        } catch (e: Exception) {
            false
        }
    }
}
```

### Exemple de Repository pour Auto-Tracking Settings

```kotlin
// À créer: AutoTrackingSettingsRepository.kt

enum class TrackingMode {
    ALWAYS,
    WORK_HOURS_ONLY,
    DISABLED
}

data class AutoTrackingSettings(
    val userId: String,
    val trackingMode: TrackingMode,
    val minTripDistanceMeters: Int = 100,
    val minTripDurationSeconds: Int = 60
)

class AutoTrackingSettingsRepository(private val context: Context) {

    private val client = SupabaseClient.client
    private val postgres = client.postgrest

    // Récupérer les paramètres
    suspend fun getSettings(userId: String): AutoTrackingSettings? {
        return try {
            val response = postgres.from("auto_tracking_settings")
                .select {
                    filter {
                        eq("user_id", userId)
                    }
                }.decodeSingleOrNull<AutoTrackingSettingsDto>()

            response?.toSettings()
        } catch (e: Exception) {
            null
        }
    }

    // Sauvegarder les paramètres
    suspend fun saveSettings(settings: AutoTrackingSettings): Boolean {
        return try {
            val dto = AutoTrackingSettingsDto(
                user_id = settings.userId,
                tracking_mode = settings.trackingMode.name,
                min_trip_distance_meters = settings.minTripDistanceMeters,
                min_trip_duration_seconds = settings.minTripDurationSeconds
            )

            postgres.from("auto_tracking_settings")
                .upsert(dto) // Upsert = insert or update
            true
        } catch (e: Exception) {
            false
        }
    }

    // Vérifier si l'auto-tracking doit être actif maintenant
    suspend fun shouldAutotrack(userId: String): Boolean {
        return try {
            val response = postgres.rpc("should_autotrack") {
                parameter("p_user_id", userId)
            }
            response.body<Boolean>()
        } catch (e: Exception) {
            false
        }
    }
}
```

## 🔄 Synchronisation Planning avec UI

### Modification de `PlanningSection` dans CalendarScreen.kt

```kotlin
@Composable
fun PlanningSection(
    userId: String, // ⚠️ NOUVEAU: Passer le userId
    viewModel: WorkScheduleViewModel = viewModel() // ⚠️ NOUVEAU: Ajouter un ViewModel
) {
    // Charger les données depuis Supabase
    LaunchedEffect(userId) {
        viewModel.loadWorkSchedules(userId)
        viewModel.loadAutoTrackingSettings(userId)
    }

    val schedules by viewModel.schedules.collectAsState()
    val autoTrackingMode by viewModel.autoTrackingMode.collectAsState()

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Auto-tracking toggle card
        AutoTrackingCard(
            trackingMode = autoTrackingMode,
            onModeChanged = { newMode ->
                viewModel.updateTrackingMode(userId, newMode)
            }
        )

        // Professional Hours section
        Text(
            text = "Professional Hours",
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold
            )
        )

        // Day schedule cards (utiliser schedules du viewModel)
        daysOfWeek.forEach { (dayName, dayOfWeek) ->
            val isoDay = androidDayToIsoDay(dayOfWeek)
            val daySchedules = schedules[isoDay] ?: emptyList()

            DayScheduleCard(
                dayName = dayName,
                timeSlots = daySchedules,
                onAddTimeSlot = {
                    viewModel.addWorkSchedule(userId, isoDay, TimeSlot(...))
                },
                onDeleteTimeSlot = { slotId ->
                    viewModel.deleteWorkSchedule(slotId)
                },
                onTimeSlotChanged = { updatedSlot ->
                    viewModel.updateWorkSchedule(userId, isoDay, updatedSlot)
                }
            )
        }
    }
}
```

### Modifier AutoTrackingCard

```kotlin
@Composable
fun AutoTrackingCard(
    trackingMode: TrackingMode,
    onModeChanged: (TrackingMode) -> Unit
) {
    Card(...) {
        Row(...) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Auto-tracking")
                Text(
                    text = when (trackingMode) {
                        TrackingMode.WORK_HOURS_ONLY -> "Only during professional hours"
                        TrackingMode.ALWAYS -> "Always active"
                        TrackingMode.DISABLED -> "Disabled"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            // Bouton à 3 états au lieu d'un simple Switch
            IconButton(onClick = {
                val nextMode = when (trackingMode) {
                    TrackingMode.DISABLED -> TrackingMode.WORK_HOURS_ONLY
                    TrackingMode.WORK_HOURS_ONLY -> TrackingMode.ALWAYS
                    TrackingMode.ALWAYS -> TrackingMode.DISABLED
                }
                onModeChanged(nextMode)
            }) {
                Icon(
                    imageVector = when (trackingMode) {
                        TrackingMode.DISABLED -> Icons.Default.Cancel
                        TrackingMode.WORK_HOURS_ONLY -> Icons.Default.Schedule
                        TrackingMode.ALWAYS -> Icons.Default.CheckCircle
                    },
                    contentDescription = null,
                    tint = when (trackingMode) {
                        TrackingMode.DISABLED -> Color.Gray
                        TrackingMode.WORK_HOURS_ONLY -> MockupGreen
                        TrackingMode.ALWAYS -> Color.Blue
                    }
                )
            }
        }
    }
}
```

## 🔗 Lien avec le bouton Auto-Tracking de la Home Page

### Modifier NewHomeScreen.kt

Actuellement, le bouton auto-tracking dans la home page utilise `TripRepository.isAutoTrackingEnabled()` qui est un simple booléen.

**Option 1: Garder la compatibilité (Recommandé)**

```kotlin
// Dans TripRepository.kt

fun isAutoTrackingEnabled(): Boolean {
    // ⚠️ Maintenir pour rétrocompatibilité
    // Interpréter: true = WORK_HOURS_ONLY ou ALWAYS, false = DISABLED
    return prefs.getBoolean(KEY_AUTO_TRACKING, false)
}

fun getAutoTrackingMode(): String {
    // ⚠️ NOUVEAU: Retourner le mode réel
    return prefs.getString(KEY_AUTO_TRACKING_MODE, "DISABLED") ?: "DISABLED"
}

fun setAutoTrackingMode(mode: String) {
    prefs.edit()
        .putString(KEY_AUTO_TRACKING_MODE, mode)
        .putBoolean(KEY_AUTO_TRACKING, mode != "DISABLED") // Compatibilité
        .apply()
}
```

**Option 2: Service qui vérifie automatiquement**

Créer un `AutoTrackingService` qui:
1. Vérifie périodiquement si on est dans les horaires pro (via `should_autotrack()`)
2. Active/désactive le tracking automatiquement
3. S'exécute en arrière-plan

## 📊 Exemples de Requêtes Utiles

### Obtenir les créneaux d'aujourd'hui

```sql
SELECT * FROM get_today_work_schedules('USER_UUID');
```

### Vérifier si on est dans les horaires pro maintenant

```sql
SELECT is_in_work_hours('USER_UUID');
```

### Vérifier si l'auto-tracking doit être actif

```sql
SELECT should_autotrack('USER_UUID');
```

### Obtenir tous les créneaux de la semaine

```sql
SELECT
    CASE day_of_week
        WHEN 1 THEN 'Monday'
        WHEN 2 THEN 'Tuesday'
        WHEN 3 THEN 'Wednesday'
        WHEN 4 THEN 'Thursday'
        WHEN 5 THEN 'Friday'
        WHEN 6 THEN 'Saturday'
        WHEN 7 THEN 'Sunday'
    END as day,
    start_hour || ':' || LPAD(start_minute::TEXT, 2, '0') as start_time,
    end_hour || ':' || LPAD(end_minute::TEXT, 2, '0') as end_time,
    is_active
FROM work_schedules
WHERE user_id = 'USER_UUID'
ORDER BY day_of_week, start_hour, start_minute;
```

## ✅ Checklist d'Implémentation

- [ ] Appliquer `work_schedules.sql` dans Supabase
- [ ] Créer `WorkScheduleRepository.kt`
- [ ] Créer `AutoTrackingSettingsRepository.kt`
- [ ] Créer `WorkScheduleViewModel.kt`
- [ ] Modifier `PlanningSection()` pour utiliser le ViewModel
- [ ] Ajouter fonctions de conversion Android ↔ ISO pour les jours
- [ ] Modifier `AutoTrackingCard` pour supporter 3 modes
- [ ] Mettre à jour `TripRepository` pour stocker le mode d'auto-tracking
- [ ] Optionnel: Créer un `AutoTrackingService` pour vérification automatique
- [ ] Tester la synchronisation Planning ↔ Supabase
- [ ] Tester l'activation/désactivation auto selon les horaires

## 🎯 Résultat Attendu

1. **Section Planning fonctionnelle**
   - Créneaux horaires persistés dans Supabase
   - Modifications synchronisées en temps réel
   - UI identique à la maquette existante

2. **Auto-tracking intelligent**
   - Mode "Horaires Pro" = actif uniquement pendant les créneaux définis
   - Mode "Toujours" = actif 24/7
   - Mode "Désactivé" = complètement off

3. **Lien avec Home Page**
   - Bouton auto-tracking affiche le mode actuel
   - Changement de mode synchronisé entre Planning et Home

## 🐛 Troubleshooting

**Problème: Les créneaux ne se sauvegardent pas**
- Vérifier que RLS est correctement configuré
- Vérifier que `auth.uid()` retourne bien l'UUID de l'utilisateur
- Vérifier les logs Supabase dans l'interface

**Problème: Conversion jour de la semaine incorrecte**
- S'assurer d'utiliser `androidDayToIsoDay()` et `isoDayToAndroidDay()`
- Vérifier que Calendar.MONDAY = 2 (Android) → 1 (ISO)

**Problème: Auto-tracking ne s'active pas**
- Vérifier que `should_autotrack()` retourne `true`
- Vérifier que l'heure système correspond aux créneaux définis
- Tester manuellement avec `SELECT is_in_work_hours('USER_UUID', NOW());`
