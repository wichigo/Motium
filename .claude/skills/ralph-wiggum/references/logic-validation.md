# Validation Logique - Critères Ralph Wiggum

Guide pour appliquer le système Ralph Wiggum aux raisonnements, arguments et prises de décision.

## Table des Matières
1. Critères Ralph (Cohérence)
2. Critères Expert (Rigueur)
3. Critères Avocat (Solidité)
4. Catalogue des Fallacies
5. Scoring Logique

---

## 1. Critères Ralph (Cohérence) 🧒

### Détection des Incohérences Naïves
Ralph est excellent pour repérer ce qui "sonne faux" même sans formation en logique.

**Questions Ralph**:
- "Attend, tu viens pas de dire le contraire?"
- "Mais ça prouve pas ce que tu dis!"
- "C'est quoi le rapport entre les deux?"
- "Pourquoi tu sautes de A à Z sans passer par B?"

### Types d'Incohérences
| Type | Description | Exemple |
|------|-------------|---------|
| Contradiction directe | A et non-A dans le même argument | "Il faut toujours tester... mais parfois on peut skip" |
| Saut logique | Conclusion non reliée aux prémisses | "Il pleut, donc le projet échouera" |
| Circularité | La conclusion est dans la prémisse | "C'est vrai parce que c'est évident" |
| Double standard | Règles différentes selon le cas | "X ne doit pas faire ça, mais Y peut" |

### Template Incohérence Ralph
```
🧒 INCONSISTANCE DÉTECTÉE:

Tu dis: "[citation 1]"
Mais aussi: "[citation 2]"

Ralph: "Euh... c'est pas la même chose? Je comprends plus!"

PROBLÈME: [explication simple de la contradiction]
RÉSOLUTION: [comment harmoniser ou clarifier]
```

---

## 2. Critères Expert (Rigueur) 🎓

### Structure Logique Formelle

**Validité d'un argument**:
```
Prémisse 1: P → Q (Si P alors Q)
Prémisse 2: P
Conclusion: Q ✅ (Modus Ponens - valide)

vs.

Prémisse 1: P → Q
Prémisse 2: Q
Conclusion: P ❌ (Affirmation du conséquent - invalide)
```

### Vérifications de Rigueur
- Les prémisses sont-elles vraies?
- La conclusion découle-t-elle logiquement?
- Y a-t-il des prémisses cachées?
- Le raisonnement est-il valide ET sound?

### Checklist Rigueur Logique
```
□ Prémisses explicitement énoncées
□ Chaque étape justifiée
□ Pas de prémisses cachées
□ Conclusion proportionnée aux prémisses
□ Distinctions nécessité/suffisance respectées
□ Causalité vs corrélation distinguées
```

### Types de Raisonnement
| Type | Fiabilité | Usage |
|------|-----------|-------|
| Déductif | Certain (si valide) | Math, logique pure |
| Inductif | Probable | Science, patterns |
| Abductif | Plausible | Diagnostic, hypothèses |
| Par analogie | Suggestif | Créativité, argumentation |

---

## 3. Critères Avocat (Solidité) ⚖️

### Stress-Test des Arguments

**Questions Déstabilisantes**:
- "Et si tes prémisses sont fausses?"
- "N'y a-t-il pas d'autres explications possibles?"
- "Quel est le meilleur argument contre ta position?"
- "Dans quelles conditions ton raisonnement s'effondre?"

### Attaques sur les Prémisses
```
PRÉMISSE: "Les utilisateurs préfèrent les interfaces simples"
CONTRE-ATTAQUES:
- Source? Quelle étude?
- "Simple" selon qui? C'est subjectif
- Tous les utilisateurs? Vraiment?
- Dans quel contexte?
```

### Attaques sur l'Inférence
```
ARGUMENT: "Nos concurrents ont X, donc nous devons avoir X"
CONTRE-ATTAQUES:
- Pourquoi suivre les concurrents?
- Nos utilisateurs sont-ils les mêmes?
- X réussit-il vraiment chez eux?
- N'y a-t-il pas mieux que X?
```

### Template Solidité
```
⚖️ STRESS-TEST DE L'ARGUMENT:

ARGUMENT ORIGINAL: "[résumé]"

ATTAQUE 1 (Prémisse faible):
"La prémisse '[X]' suppose que [Y], mais [contre-exemple]"

ATTAQUE 2 (Alternative ignorée):
"Même si les prémisses sont vraies, on pourrait aussi conclure [Z]"

ATTAQUE 3 (Cas limite):
"Ce raisonnement ne fonctionne pas si [condition]"

RECOMMANDATION: [comment renforcer l'argument]
```

---

## 4. Catalogue des Fallacies

### Fallacies de Relevance
| Fallacy | Description | Exemple | Détection |
|---------|-------------|---------|-----------|
| Ad Hominem | Attaquer la personne | "Tu dis ça parce que t'es jeune" | Ignorer qui parle, évaluer l'argument |
| Appel à l'autorité | "Expert dit donc vrai" | "Elon Musk dit que..." | Expert dans CE domaine? |
| Appel à la popularité | "Tout le monde le fait" | "99% des devs utilisent..." | Majorité ≠ vérité |
| Appel à la tradition | "On a toujours fait ainsi" | "On code comme ça depuis..." | Ancien ≠ correct |
| Homme de paille | Déformer l'argument adverse | "Tu veux donc dire que..." | Vérifier la représentation |

