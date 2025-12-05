Crée une nouvelle feature pour Motium en suivant l'architecture MVVM.

**Feature à créer** : $ARGUMENTS

## Étapes à suivre :

1. **Analyser la demande** et identifier :
   - Le nom de la feature (ex: `trip`, `profile`, `settings`)
   - Les entités/modèles nécessaires
   - Les écrans UI requis

2. **Créer les fichiers dans cet ordre** :

   ### Domain Layer
   - `domain/model/{Feature}.kt` - Entité domain
   - `domain/repository/{Feature}Repository.kt` - Interface repository

   ### Data Layer
   - `data/local/entity/{Feature}Entity.kt` - Entité Room (si persistance locale)
   - `data/local/dao/{Feature}Dao.kt` - DAO Room
   - `data/remote/dto/{Feature}Dto.kt` - DTO Supabase (si sync cloud)
   - `data/repository/{Feature}RepositoryImpl.kt` - Implémentation

   ### Presentation Layer
   - `presentation/viewmodel/{Feature}ViewModel.kt` - ViewModel avec StateFlow
   - `presentation/ui/screens/{Feature}Screen.kt` - Écran principal
   - `presentation/ui/components/{Feature}*.kt` - Composants UI

3. **Patterns à respecter** :
   - StateFlow pour l'état UI dans ViewModel
   - Sealed class pour les UI states (Loading, Success, Error)
   - Modifier comme premier param optionnel dans les Composables
   - Repository retourne Flow pour les données réactives

4. **Ajouter la navigation** dans le NavGraph existant

5. **Créer les tests unitaires** pour le ViewModel

## Template ViewModel :
```kotlin
class {Feature}ViewModel(
    private val repository: {Feature}Repository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow({Feature}UiState())
    val uiState: StateFlow<{Feature}UiState> = _uiState.asStateFlow()
    
    init {
        load{Feature}s()
    }
    
    private fun load{Feature}s() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            repository.get{Feature}s()
                .catch { e -> _uiState.update { it.copy(error = e.message, isLoading = false) } }
                .collect { items -> _uiState.update { it.copy(items = items, isLoading = false) } }
        }
    }
}

data class {Feature}UiState(
    val items: List<{Feature}> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)
```
