# /ralph-wiggum-one-shot

Implémente une feature complète SANS créer de régression.

## Philosophie

```
⏱️ LE TEMPS N'EST PAS UNE CONTRAINTE
Score cible: 100/100 des 3 personas
Max itérations: 15
Prends tout le temps nécessaire pour la perfection.
```

## Workflow

### ÉTAPE 1: Interview (Chef-de-Projet)

Pose ces questions UNE PAR UNE :

**Essentielles (obligatoires):**
1. "Quelle feature veux-tu implémenter ?"
2. "Quels fichiers/modules sont concernés ?"
3. "Quels sont les critères de succès ?"
4. "Y a-t-il des contraintes particulières ?"

**Optionnelles (si complexité détectée):**
- "Quelles couches sont impactées ? (DB/API/UI)"
- "Intégrations avec modules existants ?"
- "Tests spécifiques à ajouter ?"
- "Edge cases à considérer ?"

Attendre "c'est bon" / "ok" / "go" pour passer à l'exécution.

### ÉTAPE 2: Snapshot Avant

```bash
# 1. Audit Ralph complet du projet (score baseline)
# 2. Exécuter tous les tests existants
# 3. Git tag: ralph-oneshot-before-{feature}
```

### ÉTAPE 3: Orchestration (TOUJOURS)

Appeler les agents dans l'ordre :
1. `backend-analyzer` → Comprendre l'existant
2. `frontend-analyzer` → Comprendre l'UI
3. `backend-converter` → Implémenter DB + API
4. `frontend-converter` → Implémenter UI
5. `test-converter` → Créer les tests
6. `security-converter` → Audit sécurité
7. `syntax-validator` → Vérifier compilation

**Après CHAQUE agent:** Validation Ralph (score = 100 requis)

### ÉTAPE 4: Validation Non-Régression

- [ ] Audit Ralph après (aucun module avec score inférieur)
- [ ] Tous les tests avant passent toujours
- [ ] Nouveaux tests passent
- [ ] Checklist critères interview validée

Si régression → Boucle correction (max 15 itérations)

### ÉTAPE 5: Rapport

Générer `.claude/ralph_history/oneshot_{date}_{feature}.md`

## Critères de Succès

```json
{
  "feature_complete": true,
  "all_criteria_met": true,
  "no_regression": true,
  "ralph_score": "= 100"
}
```

## Exemple

```
User: /ralph-wiggum-one-shot

Claude: "Quelle feature veux-tu implémenter ?"

User: "Système de favoris pour les articles"

Claude: "Compris. Quels fichiers/modules sont concernés ?"

[... interview complète ...]

Claude: "📋 Récapitulatif:
Feature: Système de favoris
Critères: ✓ Toggle ✓ Liste ✓ Persistence

Je lance avec orchestrator complet ?"

User: "go"

→ [Snapshot] → [Orchestrator + Ralph 100/100] → [Validation] → [Rapport]
```
