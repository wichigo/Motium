# Questions d'Interview - Modes Ralph Wiggum

Guide des questions pour l'interview chef-de-projet dans les modes étendus.

## Principe Hybride

Chaque mode a :
- **Questions essentielles** : Toujours posées (obligatoires)
- **Questions optionnelles** : Posées si complexité détectée

**Règle d'or** : UNE question à la fois. Attendre la réponse avant la suivante.

---

## 1. Mode One-Shot (Feature)

### Questions Essentielles (4)

```
Q1: "Quelle feature veux-tu implémenter ?"
    → Attendre description claire
    → Reformuler pour confirmer la compréhension

Q2: "Quels fichiers ou modules sont concernés ?"
    → Si inconnu: "Pas de souci, je vais analyser le projet"
    → Si connu: noter pour focus orchestrator

Q3: "Quels sont les critères de succès ? Comment saura-t-on que c'est terminé ?"
    → Transformer en checklist vérifiable
    → Exemple: "L'utilisateur peut X", "Le bouton Y fait Z"

Q4: "Y a-t-il des contraintes particulières ?"
    → Performance, compatibilité, deadline
    → Design existant à respecter
    → Si "non": passer aux optionnelles ou commencer
```

### Questions Optionnelles

Poser SI la feature semble toucher plusieurs couches ou être complexe :

```
Q5: "Quelles couches sont impactées ? (Base de données / API / Interface)"
    → Aide à séquencer l'orchestrator

Q6: "Cette feature doit-elle s'intégrer avec des modules existants ?"
    → Identifier les dépendances
    → Prévoir les tests d'intégration

Q7: "Des tests spécifiques à ajouter au-delà des tests unitaires ?"
    → Tests E2E, tests de performance
    → Scénarios edge case

Q8: "Y a-t-il des edge cases ou cas limites à considérer ?"
    → Input vide, très grand, malformé
    → Utilisateur sans permission
    → Mode offline
```

### Déclencheurs de Questions Optionnelles

| Signal dans la réponse | Questions à ajouter |
|------------------------|---------------------|
| Mention "base de données" ou "API" | Q5 |
| Mention d'un module existant | Q6 |
| Feature critique ou sensible | Q7, Q8 |
| "C'est complexe" ou description longue | Q5, Q6, Q7, Q8 |

### Sortie Interview One-Shot

```json
{
  "mode": "one-shot",
  "feature": {
    "name": "[nom de la feature]",
    "description": "[description complète]"
  },
  "scope": {
    "files_identified": ["File1.kt", "File2.kt"],
    "modules_impacted": ["auth", "user"],
    "layers": ["DB", "API", "UI"]
  },
  "success_criteria": [
    "L'utilisateur peut [action]",
    "Le système [comportement]",
    "Les tests [validation]"
  ],
  "constraints": {
    "performance": "[si mentionné]",
    "compatibility": "[si mentionné]",
    "design": "[si mentionné]"
  },
  "edge_cases": [
    "[cas 1]",
    "[cas 2]"
  ],
  "complexity_detected": "high|medium|low",
  "interview_timestamp": "ISO_DATE"
}
```

---

## 2. Mode Debug (Bug Fix)

### Questions Essentielles (4)

```
Q1: "Quel est le bug ? Décris le comportement actuel."
    → Symptôme précis
    → Message d'erreur si applicable

Q2: "Comment reproduire le bug ? (étapes)"
    → Liste d'étapes numérotées
    → Conditions nécessaires (données, état)

Q3: "Quel est le comportement attendu ?"
    → Ce qui DEVRAIT se passer
    → Référence si disponible (spec, autre écran)

Q4: "As-tu une idée du fichier ou module responsable ?"
    → Si oui: focus direct
    → Si non: "Je vais investiguer"
```

### Questions Optionnelles

Poser SI le bug semble complexe ou ancien :

```
Q5: "Depuis quand ce bug existe ? Récent ou ancien ?"
    → Aide à identifier le commit coupable
    → git bisect potentiel

Q6: "Y a-t-il un commit ou une version où ça marchait ?"
    → Point de comparaison
    → Diff pour identifier la régression

Q7: "As-tu des messages d'erreur ou des logs ?"
    → Stack trace
    → Logs serveur/client
    → Console errors

Q8: "Le bug apparaît-il dans des conditions particulières ?"
    → Device spécifique
    → Données particulières
    → Timing (race condition)
    → État utilisateur (connecté, permissions)
```

### Déclencheurs de Questions Optionnelles

| Signal dans la réponse | Questions à ajouter |
|------------------------|---------------------|
| "Ça marchait avant" | Q5, Q6 |
| "J'ai une erreur" | Q7 |
| "Parfois", "de temps en temps" | Q8 |
| "Je ne sais pas reproduire" | Q8 |
| Bug en production | Q5, Q7, Q8 |

### Sortie Interview Debug

```json
{
  "mode": "debug",
  "bug": {
    "id": "bug-[timestamp]",
    "description": "[symptôme]",
    "current_behavior": "[ce qui se passe]",
    "expected_behavior": "[ce qui devrait se passer]"
  },
  "reproduction": {
    "steps": [
      "1. [étape]",
      "2. [étape]",
      "3. [résultat bugué]"
    ],
    "conditions": "[conditions nécessaires]",
    "frequency": "always|sometimes|rare"
  },
  "investigation": {
    "suspected_files": ["File.kt"],
    "suspected_modules": ["module"],
    "error_messages": "[si fourni]",
    "logs": "[si fourni]"
  },
  "history": {
    "since_when": "[si connu]",
    "working_version": "[si connu]"
  },
  "special_conditions": "[si applicable]",
  "interview_timestamp": "ISO_DATE"
}
```

