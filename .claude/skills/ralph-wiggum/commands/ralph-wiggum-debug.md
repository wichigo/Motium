# /ralph-wiggum-debug

Fixe un bug de manière ciblée SANS créer de régression.

## Philosophie

```
⏱️ LE TEMPS N'EST PAS UNE CONTRAINTE
Score cible: 100/100 des 3 personas
Max itérations: 15
Focus: Module bugué uniquement
Règle: FIX MINIMAL (pas de refactoring)
```

## Workflow

### ÉTAPE 1: Interview Debug (Chef-de-Projet)

Pose ces questions UNE PAR UNE :

**Essentielles (obligatoires):**
1. "Quel est le bug / comportement actuel ?"
2. "Comment le reproduire ? (étapes)"
3. "Quel est le comportement attendu ?"
4. "Fichier(s)/module(s) suspect(s) ?"

**Optionnelles (si bug complexe):**
- "Depuis quand ce bug existe ?"
- "Version/commit où ça marchait ?"
- "Messages d'erreur / logs ?"
- "Conditions particulières ?"

Attendre "c'est bon" / "ok" / "go" pour passer à l'exécution.

### ÉTAPE 2: Snapshot & Reproduction

```bash
# 1. Exécuter tests existants (capturer l'état)
# 2. Créer test de reproduction (DOIT ÉCHOUER)
# 3. Git tag: ralph-debug-before-{bug-id}
```

**IMPORTANT:** Le test de reproduction DOIT fail avant le fix.

### ÉTAPE 3: Orchestration (scope ciblé)

Agents appelés (selon le bug) :
1. `backend-analyzer` OU `frontend-analyzer` → Module bugué uniquement
2. `backend-converter` OU `frontend-converter` → Fix MINIMAL
3. `test-converter` → Test de régression
4. `syntax-validator` → Vérifier compilation

**Règles du Fix:**
- ✅ Fix minimal
- ✅ Backward compatible
- ❌ Pas de refactoring
- ❌ Pas de changements non liés

### ÉTAPE 4: Validation Debug

- [ ] Test de reproduction PASSE maintenant (bug fixé)
- [ ] TOUS les tests existants passent toujours
- [ ] Ralph score module modifié = 100

Si validation échoue → Boucle correction (max 15 itérations)

### ÉTAPE 5: Rapport

Générer `.claude/ralph_history/debug_{date}_{bug-id}.md`

Contenu:
- Description du bug
- Root cause identifiée
- Fix appliqué (diff minimal)
- Test de reproduction ajouté
- Confirmation non-régression

## Critères de Succès

```json
{
  "bug_fixed": true,
  "reproduction_test_passes": true,
  "all_previous_tests_pass": true,
  "fix_is_minimal": true,
  "ralph_score": "= 100"
}
```

## Exemple

```
User: /ralph-wiggum-debug le bouton favori ne répond plus

Claude: "Quel est le bug exactement ? Décris le comportement actuel."

User: "Quand je clique sur l'étoile, rien ne se passe"

Claude: "Comment reproduire ? (étapes)"

User: "Ouvrir un article, cliquer sur l'étoile"

Claude: "Quel est le comportement attendu ?"

User: "L'étoile se remplit et l'article s'ajoute aux favoris"

Claude: "As-tu une idée du fichier responsable ?"

User: "Peut-être FavoriteButton.kt"

Claude: "📋 Récapitulatif:
Bug: Bouton favori ne répond pas au clic
Repro: Article → clic étoile → rien
Suspect: FavoriteButton.kt

Je crée le test de reproduction et lance le debug ?"

User: "go"

→ [Test repro FAIL] → [Fix ciblé] → [Test repro PASS] → [Non-régression] → [Rapport]
```
