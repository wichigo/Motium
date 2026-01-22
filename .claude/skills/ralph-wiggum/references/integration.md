# Intégration Inter-Skills - Ralph Wiggum

Guide technique pour l'intégration de Ralph avec les autres skills du workflow projet.

## Table des Matières
1. Architecture de Communication
2. Contrats d'Interface
3. Flows de Données
4. Gestion des Erreurs
5. Exemples Complets

---

## 1. Architecture de Communication

### Principe
Les skills communiquent via des **fichiers JSON partagés** dans `.claude/`. Chaque skill lit/écrit dans des fichiers spécifiques, créant un système de message-passing asynchrone.

```
.claude/
├── PROJECT_STATE.json      # Orchestrator (write) ← Ralph (read)
├── VALIDATION_REPORT.json  # Ralph (write) ← Orchestrator (read)
├── ralph_config.json       # User (write) ← Ralph (read)
└── specs/
    └── PROJECT_SPEC.md     # Chef-de-projet (write) ← All (read)
```

### Responsabilités

| Skill | Écrit | Lit |
|-------|-------|-----|
| chef-de-projet | PROJECT_SPEC.md | - |
| orchestrator | PROJECT_STATE.json | PROJECT_SPEC.md, VALIDATION_REPORT.json |
| ralph-wiggum | VALIDATION_REPORT.json | PROJECT_STATE.json, PROJECT_SPEC.md |

---

## 2. Contrats d'Interface

### Interface Ralph → Orchestrator

Quand l'orchestrator appelle Ralph pour valider une feature :

**Input (implicite via fichiers + contexte):**
```json
{
  "action": "validate_feature",
  "feature_id": 3,
  "feature_name": "Brush engine",
  "files": [
    "app/src/main/kotlin/com/app/brush/BrushEngine.kt",
    "app/src/main/kotlin/com/app/brush/BrushConfig.kt"
  ],
  "context": {
    "project_type": "android_kotlin",
    "strict_mode": false
  }
}
```

**Output (dans VALIDATION_REPORT.json):**
```json
{
  "feature_validations": [
    {
      "feature_id": 3,
      "timestamp": "2026-01-12T10:30:00Z",
      "score": 72,
      "passed": false,
      "personas": {
        "ralph": {"score": 75, "issues": 2},
        "expert": {"score": 68, "issues": 4},
        "avocat": {"score": 73, "issues": 2}
      },
      "issues": [
        {
          "id": "issue_001",
          "file": "BrushEngine.kt",
          "line": 45,
          "type": "potential_crash",
          "severity": "blocker",
          "persona": "expert",
          "message": "Division par zéro possible si brushSize == 0",
          "suggestion": "Ajouter: if (brushSize <= 0) return ou throw"
        },
        {
          "id": "issue_002",
          "file": "BrushEngine.kt",
          "line": 23,
          "type": "magic_number",
          "severity": "warning",
          "persona": "ralph",
          "message": "0.5f utilisé directement sans explication",
          "suggestion": "Extraire en constante: DEFAULT_BRUSH_FLOW = 0.5f"
        }
      ],
      "suggestions": [
        "Ajouter validation des inputs en début de fonction",
        "Documenter les valeurs par défaut"
      ]
    }
  ]
}
```

### Interface Orchestrator → Ralph

L'orchestrator signale à Ralph qu'une feature est prête à valider en mettant à jour PROJECT_STATE.json :

```json
{
  "features": [
    {
      "id": 3,
      "name": "Brush engine",
      "status": "pending_validation",  // ← Signal pour Ralph
      "files_modified": [
        "app/src/main/kotlin/com/app/brush/BrushEngine.kt"
      ],
      "implemented_at": "2026-01-12T10:25:00Z"
    }
  ]
}
```

### Interface Chef-de-Projet → Ralph

Ralph lit PROJECT_SPEC.md pour le cross-check specs/code :

