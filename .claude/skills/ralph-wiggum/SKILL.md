---
name: ralph-wiggum
description: |
  Système complet de boucle itérative autonome ET de validation multi-persona pour le développement.
  Combine la technique "Ralph Loop" (itération jusqu'à complétion) avec la validation triple-critique.
  
  Triggers Boucle (Ralph Loop):
  - "ralph loop [tâche]" / "ralph-loop [tâche]" / "boucle ralph [tâche]"
  - "itère jusqu'à [condition]" / "continue jusqu'à [critère]"
  - "[tâche] --loop" / "[tâche] --ralph" / "[tâche] --max-iterations N"
  - "implémente [feature] en boucle" / "fixe [bug] jusqu'à résolution"
  
  Triggers Validation:
  - "valide ta réponse" / "vérifie ce que tu viens de dire"
  - "ralph wiggum" / "self-check" / "auto-critique"
  - "es-tu sûr de ta réponse?" / "peux-tu vérifier?"
  - "améliore ta réponse" / "simplifie" / "clarifie"
  
  Triggers Projet:
  - "ralph project" / "audit projet" / "validation globale"
  - Automatiquement par orchestrator après chaque feature
  
  Commandes Slash (NOUVEAU):
  - "/ralph-wiggum-one-shot" → Feature complète sans régression
  - "/ralph-wiggum-debug" → Bug fix ciblé sans régression
  - "/ralph-wiggum-task" → Tâche générale non-code
  
  Fonctionnalités: Boucle autonome avec stop-condition, Multi-persona critique (naïf, expert, avocat du diable), scoring 100/100 requis, 15 itérations max, temps illimité (qualité > rapidité), auto-correction itérative, détection de blocages, completion promises, intégration orchestrator/chef-de-projet, snapshot/rollback, historique des exécutions.
---

# Ralph Wiggum - Système d'Auto-Validation Avancé

Skill d'auto-critique et d'amélioration itérative des réponses inspiré de la technique "Ralph Wiggum Plugin".

## Table des Matières

1. [Concept Original](#concept-original)
2. [Architecture](#architecture-avancée)
3. [Système de Boucle Ralph](#système-de-boucle-ralph-ralph-loop)
4. [Validation Multi-Persona](#les-trois-personas)
5. [Mode Projet](#mode-projet-audit-global)
6. **[Modes Étendus (NOUVEAU)](#modes-étendus)**
   - [One-Shot (Feature)](#mode-one-shot-feature)
   - [Debug (Bug Fix)](#mode-debug-bug-fix)
   - [Task (Tâche Générale)](#mode-task-tâche-générale)
7. [Gestion des Échecs et Rollback](#gestion-des-échecs-et-rollback)
8. [Intégration Inter-Skills](#intégration-inter-skills)
9. [Configuration](#configuration)

---

## Modes Étendus

### Vue d'Ensemble

Trois nouveaux modes combinant Ralph Wiggum avec **Chef-de-Projet** (interview) et **Orchestrator** (agents spécialisés) :

| Commande | Usage | Orchestrator | Non-Régression |
|----------|-------|--------------|----------------|
| `/ralph-wiggum-one-shot` | Feature complète | ✅ Toujours | Audit complet avant/après + tests |
| `/ralph-wiggum-debug` | Fix bug ciblé | ✅ Toujours | Test reproduction + tests existants |
| `/ralph-wiggum-task` | Tâche non-code | ❌ Non | Validation 3 personas + checklist |

### Architecture Commune

```
┌─────────────────────────────────────────────────────────────────┐
│                    PHASE 0: INTERVIEW (Chef-de-Projet)          │
│  Questions hybrides (essentielles + optionnelles si complexe)  │
│  → Définit les critères de succès vérifiables                  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    PHASE 1: SNAPSHOT AVANT                      │
│  Score Ralph baseline │ Tests existants │ Git tag              │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    PHASE 2: EXÉCUTION                           │
│  Orchestrator (agents) + Boucle Ralph (max 10 itérations)      │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    PHASE 3: VALIDATION NON-RÉGRESSION           │
│  Audit après │ Comparaison tests │ Checklist critères          │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    PHASE 4: RAPPORT & HISTORIQUE                │
│  .claude/ralph_history/ + VALIDATION_REPORT.json               │
└─────────────────────────────────────────────────────────────────┘
```

---

### Mode One-Shot (Feature)

**Trigger** : `/ralph-wiggum-one-shot [description optionnelle]`

**Cas d'usage** : Implémenter une nouvelle fonctionnalité sans créer de régression.

#### Interview (Questions Hybrides)

**Essentielles (toujours posées)** :
1. "Quelle feature veux-tu implémenter ?"
2. "Quels fichiers/modules sont concernés ?"
3. "Quels sont les critères de succès ?"
4. "Y a-t-il des contraintes particulières ?"

**Optionnelles (si complexité détectée)** :
- "Quelles couches sont impactées ? (DB/API/UI)"
- "Intégrations avec modules existants ?"
- "Tests spécifiques à ajouter ?"
- "Edge cases à considérer ?"

#### Workflow

```
1. INTERVIEW → FEATURE_SPEC.json
2. SNAPSHOT AVANT:
   ├── Audit Ralph complet (score baseline par module)
   ├── Exécuter tous les tests existants
   └── Git tag: "ralph-oneshot-before-{feature}"
   
3. ORCHESTRATION (TOUJOURS):
   ├── backend-analyzer → frontend-analyzer
   ├── backend-converter → frontend-converter
   ├── test-converter → security-converter
   └── syntax-validator + Ralph validation après chaque agent

4. VALIDATION NON-RÉGRESSION:
   ├── Audit Ralph après (aucun module avec score inférieur)
   ├── Tous les tests avant passent toujours
   └── Nouveaux tests passent

5. RAPPORT → .claude/ralph_history/oneshot_{date}_{feature}.md
```

#### Critères de Succès

```json
{
  "feature_complete": true,
  "all_criteria_met": true,
  "no_regression": {
    "ralph_scores": "aucun module ↓",
    "tests": "100% des tests avant passent",
    "new_tests": "100% passent"
  },
  "ralph_score_new_files": "= 100 (perfection requise)"
}
```

---

### Mode Debug (Bug Fix)

**Trigger** : `/ralph-wiggum-debug [description du bug]`

**Cas d'usage** : Fixer un bug de manière ciblée sans créer de régression.

#### Interview (Questions Hybrides)

**Essentielles (toujours posées)** :
1. "Quel est le bug / comportement actuel ?"
2. "Comment le reproduire ? (étapes)"
3. "Quel est le comportement attendu ?"
4. "Fichier(s)/module(s) suspect(s) ?"

**Optionnelles (si bug complexe)** :
- "Depuis quand ce bug existe ?"
- "Version/commit où ça marchait ?"
- "Messages d'erreur / logs ?"
- "Conditions particulières ?"

#### Workflow

```
1. INTERVIEW → BUG_SPEC.json
2. SNAPSHOT & REPRODUCTION:
   ├── Exécuter tests existants (capturer l'état)
   ├── Créer test de reproduction (DOIT FAIL)
   └── Git tag: "ralph-debug-before-{bug-id}"

3. ORCHESTRATION (scope ciblé):
   ├── Analyzer du module bugué uniquement
   ├── Converter avec FIX MINIMAL
   ├── test-converter (test de régression)
   └── syntax-validator

4. VALIDATION DEBUG:
   ├── Test de reproduction PASSE maintenant
   ├── TOUS les tests existants passent toujours
   └── Ralph score module modifié >= 80

5. RAPPORT → .claude/ralph_history/debug_{date}_{bug-id}.md
```

#### Règles du Fix

- ✅ Fix minimal
- ✅ Backward compatible
- ❌ Pas de refactoring
- ❌ Pas de changements non liés

---

### Mode Task (Tâche Générale)

**Trigger** : `/ralph-wiggum-task [description]`

**Cas d'usage** : Toute tâche qui n'est pas de l'implémentation de feature ou du debug.

**Exemples** :
- Documentation (README, guides, specs)
- Analyse/audit (revue code, performance)
- Génération (prompts, templates, configs)
- Recherche (investigation, comparaison)
- Rédaction (articles, rapports)

#### Interview (Questions Hybrides)

**Essentielles (toujours posées)** :
1. "Quelle tâche veux-tu accomplir ?"
2. "Quel est le livrable attendu ?"
3. "Quels sont les critères de succès ?"
4. "Qui est la cible / l'audience ?"

**Optionnelles (selon le type)** :
- "Contraintes de format ?"
- "Références / exemples à suivre ?"
- "Niveau de détail attendu ?"
- "Deadline ou priorité ?"

#### Workflow

```
1. INTERVIEW → TASK_SPEC.json
2. EXÉCUTION DIRECTE (pas d'orchestrator):
   WHILE (score < 80 AND iteration < 10):
       ├── Produire/améliorer le livrable
       ├── Validation 3 personas (clarté, précision, complétude)
       └── Vérifier checklist critères

3. VALIDATION QUALITÉ:
   ├── 🧒 Ralph: Clarté >= 75
   ├── 🎓 Expert: Précision >= 80
   └── ⚖️ Avocat: Complétude >= 75

4. RAPPORT → .claude/ralph_history/task_{date}_{name}.md
```

---

## Gestion des Échecs et Rollback

### Workflow d'Échec

```
WHILE (NOT success AND iteration < 15):
    Tenter → Valider → Corriger si échec

IF iteration >= 15:
    ┌─────────────────────────────────────────┐
    │  🛑 PAUSE - INTERVENTION HUMAINE        │
    ├─────────────────────────────────────────┤
    │  Afficher:                              │
    │  • Problème identifié                   │
    │  • 15 tentatives effectuées             │
    │  • Approches essayées                   │
    │  • Suggestions pour débloquer           │
    │                                         │
    │  Options:                               │
    │  [1] Fournir indications → reprendre    │
    │  [2] Modifier critères → reprendre      │
    │  [3] Rollback → abandonner              │
    └─────────────────────────────────────────┘

SI toujours bloqué après intervention:
    → Option ROLLBACK disponible
```

### Mécanisme de Rollback

```
1. IDENTIFIER le snapshot avant
   └── Git tag: "ralph-{mode}-before-{id}"

2. LISTER les changements à annuler
   └── Diff snapshot ↔ état actuel

3. CONFIRMER avec l'utilisateur
   └── "Fichiers à restaurer: [liste]. Confirmer ? [y/n]"

4. EXÉCUTER
   └── git checkout {tag} -- [fichiers]

5. NETTOYER
   └── Supprimer fichiers créés si applicable

6. RAPPORT D'ÉCHEC
   └── .claude/ralph_history/failed_{date}_{id}.md
```

### Points de Rollback

```json
// .claude/ROLLBACK_POINTS.json
{
  "points": [
    {
      "id": "oneshot-2026-01-21-favorites",
      "mode": "one-shot",
      "git_tag": "ralph-oneshot-before-favorites",
      "timestamp": "2026-01-21T10:30:00Z",
      "files_snapshot": [
        {"path": "User.kt", "hash": "abc123"}
      ],
      "status": "active"
    }
  ]
}
```

---

## Historique et Rapports

### Structure des Fichiers

```
.claude/
├── ralph_history/
│   ├── oneshot_2026-01-21_favorites.md
│   ├── debug_2026-01-21_button-click.md
│   ├── task_2026-01-21_readme-update.md
│   └── failed_2026-01-21_complex-feature.md
├── VALIDATION_REPORT.json
├── PROJECT_STATE.json
├── ROLLBACK_POINTS.json
└── ralph_extended_config.json
```

### Format Rapport Exécution

```markdown
# Ralph Wiggum - [MODE] - [DATE]

## Résumé
- **Mode**: one-shot | debug | task
- **Durée**: X minutes
- **Itérations**: N
- **Score final**: XX/100

## Objectif
[Description de la tâche]

## Critères de Succès
- [x] Critère 1
- [x] Critère 2
- [ ] Critère 3 (si échec)

## Fichiers Impactés
| Fichier | Action | Score |
|---------|--------|-------|
| File.kt | Created | 85/100 |
| Other.kt | Modified | 88/100 |

## Non-Régression (si applicable)
| Module | Avant | Après | Delta |
|--------|-------|-------|-------|
| auth/ | 82 | 84 | +2 ✅ |
| user/ | 78 | 78 | 0 ✅ |

## Itérations
### Iteration 1
[Détails...]

### Iteration N
[Détails...]

## Problèmes Rencontrés
[Si applicable]

## Leçons Apprises
[Si applicable]
```

---

## Configuration

### Philosophie : Qualité Absolue

```
┌─────────────────────────────────────────────────────────────────┐
│  ⏱️ LE TEMPS N'EST PAS UNE CONTRAINTE                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  • Si ça prend 10 minutes au lieu de 2 → OK                    │
│  • Si ça prend 15 itérations au lieu de 3 → OK                 │
│  • Si Claude doit réfléchir longuement → OK                    │
│                                                                 │
│  SEUL COMPTE LE RÉSULTAT : 100/100 des 3 personas              │
│                                                                 │
│  Mieux vaut un travail parfait en 15 min                       │
│  qu'un travail bâclé en 2 min.                                 │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Paramètres par Défaut

```json
// .claude/ralph_extended_config.json
{
  "philosophy": {
    "time_is_not_a_constraint": true,
    "quality_over_speed": true,
    "target": "100/100 - perfection"
  },
  "one_shot": {
    "max_iterations": 15,
    "score_threshold": 100,
    "require_no_regression": true,
    "orchestrator_always": true,
    "create_snapshot": true
  },
  "debug": {
    "max_iterations": 15,
    "score_threshold": 100,
    "require_reproduction_test": true,
    "minimal_fix_only": true,
    "create_snapshot": true
  },
  "task": {
    "max_iterations": 15,
    "score_threshold": 100,
    "validate_all_criteria": true,
    "create_snapshot": false
  },
  "history": {
    "directory": ".claude/ralph_history/",
    "keep_days": 90,
    "include_diffs": true
  },
  "rollback": {
    "enabled": true,
    "require_confirmation": true,
    "keep_failed_reports": true
  }
}
```

---

## Exemples d'Utilisation

### One-Shot

```
User: /ralph-wiggum-one-shot

Claude: "Quelle feature veux-tu implémenter ?"

User: "Système de favoris pour les articles"

[... interview ...]

Claude: "📋 Récapitulatif:
Feature: Système de favoris
Critères: ✓ Toggle ✓ Liste ✓ Persistence

Je lance avec orchestrator complet ?"

User: "oui"

→ [Snapshot] → [Orchestrator] → [Validation] → [Rapport]
```

### Debug

```
User: /ralph-wiggum-debug le bouton favori ne répond plus

[... interview ...]

→ [Test reproduction] → [Fix ciblé] → [Non-régression] → [Rapport]
```

### Task

```
User: /ralph-wiggum-task rédige le README du projet

[... interview ...]

→ [Rédaction] → [Validation 3 personas] → [Rapport]
```

---

## Références

- `references/extended-modes.md` : Documentation détaillée des modes étendus
- `references/interview-questions.md` : Guide complet des questions d'interview
- `references/integration.md` : Intégration avec orchestrator et chef-de-projet
- `references/code-validation.md` : Critères de validation spécifiques au code
- `references/logic-validation.md` : Critères de validation logique
- `references/technical-validation.md` : Critères de validation technique
- `references/loop-patterns.md` : Patterns et templates de boucles
- `commands/` : Fichiers des commandes slash

---

## Installation des Commandes Slash

### Option 1: Copier dans le projet (Recommandé)

Pour que les commandes `/ralph-wiggum-one-shot`, `/ralph-wiggum-debug`, et `/ralph-wiggum-task` soient disponibles dans Claude Code :

```bash
# Dans ton projet, créer le dossier commands
mkdir -p .claude/commands

# Copier les fichiers de commandes
cp /mnt/skills/user/ralph-wiggum/commands/*.md .claude/commands/
```

Structure attendue :
```
ton-projet/
├── .claude/
│   └── commands/
│       ├── ralph-wiggum-one-shot.md
│       ├── ralph-wiggum-debug.md
│       └── ralph-wiggum-task.md
└── ...
```

### Option 2: Symlink (si skills montés)

```bash
ln -s /mnt/skills/user/ralph-wiggum/commands/*.md .claude/commands/
```

### Vérification

Après installation, dans Claude Code terminal :
```
/ralph-wiggum-one-shot
/ralph-wiggum-debug  
/ralph-wiggum-task
```

Ces commandes devraient maintenant être reconnues et exécuter le workflow correspondant.
