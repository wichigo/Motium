# /ralph-wiggum-task

Exécute une tâche générale (non-code) avec validation qualité.

## Philosophie

```
⏱️ LE TEMPS N'EST PAS UNE CONTRAINTE
Score cible: 100/100 des 3 personas
Max itérations: 15
Pas d'orchestrator (pas de code)
Validation qualité par les 3 personas
```

## Types de Tâches

| Type | Exemples |
|------|----------|
| Documentation | README, guides, specs, API docs |
| Analyse | Revue code, audit perf, comparaison |
| Génération | Prompts, templates, configs |
| Recherche | Investigation, benchmark, études |
| Rédaction | Articles, rapports, présentations |

## Workflow

### ÉTAPE 1: Interview Task (Chef-de-Projet)

Pose ces questions UNE PAR UNE :

**Essentielles (obligatoires):**
1. "Quelle tâche veux-tu accomplir ?"
2. "Quel est le livrable attendu ?"
3. "Quels sont les critères de succès ?"
4. "Qui est la cible / l'audience ?"

**Optionnelles (selon le type):**
- "Contraintes de format ?"
- "Références / exemples à suivre ?"
- "Niveau de détail attendu ?"
- "Deadline ou priorité ?"

Attendre "c'est bon" / "ok" / "go" pour passer à l'exécution.

### ÉTAPE 2: Exécution avec Boucle Ralph

```
WHILE (score < 100 AND iteration < 15):
    1. Produire/améliorer le livrable
    2. Validation 3 personas
    3. Vérifier checklist critères
    4. Si insuffisant: corriger et retry
```

**Pas d'orchestrator** - exécution directe avec validation itérative.

### ÉTAPE 3: Validation Qualité (3 Personas à 100/100)

**🧒 RALPH (Clarté) = 100/100 requis**
- "C'est compréhensible ?"
- "Y'a des trucs confus ?"
- "Ça répond à la question ?"

**🎓 EXPERT (Précision) = 100/100 requis**
- "Les infos sont correctes ?"
- "Rien d'obsolète ou imprécis ?"
- "Le niveau de détail est adapté ?"

**⚖️ AVOCAT (Complétude) = 100/100 requis**
- "Il manque quelque chose ?"
- "Les alternatives sont mentionnées ?"
- "Les limites sont reconnues ?"

### ÉTAPE 4: Rapport

Générer `.claude/ralph_history/task_{date}_{name}.md`

Contenu:
- Description de la tâche
- Livrable produit
- Score qualité par persona
- Checklist critères (✅/❌)
- Itérations nécessaires

## Critères de Succès

```json
{
  "deliverable_produced": true,
  "all_criteria_met": true,
  "personas_validation": {
    "ralph_clarity": "= 100",
    "expert_precision": "= 100",
    "avocat_completude": "= 100"
  }
}
```

## Exemple

```
User: /ralph-wiggum-task

Claude: "Quelle tâche veux-tu accomplir ?"

User: "Rédiger le README du projet Motium"

Claude: "Quel est le livrable attendu ?"

User: "Un README.md complet avec installation, features, screenshots"

Claude: "Quels sont les critères de succès ?"

User: "Clair pour un nouveau dev, installation en 5 min, toutes les features listées"

Claude: "Qui est la cible ?"

User: "Développeurs Android qui découvrent le projet"

Claude: "📋 Récapitulatif:
Tâche: README.md pour Motium
Livrable: Markdown avec install + features + screenshots
Cible: Devs Android
Critères: ✓ Clair ✓ Install 5min ✓ Features complètes

Je lance la rédaction avec validation 100/100 ?"

User: "go"

→ [Rédaction] → [Validation 3 personas 100/100] → [Rapport]
```