```markdown
## Feature #3: Brush Engine

### Acceptance Criteria
- [ ] Brush size: 1-500px
- [ ] Opacity: 0-100%
- [ ] Flow: 0-100%
- [ ] Pressure sensitivity support

### Technical Notes
- Use OpenGL ES 3.0 for rendering
- Max 60fps target
```

Ralph vérifie que le code respecte ces critères.

---

## 3. Flows de Données

### Flow Complet : Nouvelle Feature

```
1. ORCHESTRATOR commence feature #3
   └─► Écrit dans PROJECT_STATE.json: status = "in_progress"

2. ORCHESTRATOR implémente le code
   └─► Crée/modifie BrushEngine.kt, BrushConfig.kt

3. ORCHESTRATOR signale fin d'implémentation
   └─► Écrit dans PROJECT_STATE.json: status = "pending_validation"

4. RALPH détecte le changement (ou est appelé explicitement)
   └─► Lit PROJECT_STATE.json
   └─► Lit les fichiers modifiés
   └─► Lit PROJECT_SPEC.md pour contexte

5. RALPH exécute validation
   └─► Applique 3 personas
   └─► Calcule score
   └─► Écrit dans VALIDATION_REPORT.json

6. ORCHESTRATOR lit le résultat
   └─► Si passed=true: status = "validated", passe à feature suivante
   └─► Si passed=false: applique corrections, retour à étape 3
```

### Flow : Audit Projet Global

```
1. USER demande: "ralph project"

2. RALPH scan le projet
   └─► Liste tous les fichiers Kotlin/Java
   └─► Groupe par module (package)

3. RALPH charge le contexte
   └─► Lit PROJECT_STATE.json (features complétées)
   └─► Lit PROJECT_SPEC.md (specs attendues)
   └─► Lit historique VALIDATION_REPORT.json

4. RALPH valide chaque module
   └─► Applique personas par fichier
   └─► Agrège par module
   └─► Détecte patterns récurrents

5. RALPH cross-check specs
   └─► Compare features spec vs implémentées
   └─► Vérifie critères d'acceptance

6. RALPH génère rapport global
   └─► Écrit rapport complet dans VALIDATION_REPORT.json
   └─► Affiche rapport formaté à l'utilisateur
```

---

## 4. Gestion des Erreurs

### Fichier State Manquant

```kotlin
// Si PROJECT_STATE.json n'existe pas
if (!projectStateFile.exists()) {
    // Mode standalone - pas d'intégration orchestrator
    logger.info("Running in standalone mode (no orchestrator)")
    validateStandalone(files)
}
```

### Incohérence de State

```kotlin
// Si feature marquée "validated" mais score < threshold
if (feature.status == "validated" && feature.ralphScore < threshold) {
    logger.warn("Inconsistency: feature ${feature.id} marked validated but score ${feature.ralphScore} < $threshold")
    // Proposer re-validation
}
```

### Timeout / Fichiers Volumineux

```kotlin
// Pour fichiers > 1000 lignes
if (file.lineCount > 1000) {
    // Validation par chunks ou sampling
    validateLargeFile(file, sampleRate = 0.3)  // 30% des fonctions
}
```

---

## 5. Exemples Complets

### Exemple 1: Orchestrator appelle Ralph

