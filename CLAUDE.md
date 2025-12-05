# Project: Motium

## Description
Application Android de suivi de mobilité et trajets avec OCR, cartographie et synchronisation cloud.

## Tech Stack
- **Language**: Kotlin
- **Min SDK**: 31 (Android 12)
- **Target SDK**: 34 (Android 14)
- **Compile SDK**: 36
- **UI**: Jetpack Compose + Material 3
- **Architecture**: MVVM (Hilt prévu mais désactivé)
- **Database locale**: Room + KSP
- **Backend**: Supabase (Postgrest, Auth, Realtime, Storage)
- **Auth**: Supabase Auth + Google Sign-In
- **Maps**: OSMDroid (OpenStreetMap)
- **Location**: Google Play Services Location
- **OCR**: ML Kit Text Recognition (on-device)
- **Images**: Coil
- **Networking**: Retrofit + Moshi + OkHttp
- **Async**: Kotlin Coroutines + Flow
- **Background**: WorkManager
- **Security**: EncryptedSharedPreferences
- **PDF**: iTextPDF
- **Permissions**: Accompanist Permissions
- **Date/Time**: kotlinx-datetime
- **Serialization**: kotlinx-serialization-json
- **Testing**: JUnit, MockK, Mockito, Turbine, Robolectric, Espresso

## MCP Tools

### Context7 (AUTO-INVOKE - IMPORTANT)
Always use context7 when I need code generation, setup or configuration steps, 
or library/API documentation. Automatically use the Context7 MCP tools 
(resolve-library-id, get-library-docs) without me having to explicitly ask.

**Priority libraries for Context7:**
- `/supabase/supabase` - Backend, Auth, Realtime, Storage
- `/androidx/compose` - Jetpack Compose UI
- `/androidx/room` - Database locale
- `/square/retrofit` - Networking
- `/square/moshi` - JSON parsing
- `/coil-kt/coil` - Image loading
- `/google/mlkit` - OCR Text Recognition
- `/osmdroid/osmdroid` - OpenStreetMap maps

## Package Structure
```
com.application.motium/
├── data/
│   ├── local/              # Room database, DAOs, entities
│   ├── remote/             # Supabase services, API, DTOs
│   └── repository/         # Repository implementations
├── domain/
│   ├── model/              # Domain entities
│   ├── repository/         # Repository interfaces
│   └── usecase/            # Business logic
├── presentation/
│   ├── ui/
│   │   ├── components/     # Composables réutilisables
│   │   ├── screens/        # Écrans (Screen composables)
│   │   └── theme/          # Material 3 theme
│   ├── viewmodel/          # ViewModels
│   └── navigation/         # Navigation graphs
├── service/                # Services (Location, Sync)
├── worker/                 # WorkManager workers
├── util/                   # Extensions, helpers
└── MotiumApp.kt            # Application class
```

## Commands
```bash
# Build
./gradlew assembleDebug
./gradlew assembleRelease

# Tests
./gradlew test                      # Unit tests
./gradlew testDebugUnitTest         # Debug unit tests
./gradlew connectedAndroidTest      # Instrumented tests

# Lint & Quality
./gradlew lint
./gradlew lintDebug

# Install
./gradlew installDebug

# Clean
./gradlew clean

# Room schema export (si configuré)
./gradlew kspDebugKotlin
```

## Code Conventions

### Kotlin Style
- Kotlin official conventions
- 4 espaces d'indentation
- Max 120 caractères par ligne
- Pas de wildcard imports
- Trailing commas pour multi-lignes

### Naming
| Type | Convention | Exemple |
|------|------------|---------|
| Package | lowercase | `com.application.motium.data` |
| Class | PascalCase | `TripRepository` |
| Composable | PascalCase | `TripCard`, `HomeScreen` |
| Function | camelCase | `getTrips()`, `syncData()` |
| Property | camelCase | `isLoading`, `tripList` |
| Constant | SCREAMING_SNAKE | `MAX_SYNC_INTERVAL` |
| ViewModel | Suffix `ViewModel` | `HomeViewModel` |
| UseCase | Suffix `UseCase` | `GetTripsUseCase` |
| Repository | Suffix `Repository` | `TripRepository` |
| DAO | Suffix `Dao` | `TripDao` |
| Entity (Room) | Suffix `Entity` | `TripEntity` |
| DTO | Suffix `Dto` | `TripDto` |

### Compose Guidelines
```kotlin
// ✅ Bon - Stateless, modifier en premier param optionnel
@Composable
fun TripCard(
    trip: Trip,
    onTripClick: (Trip) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        // ...
    }
}

// ✅ Preview avec showBackground
@Preview(showBackground = true)
@Composable
private fun TripCardPreview() {
    MotiumTheme {
        TripCard(trip = previewTrip, onTripClick = {})
    }
}
```

