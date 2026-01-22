# Ralph Loop Patterns - Guide des Boucles Itératives

Patterns et templates pour utiliser efficacement le système de boucle Ralph.

## Table des Matières
1. Principes Fondamentaux
2. Templates par Type de Tâche
3. Gestion des Prompts
4. Anti-Patterns à Éviter
5. Debugging des Boucles

---

## 1. Principes Fondamentaux

### La Règle d'Or
> "Le prompt ne change pas, mais le code évolue."

À chaque itération, Claude voit :
- Le même prompt original
- L'état actuel des fichiers (modifiés par les itérations précédentes)
- Le feedback de la validation Ralph

### Les 4 Piliers

```
1. ITÉRATION PERSISTANTE
   Ne pas abandonner au premier échec.
   Laisser la boucle affiner le travail.

2. ÉCHECS INFORMATIFS
   "Deterministically bad" = les échecs sont prévisibles.
   Chaque erreur enseigne quelque chose.

3. PROMPTS DE QUALITÉ
   Le succès dépend de bons prompts, pas juste du modèle.
   Investir du temps dans la rédaction.

4. CRITÈRES VÉRIFIABLES
   Définir des conditions de succès mesurables.
   Éviter les critères subjectifs.
```

### Structure d'un Bon Prompt Ralph

```markdown
## [TITRE DE LA TÂCHE]

### Contexte
[Bref contexte du projet/feature]

### Objectif
[Ce qui doit être accompli - PRÉCIS]

### Requirements
- [Requirement 1 - vérifiable]
- [Requirement 2 - vérifiable]
- [Requirement 3 - vérifiable]

### Success Criteria
- [Critère 1 - mesurable]
- [Critère 2 - mesurable]
- [Critère 3 - mesurable]

### Process (optionnel)
1. [Étape 1]
2. [Étape 2]
3. [Étape 3]

### Contraintes (optionnel)
- [Contrainte technique]
- [Contrainte de performance]

### Output
Output <promise>COMPLETION_KEYWORD</promise> quand:
- [Condition 1]
- [Condition 2]
```

---

## 2. Templates par Type de Tâche

### Template: Nouvelle Feature

```markdown
ralph loop "Implémente [NOM_FEATURE] pour [APP].

### Contexte
[Description du contexte actuel de l'app]

### Requirements Fonctionnels
- [ ] [Fonctionnalité A]
- [ ] [Fonctionnalité B]
- [ ] [Fonctionnalité C]

### Requirements Techniques
- Architecture: [MVVM/Clean/autre]
- Tests: Coverage minimum [X]%
- Performance: [Critère perf]

### Fichiers à Créer
- [Package]/[Feature].kt
- [Package]/[FeatureViewModel].kt
- [Package]/[FeatureRepository].kt
- [Package]/[FeatureTest].kt

### Success Criteria
- Tous les tests passent
- Pas d'erreurs lint
- Intégration avec [module existant] fonctionnelle
- UI responsive

### Process
1. Créer les data classes / models
2. Implémenter le Repository
3. Créer le ViewModel
4. Ajouter les tests
5. Créer l'UI
6. Intégrer et tester end-to-end

Output <promise>FEATURE_COMPLETE</promise> quand tout est validé."

--max-iterations 25
--score-threshold 85
```

### Template: Bug Fix

```markdown
ralph loop "Fix: [DESCRIPTION DU BUG]

### Reproduction
1. [Étape 1]
2. [Étape 2]
3. [Résultat actuel]
4. [Résultat attendu]

### Fichiers Suspects
- [Fichier1.kt] - [Raison]
- [Fichier2.kt] - [Raison]

### Debug Process
1. Reproduire le bug avec un test
2. Identifier la root cause
3. Implémenter le fix minimal
4. Vérifier qu'il n'y a pas de régression
5. Ajouter un test de régression

### Success Criteria
- Le bug ne se reproduit plus
- Tests existants passent toujours
- Test de régression ajouté et passe
- Pas de side effects

### Contraintes
- Fix minimal (pas de refactoring)
- Backward compatible

Output <promise>BUG_FIXED</promise> quand résolu."

--max-iterations 15
```

### Template: Refactoring

