# Validation de Code - Critères Ralph Wiggum

Guide spécifique pour appliquer le système Ralph Wiggum à la validation de code.

## Table des Matières
1. Critères Ralph (Lisibilité)
2. Critères Expert (Technique)
3. Critères Avocat (Robustesse)
4. Checklist par Langage
5. Scoring Code

---

## 1. Critères Ralph (Lisibilité) 🧒

### Questions Naïves sur le Code
- "C'est quoi cette variable `x`? Elle fait quoi?"
- "Pourquoi y'a 3 boucles imbriquées? C'est compliqué!"
- "Cette fonction fait 200 lignes... je comprends plus!"
- "Il manque pas des commentaires là?"

### Problèmes Détectés
| Problème | Sévérité | Exemple |
|----------|----------|---------|
| Noms cryptiques | Majeur | `val x = calc(a,b,c)` |
| Fonctions trop longues | Majeur | > 50 lignes |
| Pas de commentaires | Critique | Logique complexe non documentée |
| Magic numbers | Critique | `if (status == 7)` |
| Nesting excessif | Majeur | > 3 niveaux d'indentation |

### Corrections Ralph
```
AVANT (confus):
def p(d, t):
    return d * (1 + t/100)

APRÈS (clair):
def calculate_price_with_tax(base_price: float, tax_percent: float) -> float:
    """Calcule le prix TTC à partir du prix HT et du taux de taxe."""
    return base_price * (1 + tax_percent / 100)
```

---

## 2. Critères Expert (Technique) 🎓

### Vérifications Techniques
- Types corrects et cohérents?
- Gestion des erreurs présente?
- Edge cases traités?
- Performance acceptable?
- Sécurité respectée?

### Problèmes Détectés
| Problème | Sévérité | Exemple |
|----------|----------|---------|
| Type unsafe | Bloquant | `any` partout en TypeScript |
| Pas de try/catch | Bloquant | Appels réseau non protégés |
| SQL injection | Bloquant | String concatenation dans queries |
| N+1 queries | Majeur | Loop avec query à chaque itération |
| Memory leak | Majeur | EventListeners jamais nettoyés |
| Division par zéro | Critique | Pas de check sur diviseur |

### Checklist Expert
```
□ Types explicites sur fonctions publiques
□ Null/undefined handling
□ Input validation
□ Error boundaries
□ Resource cleanup (close, dispose)
□ Concurrency safety si applicable
□ Pas de secrets hardcodés
```

---

## 3. Critères Avocat (Robustesse) ⚖️

### Questions Contradictoires
- "Et si l'utilisateur entre n'importe quoi?"
- "Que se passe-t-il si le serveur ne répond pas?"
- "Et avec 1 million d'entrées, ça marche?"
- "Un hacker pourrait exploiter ça comment?"

### Scénarios de Casse
| Scénario | Test |
|----------|------|
| Input vide | `function("")`, `function(null)` |
| Input énorme | `function("A".repeat(1000000))` |
| Input malicieux | `function("<script>alert(1)</script>")` |
| Concurrence | Appels simultanés multiples |
| Timeout | Serveur qui ne répond jamais |
| Données corrompues | JSON malformé, encoding bizarre |

### Template Contre-Arguments Code
```
"Ce code suppose que [X], mais que se passe-t-il si:
- L'input est [null/vide/énorme/malformé]
- Le service externe [timeout/erreur/données inattendues]
- L'utilisateur [action inattendue]
- Le système [crash/redémarrage/mémoire pleine]"
```

---

## 4. Checklist par Langage

### Python
```
RALPH:
□ Docstrings présentes
□ Type hints sur fonctions
□ Noms de variables explicites
□ Pas de code dupliqué

EXPERT:
□ f-strings préférées à .format()
□ Context managers pour fichiers
□ List comprehensions idiomatiques
□ Exceptions spécifiques (pas bare except)

AVOCAT:
□ Input sanitization
□ Path traversal protection
□ Pickle/eval évités
□ Timeouts sur requests
```

### JavaScript/TypeScript
```
RALPH:
□ Noms camelCase cohérents
□ JSDoc ou TSDoc présent
□ Async/await lisible
□ Pas de callback hell

EXPERT:
□ TypeScript strict mode
□ Pas de any excessif
□ Nullish coalescing (??)
□ Optional chaining (?.)

AVOCAT:
□ XSS protection
□ CSRF tokens
□ Rate limiting
□ Prototype pollution check
```

### Kotlin (Android)
```
RALPH:
□ KDoc présent
□ Nullable types explicites
□ data classes pour DTOs
□ Extension functions lisibles

EXPERT:
□ Coroutines avec scope correct
□ Flow pour streams
□ Sealed classes pour états
□ Pas de !! excessifs

AVOCAT:
□ ProGuard rules
□ Intent validation
□ Content provider security
□ WebView safety
```

---

## 5. Scoring Code

### Calcul du Score Code
```
SCORE_CODE = 100 
  - (problèmes_lisibilité × 3)
  - (problèmes_technique × 7) 
  - (problèmes_sécurité × 15)
  - (bugs_potentiels × 10)
```

### Pénalités par Catégorie

| Catégorie | Pénalité | Exemples |
|-----------|----------|----------|
| Style | -2 | Indentation, naming inconsistent |
| Lisibilité | -3 | Magic numbers, noms cryptiques |
| Logique | -5 | Conditions inversées, off-by-one |
| Performance | -5 | N+1, boucles inutiles |
| Sécurité | -15 | Injection, XSS, auth bypass |
| Bug bloquant | -20 | Crash assuré, data corruption |

### Seuils pour Code
| Score | Verdict | Action |
|-------|---------|--------|
| 90-100 | ✅ Production-ready | Peut être mergé |
| 75-89 | ⚠️ Review | Corrections mineures avant merge |
| 50-74 | 🔄 Refactor | Réécriture partielle nécessaire |
| < 50 | ❌ Reject | Ne pas utiliser, réécrire |

---

## Exemple de Rapport Code

```
📊 VALIDATION CODE: calculate_shipping.py

SCORE: 72/100 🔄

🧒 RALPH (Lisibilité): 85/100
- ⚠️ Variable `d` non explicite (ligne 23)
- ⚠️ Fonction de 67 lignes à découper

🎓 EXPERT (Technique): 70/100
- ❌ Division sans check zéro (ligne 45)
- ⚠️ Pas de type hints
- ⚠️ Exception générique `except Exception`

⚖️ AVOCAT (Robustesse): 60/100
- ❌ Pas de validation input poids négatif
- ⚠️ Que si la distance API timeout?

CORRECTIONS SUGGÉRÉES:
1. Renommer `d` → `distance_km`
2. Ajouter `if divisor == 0: raise ValueError`
3. Ajouter validation: `if weight <= 0: raise`
4. Wrapper API avec timeout et retry

APRÈS CORRECTION: Score estimé 88/100 ✅
```