---

## 3. Mode Task (Tâche Générale)

### Questions Essentielles (4)

```
Q1: "Quelle tâche veux-tu accomplir ?"
    → Description claire de l'objectif
    → Contexte si nécessaire

Q2: "Quel est le livrable attendu ?"
    → Format: document, fichier, analyse, rapport
    → Où le livrable sera utilisé

Q3: "Quels sont les critères de succès ?"
    → Comment savoir si c'est réussi
    → Qualité attendue

Q4: "Qui est la cible / l'audience ?"
    → Niveau technique
    → Contexte d'utilisation
```

### Questions Optionnelles

Selon le type de tâche détecté :

```
Q5: "Y a-t-il des contraintes de format ?"
    → Markdown, PDF, longueur
    → Structure imposée

Q6: "As-tu des références ou exemples à suivre ?"
    → Inspiration
    → Standards à respecter

Q7: "Quel niveau de détail est attendu ?"
    → Survol vs exhaustif
    → Exemples inclus ou non

Q8: "Y a-t-il une deadline ou priorité ?"
    → Urgence
    → Trade-offs acceptables
```

### Types de Tâches et Questions Adaptées

| Type détecté | Questions supplémentaires |
|--------------|---------------------------|
| Documentation | Q5, Q6, Q7 |
| Analyse/Audit | Q6, Q7 |
| Génération contenu | Q5, Q6 |
| Recherche | Q6, Q7 |
| Rédaction | Q5, Q6, Q7 |

### Sortie Interview Task

```json
{
  "mode": "task",
  "task": {
    "name": "[nom de la tâche]",
    "description": "[description]",
    "type": "documentation|analysis|generation|research|writing|other"
  },
  "deliverable": {
    "format": "[format attendu]",
    "location": "[où sera le livrable]",
    "usage": "[comment il sera utilisé]"
  },
  "success_criteria": [
    "[critère 1 vérifiable]",
    "[critère 2 vérifiable]"
  ],
  "audience": {
    "who": "[cible]",
    "technical_level": "beginner|intermediate|expert",
    "context": "[contexte d'utilisation]"
  },
  "constraints": {
    "format": "[si spécifié]",
    "length": "[si spécifié]",
    "style": "[si spécifié]"
  },
  "references": "[si fourni]",
  "detail_level": "overview|standard|exhaustive",
  "priority": "low|medium|high|urgent",
  "interview_timestamp": "ISO_DATE"
}
```

---

## Détection de Fin d'Interview

### Phrases qui déclenchent le passage à l'exécution

```
- "c'est bon"
- "c'est tout"
- "on peut commencer"
- "go"
- "lance"
- "ok"
- "parfait"
- "rien d'autre"
```

### Confirmation avant exécution

Après détection de fin, toujours confirmer :

```
"📋 Récapitulatif:
[résumé de l'interview]

Critères de succès:
✓ [critère 1]
✓ [critère 2]

Je lance [le mode] ? (oui/modifier)"
```

---

## Exemples d'Interviews

### Exemple One-Shot

```
User: /ralph-wiggum-one-shot

Claude: "Quelle feature veux-tu implémenter ?"

User: "Ajouter un système de favoris pour les articles"

Claude: "Compris - un système de favoris pour marquer des articles.
Quels fichiers ou modules sont concernés selon toi ?"

User: "Probablement Article et User, et il faudra une nouvelle table"

Claude: "OK, ça touchera Article, User, et une nouvelle table favorites.
Quels sont les critères de succès ? Comment saura-t-on que c'est terminé ?"

User: "L'utilisateur peut ajouter/retirer un favori, voir sa liste de favoris, et le favori persiste après déconnexion"

Claude: "Parfait. Y a-t-il des contraintes particulières ?"

User: "Non, c'est assez simple"

Claude: "📋 Récapitulatif:
Feature: Système de favoris articles
Scope: User, Article, nouvelle table favorites
Critères: ✓ Toggle favori ✓ Liste favoris ✓ Persistence

Je lance one-shot avec orchestrator complet ? (oui/modifier)"

User: "oui"

→ [Démarre ÉTAPE 2: SNAPSHOT AVANT]
```

### Exemple Debug

```
User: /ralph-wiggum-debug le bouton favori ne marche plus

Claude: "Quel est le bug exactement ? Décris le comportement actuel."

User: "Quand je clique sur le bouton favori, rien ne se passe"

Claude: "Compris - clic sur favori = pas de réaction.
Comment reproduire ? (étapes)"

User: "Ouvrir un article, cliquer sur l'étoile en haut à droite"

Claude: "OK. Quel est le comportement attendu ?"

User: "L'étoile devrait se remplir et l'article être ajouté aux favoris"

Claude: "As-tu une idée du fichier ou module responsable ?"

User: "Peut-être FavoriteButton.kt"

Claude: "Depuis quand ce bug existe ? C'est récent ?"

User: "Depuis hier je crois, après un merge"

Claude: "📋 Récapitulatif:
Bug: Bouton favori ne répond pas au clic
Repro: Article → clic étoile → rien
Attendu: Étoile remplie + ajout aux favoris
Suspect: FavoriteButton.kt
Depuis: Hier (post-merge)

Je lance debug avec test de reproduction ? (oui/modifier)"

User: "go"

→ [Démarre ÉTAPE 2: SNAPSHOT & REPRODUCTION]
```