```markdown
ralph loop "Refactore [MODULE] vers [PATTERN/ARCHITECTURE].

### État Actuel
- [Description du code actuel]
- [Problèmes identifiés]

### État Cible
- [Description de l'architecture cible]
- [Bénéfices attendus]

### Étapes de Migration
1. [ ] Extraire interfaces
2. [ ] Créer nouvelles implémentations
3. [ ] Migrer les dépendances
4. [ ] Supprimer le code legacy
5. [ ] Mettre à jour les tests

### Règles
- Les tests doivent passer à CHAQUE étape
- Commits atomiques par étape
- Pas de changement de comportement

### Success Criteria
- Architecture conforme au pattern [X]
- 100% des tests passent
- Pas de code legacy restant
- Documentation mise à jour

Output <promise>REFACTOR_DONE</promise> quand migration complète."

--max-iterations 30
```

### Template: Migration/Upgrade

```markdown
ralph loop "Migre [TECHNO_A] vers [TECHNO_B].

### Scope
- Fichiers concernés: [pattern ou liste]
- Dépendances à updater: [liste]

### Mapping de Migration
| Avant (A) | Après (B) |
|-----------|-----------|
| [ancien]  | [nouveau] |
| [ancien]  | [nouveau] |

### Process
1. Updater les dépendances
2. Migrer fichier par fichier
3. Adapter les tests
4. Vérifier le build
5. Run tous les tests

### Rollback Plan
En cas de blocage:
- Documenter le problème
- Output <promise>BLOCKED</promise>

### Success Criteria
- Build réussi sans warnings
- Tous les tests passent
- Aucune référence à [TECHNO_A] restante
- Performance équivalente ou meilleure

Output <promise>MIGRATION_COMPLETE</promise> quand terminé."

--max-iterations 40
```

### Template: Tests Coverage

```markdown
ralph loop "Augmente la coverage de [MODULE] à [X]%.

### État Actuel
- Coverage actuelle: [Y]%
- Fichiers non couverts: [liste]

### Priorités
1. [Fichier critique 1] - 0% → 80%
2. [Fichier critique 2] - 30% → 80%
3. [Autres fichiers] - selon importance

### Types de Tests à Ajouter
- Unit tests pour chaque fonction publique
- Tests des edge cases
- Tests des error paths
- Tests d'intégration si applicable

### Règles
- Tests lisibles et maintenables
- Pas de tests triviaux (getters/setters simples)
- Mock uniquement les dépendances externes
- Naming: should_[expected]_when_[condition]

### Success Criteria
- Coverage >= [X]%
- Tous les tests passent
- Pas de tests flaky
- Tests documentés si complexes

Output <promise>COVERAGE_DONE</promise> quand objectif atteint."

--max-iterations 20
```

### Template: Multi-Phase avec Checkpoints

```markdown
ralph loop "Construis [PROJET/FEATURE] en phases.

## Phase 1: Setup & Structure
- [ ] Créer la structure de dossiers
- [ ] Configurer les dépendances
- [ ] Setup de base

→ Output <promise>PHASE1_DONE</promise> quand terminé

## Phase 2: Core Logic
- [ ] Implémenter [composant A]
- [ ] Implémenter [composant B]
- [ ] Tests unitaires

→ Output <promise>PHASE2_DONE</promise> quand terminé

## Phase 3: Intégration
- [ ] Connecter les composants
- [ ] Tests d'intégration

⚠️ HARD STOP: Attendre validation avant Phase 4

→ Output <promise>PHASE3_DONE</promise> quand terminé

## Phase 4: Polish
- [ ] Optimisations
- [ ] Documentation
- [ ] Cleanup

→ Output <promise>PROJECT_COMPLETE</promise> quand tout est fini"

--max-iterations 50
```

---

## 3. Gestion des Prompts

### Prompt Trop Vague ❌
```
"Améliore le code"
```
→ Claude ne sait pas quand s'arrêter, boucle infinie probable.

### Prompt Bien Défini ✅
```
"Améliore la performance du module de rendu.
Critères: 
- FPS >= 60 sur device mid-range
- Memory usage < 100MB
- Pas de regression fonctionnelle

Output <promise>PERF_DONE</promise> quand les 3 critères sont remplis."
```

### Completion Promises

Utilisez des promises claires et uniques :

```
✅ BON:
<promise>FEATURE_AUTH_COMPLETE</promise>
<promise>BUG_12345_FIXED</promise>
<promise>MIGRATION_V2_DONE</promise>

❌ MAUVAIS:
<promise>DONE</promise>           # Trop générique
<promise>OK</promise>             # Peut apparaître par hasard
<promise>SUCCESS</promise>        # Ambigu
```

### Gestion des Blocages dans le Prompt

Toujours inclure une échappatoire :

```markdown
### En cas de blocage
Si après 5 itérations le problème persiste:
1. Documenter ce qui bloque
2. Lister les approches tentées
3. Suggérer des alternatives
4. Output <promise>BLOCKED_NEED_HELP</promise>
```