### Fallacies de Causalité
| Fallacy | Description | Exemple | Détection |
|---------|-------------|---------|-----------|
| Post hoc | Après donc à cause | "Depuis le refacto, plus de bugs" | Corrélation ≠ causalité |
| Cause unique | Ignorer la complexité | "Le projet a échoué à cause de X" | Rarement une seule cause |
| Pente glissante | Enchaînement non prouvé | "Si on fait A, alors B, puis C..." | Chaque étape probable? |

### Fallacies de Structure
| Fallacy | Description | Exemple | Détection |
|---------|-------------|---------|-----------|
| Faux dilemme | Seulement 2 options | "Soit on shippe maintenant, soit on annule" | Chercher option C |
| Argument circulaire | Conclusion = prémisse | "C'est vrai car c'est évident" | La prémisse prouve-t-elle? |
| Moving goalposts | Changer les critères | "Ok mais maintenant il faut aussi..." | Fixer les critères à l'avance |
| No true Scotsman | Redéfinir pour exclure | "Un VRAI dev ne ferait pas ça" | Définition arbitraire? |

### Template Détection Fallacy
```
🔍 FALLACY DÉTECTÉE: [nom]

CITATION: "[extrait contenant la fallacy]"

STRUCTURE DU PROBLÈME:
- Ce qui est dit: [reformulation]
- Ce qui est implicite: [prémisse cachée]
- Pourquoi c'est fallacieux: [explication]

CORRECTION:
- Version valide: "[reformulation corrigée]"
- Ou admettre: "[limite de l'argument]"
```

---

## 5. Scoring Logique

### Calcul du Score
```
SCORE_LOGIQUE = 100
  - (contradictions × 15)
  - (sauts_logiques × 10)
  - (fallacies × 12)
  - (prémisses_non_justifiées × 8)
  - (alternatives_ignorées × 6)
```

### Grille de Pénalités Détaillée
| Problème | Pénalité | Notes |
|----------|----------|-------|
| Contradiction explicite | -15 | Grave: détruit la crédibilité |
| Contradiction implicite | -10 | Moins visible mais problématique |
| Fallacy majeure | -12 | Ad hominem, faux dilemme, etc. |
| Fallacy mineure | -6 | Appel à popularité modéré |
| Saut logique | -10 | Conclusion non supportée |
| Prémisse cachée | -8 | Non explicite |
| Prémisse fausse | -15 | Base incorrect |
| Alternative ignorée | -6 | Vision tunnel |
| Généralisation abusive | -8 | "Tous", "Jamais", etc. |

### Niveaux de Confiance
| Score | Verdict | Usage |
|-------|---------|-------|
| 90-100 | ✅ Argument solide | Peut convaincre un sceptique |
| 75-89 | ⚠️ Argument acceptable | Avec quelques réserves |
| 60-74 | 🔄 Argument faible | Nécessite renforcement |
| < 60 | ❌ Argument invalide | Ne pas utiliser tel quel |

---

## Exemple de Rapport Logique

```
📊 VALIDATION LOGIQUE: "Argument pour migration vers microservices"

SCORE: 65/100 🔄

🧒 RALPH (Cohérence): 70/100
- ⚠️ "On a trop de dette technique" puis "notre monolithe est stable"
  → Contradiction apparente à résoudre
- ✅ Fil logique général compréhensible

🎓 EXPERT (Rigueur): 60/100
- ❌ Prémisse cachée: "microservices = moins de dette"
  → Non justifié, souvent l'inverse
- ⚠️ Causalité non établie: Netflix réussit ≠ nous réussirons
- ⚠️ "Évident que..." → pas d'évidence, besoin de preuves

⚖️ AVOCAT (Solidité): 65/100
- ❌ FALLACY: Appel à l'autorité ("Netflix et Amazon font ça")
- ❌ FALLACY: Faux dilemme ("microservices ou mourir")
- ⚠️ Contre-arguments ignorés:
  - Complexité opérationnelle
  - Overhead réseau
  - Debugging distribué

RECONSTRUCTION SUGGÉRÉE:
1. Quantifier la dette technique actuelle
2. Estimer le coût de migration vs. refacto monolithe
3. Adresser explicitement les risques microservices
4. Considérer solutions intermédiaires (modular monolith)
5. Retirer les appels à l'autorité non pertinents

APRÈS CORRECTIONS: Score estimé 82/100 ⚠️
```

---

## Quick Reference: Questions à Poser

### Toujours demander
1. "Quelle est la conclusion?"
2. "Quelles sont les prémisses?"
3. "Le lien prémisses→conclusion est-il valide?"
4. "Les prémisses sont-elles vraies?"
5. "Y a-t-il des alternatives ignorées?"

### Red Flags Immédiats
- "Évidemment...", "Clairement..."
- "Tout le monde sait que..."
- "On a toujours fait comme ça"
- "Si on ne fait pas X, alors catastrophe"
- Absence totale de nuance