### Null Safety
```kotlin
// ❌ Jamais
val name = user!!.name

// ✅ Préférer
val name = user?.name ?: "Unknown"
user?.let { processUser(it) }
```

### Coroutines & Flow
```kotlin
// ViewModel - utiliser viewModelScope
fun loadTrips() {
    viewModelScope.launch {
        tripRepository.getTrips()
            .catch { e -> _uiState.update { it.copy(error = e.message) } }
            .collect { trips -> _uiState.update { it.copy(trips = trips) } }
    }
}

// Repository - utiliser Dispatchers.IO pour I/O
suspend fun syncTrips() = withContext(Dispatchers.IO) {
    // ...
}
```

### Room Database
```kotlin
// Entity
@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey val id: String,
    val startTime: Long,
    val endTime: Long?,
    @ColumnInfo(name = "is_synced") val isSynced: Boolean = false,
)

// DAO - Flow pour les queries réactives
@Dao
interface TripDao {
    @Query("SELECT * FROM trips ORDER BY startTime DESC")
    fun getAllTrips(): Flow<List<TripEntity>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrip(trip: TripEntity)
}
```

### Supabase
```kotlin
// Utiliser les clients depuis BuildConfig
val supabase = createSupabaseClient(
    supabaseUrl = BuildConfig.SUPABASE_URL,
    supabaseKey = BuildConfig.SUPABASE_ANON_KEY
) {
    install(Auth)
    install(Postgrest)
    install(Realtime)
    install(Storage)
}

// Queries Postgrest
suspend fun getTrips(): List<TripDto> {
    return supabase.postgrest["trips"]
        .select()
        .decodeList()
}
```

## Testing

### Naming Convention
```kotlin
// Format: should_expectedBehavior_when_condition
@Test
fun should_returnTrips_when_databaseHasData() = runTest {
    // Given
    val trips = listOf(createTestTrip())
    coEvery { tripDao.getAllTrips() } returns flowOf(trips)
    
    // When
    val result = repository.getTrips().first()
    
    // Then
    assertEquals(trips, result)
}
```

### Test Dependencies
- **Unit tests**: JUnit, MockK, Turbine (Flow testing), Robolectric
- **Instrumented**: Espresso, MockK-Android, Compose UI Testing

## Git Workflow

### Commit Format
```
type(scope): description

Types: feat, fix, docs, style, refactor, test, chore, perf
```

**Exemples:**
```
feat(trip): add trip recording with location tracking
fix(ocr): handle camera permission denial gracefully
refactor(auth): migrate to Supabase Auth
test(repository): add unit tests for TripRepository
```

### Branches
- `main` - Production stable
- `develop` - Développement
- `feature/*` - Nouvelles features
- `bugfix/*` - Corrections de bugs
- `hotfix/*` - Fixes urgents

## Security

### Clés API
⚠️ **ATTENTION** : Les clés Supabase sont actuellement en dur dans `build.gradle.kts`.

**TODO** : Migrer vers `local.properties` :
```properties
# local.properties (NE PAS COMMIT)
SUPABASE_URL=https://xxx.supabase.co
SUPABASE_ANON_KEY=eyJ...
```

```kotlin
// build.gradle.kts
val localProperties = Properties().apply {
    load(rootProject.file("local.properties").inputStream())
}
buildConfigField("String", "SUPABASE_URL", "\"${localProperties["SUPABASE_URL"]}\"")
```

### Données sensibles
- Utiliser `EncryptedSharedPreferences` pour les tokens
- Ne jamais logger les tokens/clés
- Valider les inputs utilisateur

## Permissions Android
```xml
<!-- Déjà utilisées ou à prévoir -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

## Known Issues & TODOs
- [ ] Hilt désactivé - activer quand l'architecture sera stabilisée
- [ ] Clés Supabase en dur dans build.gradle.kts (à sécuriser)
- [ ] Configurer ProGuard pour release (`isMinifyEnabled = false`)

## Important Files
| Fichier | Description |
|---------|-------------|
| `app/build.gradle.kts` | Dépendances et config |
| `gradle/libs.versions.toml` | Version catalog |
| `local.properties` | Clés API (gitignored) |
| `app/src/main/AndroidManifest.xml` | Permissions, components |

## Do Not Modify Without Review
- Database migrations Room (créer une nouvelle migration)
- `BuildConfig` fields (impact sur tout le projet)
- Schemas Supabase (coordonner avec backend)

## Useful Resources
- [Supabase Kotlin Docs](https://supabase.com/docs/reference/kotlin/introduction)
- [Jetpack Compose](https://developer.android.com/jetpack/compose)
- [Room Database](https://developer.android.com/training/data-storage/room)
- [OSMDroid Wiki](https://github.com/osmdroid/osmdroid/wiki)
- [ML Kit Text Recognition](https://developers.google.com/ml-kit/vision/text-recognition/android)
- [Accompanist Permissions](https://google.github.io/accompanist/permissions/)