---

## 4. Anti-Patterns à Éviter

### Anti-Pattern 1: Pas de Limite d'Itérations
```bash
# ❌ DANGER: Boucle potentiellement infinie
ralph loop "Fais quelque chose"

# ✅ CORRECT: Toujours une limite
ralph loop "Fais quelque chose" --max-iterations 20
```

### Anti-Pattern 2: Critères Subjectifs
```markdown
# ❌ MAUVAIS: Comment mesurer "propre" ?
Success Criteria:
- Code propre
- Bonne architecture

# ✅ BON: Critères mesurables
Success Criteria:
- Lint: 0 errors, 0 warnings
- Toutes les fonctions < 30 lignes
- Cyclomatic complexity < 10
```

### Anti-Pattern 3: Scope Trop Large
```markdown
# ❌ MAUVAIS: Trop ambitieux pour une boucle
"Refactore toute l'application en Clean Architecture"

# ✅ BON: Scope limité et incrémental
"Refactore le module Auth en Clean Architecture.
Les autres modules restent inchangés."
```

### Anti-Pattern 4: Dépendances Externes Non Gérées
```markdown
# ❌ MAUVAIS: Dépend d'une API externe
"Intègre l'API météo et affiche les données"
# → Peut échouer si l'API timeout

# ✅ BON: Mock les dépendances
"Intègre l'API météo avec mock pour les tests.
Critère: fonctionne avec données mockées.
L'intégration réelle sera testée manuellement."
```

### Anti-Pattern 5: Pas de Tests
```markdown
# ❌ MAUVAIS: Pas de validation automatique
"Implémente le feature"

# ✅ BON: Tests comme critère
"Implémente le feature.
Success: tous les tests passent.
Process: écrire le test AVANT l'implémentation."
```

---

## 5. Debugging des Boucles

### Symptôme: Boucle Infinie

**Causes possibles:**
1. Pas de completion promise dans le prompt
2. Critères impossibles à atteindre
3. Bug dans le code qui empêche les tests de passer

**Solution:**
```bash
# Ajouter un max-iterations faible pour debug
ralph loop "..." --max-iterations 5 --verbose

# Analyser pourquoi la condition n'est pas remplie
```

### Symptôme: Score Stagnant

**Causes possibles:**
1. Problème structurel que l'auto-fix ne peut pas résoudre
2. Dépendance externe défaillante
3. Test flaky

**Solution:**
```markdown
# Ajouter dans le prompt:
"Si le score stagne pendant 3 itérations:
1. Identifier le problème bloquant
2. Documenter les tentatives
3. Output <promise>STUCK</promise> avec explication"
```

### Symptôme: Iterations Trop Nombreuses

**Causes possibles:**
1. Scope trop large
2. Critères trop stricts
3. Prompt ambigu

**Solution:**
- Découper en phases plus petites
- Réduire le score_threshold temporairement
- Clarifier le prompt avec des exemples

### Logs de Debug

Activer le mode verbose pour voir chaque itération :

```bash
ralph loop "..." --verbose

# Output pour chaque itération:
# ├── Iteration #3
# ├── Files modified: [liste]
# ├── Tests: 12 passed, 2 failed
# ├── Score: 72/100
# ├── Issues: [liste]
# └── Decision: Continue (score < 80)
```

---

## Quick Reference

### Commandes

| Commande | Description |
|----------|-------------|
| `ralph loop "prompt"` | Démarre une boucle |
| `--max-iterations N` | Limite d'itérations |
| `--score-threshold N` | Score minimum (défaut: 80) |
| `--completion-promise "X"` | Mot-clé de fin |
| `--verbose` | Mode détaillé |
| `ralph cancel` | Annule la boucle en cours |

### Completion Promises Standards

| Promise | Usage |
|---------|-------|
| `DONE` | Générique |
| `FEATURE_COMPLETE` | Feature terminée |
| `BUG_FIXED` | Bug résolu |
| `MIGRATION_DONE` | Migration terminée |
| `REFACTOR_COMPLETE` | Refactoring terminé |
| `BLOCKED` | Besoin d'aide humaine |
| `PHASE_N_DONE` | Phase N terminée |

### Seuils Recommandés

| Type de Tâche | max_iterations | score_threshold |
|---------------|----------------|-----------------|
| Bug fix simple | 10 | 80 |
| Feature moyenne | 20-25 | 80 |
| Refactoring | 30 | 85 |
| Migration | 40 | 80 |
| Projet complet | 50+ | 85 |