```
CONTEXTE: Orchestrator vient d'implémenter la feature "Layer System"

── ORCHESTRATOR ──────────────────────────────────────────────────────
Met à jour PROJECT_STATE.json:
{
  "current_phase": 2,
  "features": [{
    "id": 2,
    "name": "Layer system",
    "status": "pending_validation",
    "files_modified": ["LayerManager.kt", "Layer.kt", "LayerRepository.kt"]
  }]
}

Appelle: "Ralph, valide la feature #2"
──────────────────────────────────────────────────────────────────────

── RALPH ─────────────────────────────────────────────────────────────
1. Lit PROJECT_STATE.json → trouve feature #2 pending
2. Lit les 3 fichiers modifiés
3. Lit PROJECT_SPEC.md → trouve specs Layer System

4. Exécute validation:
   - Ralph (naïf): "LayerManager c'est quoi? Y'a pas de commentaires!"
   - Expert: "addLayer() ne vérifie pas la limite max de layers"
   - Avocat: "Et si on delete le layer actif? NPE possible"

5. Calcule score: 74/100 → passed = false

6. Écrit VALIDATION_REPORT.json:
{
  "feature_validations": [{
    "feature_id": 2,
    "score": 74,
    "passed": false,
    "issues": [
      {"type": "missing_docs", "severity": "warning"},
      {"type": "no_limit_check", "severity": "major"},
      {"type": "potential_npe", "severity": "blocker"}
    ]
  }]
}

Retourne: "❌ Score 74/100 - 3 issues à corriger"
──────────────────────────────────────────────────────────────────────

── ORCHESTRATOR ──────────────────────────────────────────────────────
Lit VALIDATION_REPORT.json → passed = false
Applique les corrections suggérées
Relance: "Ralph, re-valide feature #2"
──────────────────────────────────────────────────────────────────────

── RALPH (2ème passe) ────────────────────────────────────────────────
Score: 86/100 → passed = true
Retourne: "✅ Score 86/100 - Feature validée"
──────────────────────────────────────────────────────────────────────

── ORCHESTRATOR ──────────────────────────────────────────────────────
Met à jour PROJECT_STATE.json:
{
  "features": [{
    "id": 2,
    "status": "validated",
    "ralph_score": 86
  }]
}

Passe à la feature #3
──────────────────────────────────────────────────────────────────────
```

### Exemple 2: Cross-Check Specs

```
SPEC (PROJECT_SPEC.md):
"""
## Feature #5: Export
- Formats: PNG, JPEG, PSD
- Resolution: up to 4K (3840x2160)
- Compression quality: 1-100
"""

CODE (ExportManager.kt):
"""
fun export(format: ExportFormat, quality: Int) {
    // format: PNG, JPEG only
    // quality: 0-100
    // max resolution: 1920x1080
}
"""

RALPH CROSS-CHECK:
⚠️ INCOHÉRENCES SPECS/CODE:

1. FORMAT MANQUANT
   Spec: PNG, JPEG, PSD
   Code: PNG, JPEG (PSD missing)
   → Action: Implémenter export PSD ou mettre à jour specs

2. RÉSOLUTION LIMITÉE
   Spec: jusqu'à 4K (3840x2160)
   Code: max 1920x1080
   → Action: Augmenter limite ou documenter la limitation

3. RANGE QUALITY DIFFÉRENT
   Spec: 1-100
   Code: 0-100
   → Action: Clarifier si 0 est valide
```

---

## Configuration Avancée

### ralph_config.json Complet

```json
{
  "version": "1.0",
  
  "thresholds": {
    "feature_pass": 80,
    "module_warning": 70,
    "project_release": 85,
    "blocker_fails": true
  },
  
  "retries": {
    "max_attempts": 3,
    "backoff_seconds": 0
  },
  
  "scanning": {
    "include_patterns": ["**/*.kt", "**/*.java", "**/*.ts"],
    "exclude_patterns": [
      "**/test/**",
      "**/generated/**",
      "**/build/**"
    ],
    "max_file_lines": 1000,
    "sample_large_files": true
  },
  
  "personas": {
    "ralph": {"enabled": true, "weight": 0.25},
    "expert": {"enabled": true, "weight": 0.45},
    "avocat": {"enabled": true, "weight": 0.30}
  },
  
  "integration": {
    "orchestrator_state_file": ".claude/PROJECT_STATE.json",
    "spec_file": ".claude/specs/PROJECT_SPEC.md",
    "output_file": ".claude/VALIDATION_REPORT.json",
    "auto_watch": false
  },
  
  "reporting": {
    "format": "detailed",
    "include_suggestions": true,
    "include_code_snippets": true,
    "max_issues_per_file": 10
  }
}
```
