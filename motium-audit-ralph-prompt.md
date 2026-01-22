# 🔍 AUDIT & CORRECTION - Système Abonnements/Licences Motium

## Contexte

Tu vas auditer et corriger le système d'abonnements/licences de Motium qui existe déjà partiellement mais a des bugs qui empêchent le fonctionnement global.

**Stack:** Android/Kotlin + Supabase + Stripe Billing
**Objectif:** Score Ralph 100/100 sur tout le système

---

## SPÉCIFICATIONS DE RÉFÉRENCE (Source de vérité)

### Statuts Utilisateur (subscription_type)
| Statut | Description |
|--------|-------------|
| `TRIAL` | Période d'essai 7 jours, sans carte |
| `PREMIUM` | Abonnement mensuel individuel actif |
| `LIFETIME` | Abonnement lifetime individuel (permanent) |
| `LICENSED` | Couvert par une licence pro |
| `EXPIRED` | Plus d'accès premium |

**⚠️ `FREE` doit être supprimé/migré vers `EXPIRED`**

### Statuts Licence (licenses.status)
| Statut | Description |
|--------|-------------|
| `available` | Dans le pool, non attribuée |
| `active` | Attribuée à un collaborateur |
| `suspended` | Impayé du compte pro (mensuelles uniquement) |
| `canceled` | Résiliée, en attente suppression/libération à date groupée |
| `unlinked` | Délinkage, bloquée jusqu'à date groupée |

**⚠️ `pending` doit être supprimé/migré vers `suspended`**

### Statuts Compte Pro (pro_accounts.status)
| Statut | Description |
|--------|-------------|
| `trial` | Période d'essai 7 jours |
| `active` | Au moins 1 licence achetée |
| `expired` | Essai terminé sans achat |
| `suspended` | Impayé |

---

## RÈGLES MÉTIER CRITIQUES

### 1. INDIVIDUEL - Flux
```
INSCRIPTION → TRIAL (7j)
    │
    ├─► Paiement mensuel → PREMIUM (renouvellement auto)
    ├─► Paiement lifetime → LIFETIME (permanent)
    └─► Pas de paiement / Échec → EXPIRED (immédiat au 1er échec)

RÉSILIATION mensuelle → reste PREMIUM jusqu'à fin période → EXPIRED
```

### 2. PRO - Flux
```
INSCRIPTION → trial (7j)
    │
    ├─► Peut lier collaborateurs pendant essai
    │   └─► Voit infos perso de tous
    │   └─► Voit trajets/véhicules SEULEMENT si collaborateur a abo actif
    │
    ├─► Achat licence(s) → active
    │   └─► 1er achat mensuel = définit billing_anchor_day
    │   └─► Lifetime = paiement immédiat
    │   └─► Mensuelle = proratisée, facturée à date groupée
    │
    └─► Pas d'achat à J+7 → expired
```

### 3. ATTRIBUTION LICENCE
```
Collaborateur LIFETIME     → ❌ BLOCAGE (erreur: déjà lifetime)
Collaborateur LICENSED     → ❌ BLOCAGE (erreur: déjà licensé)
Collaborateur PREMIUM      → Résilier abo perso → LICENSED
Collaborateur TRIAL/EXPIRED → LICENSED direct
```

### 4. RÉSILIATION LICENCE
```
Licence canceled + attribuée :
    └─► Collaborateur garde accès jusqu'à date groupée
    └─► À date groupée:
        ├─► Mensuelle → SUPPRIMÉE
        └─► Lifetime → available (retour pool)
    └─► Collaborateur → EXPIRED
```

### 5. DÉLINKAGE (collaborateur se délie OU pro délie)
```
Licence → unlinked
    └─► Collaborateur garde accès jusqu'à date groupée
    └─► À date groupée:
        ├─► Mensuelle → SUPPRIMÉE
        └─► Lifetime → available (retour pool)
    └─► Collaborateur → EXPIRED
```

### 6. RENOUVELLEMENT PRO (date groupée)
```
Paiement OK:
    └─► Traiter toutes les licences canceled/unlinked
    └─► Réactiver les suspended → active

Paiement FAIL (1er échec):
    └─► Licences mensuelles → suspended
    └─► Licences lifetime → pas de changement
    └─► Compte pro → suspended
    └─► Afficher bouton "Régulariser"
```

---

## PROCESSUS D'AUDIT RALPH

### Phase 1: Scanner les fichiers existants

Localise et lis ces fichiers (adapter les chemins selon ton projet):

**Supabase:**
- `supabase/functions/stripe-webhook/index.ts` (ou équivalent)
- `supabase/migrations/*.sql` (schéma actuel)

**Kotlin:**
- Repository/ViewModel liés aux subscriptions
- Modèles/Enums subscription_type, license_status
- Appels Stripe

**Bases de données:**
- Exécute des requêtes pour vérifier l'état actuel des tables

### Phase 2: Validation Ralph 3 Personas

Pour CHAQUE fichier trouvé, applique :

#### 🧒 RALPH (Clarté/Cohérence)
- Les noms de variables sont clairs ?
- Le flux est compréhensible ?
- Y a-t-il des contradictions évidentes ?
- Les statuts utilisés correspondent aux specs ?

#### 🎓 EXPERT (Technique)
- Types corrects ?
- Gestion d'erreurs présente ?
- Edge cases traités ?
- SQL injection possible ?
- N+1 queries ?

#### ⚖️ AVOCAT (Robustesse)
- Que se passe-t-il si input invalide ?
- Et si le webhook arrive 2 fois (idempotence) ?
- Et si Stripe timeout ?
- Et si l'utilisateur a 2 onglets ouverts ?

### Phase 3: Cross-Check Specs

Compare le code actuel avec les spécifications ci-dessus :

```
POUR CHAQUE règle métier:
    1. Trouver où elle est implémentée
    2. Vérifier qu'elle est correcte
    3. Si manquante ou incorrecte → FLAG comme issue
```

### Phase 4: Rapport et Corrections

Génère un rapport structuré puis corrige tous les problèmes.

---

## CHECKLIST DE VALIDATION (Score 100/100)

### Tables BDD
- [ ] `users.subscription_type` : contrainte CHECK avec TRIAL, PREMIUM, LIFETIME, LICENSED, EXPIRED
- [ ] `users.subscription_type` : défaut = 'TRIAL' (pas FREE)
- [ ] Aucun user avec subscription_type = 'FREE' (migré vers EXPIRED)
- [ ] `licenses.status` : contrainte CHECK avec available, active, suspended, canceled, unlinked
- [ ] `licenses.status` : défaut = 'available' (pas pending)
- [ ] Aucune licence avec status = 'pending' (migré vers suspended)
- [ ] `licenses.is_owner_license` : colonne existe (BOOLEAN DEFAULT false)
- [ ] `pro_accounts.status` : colonne existe avec contrainte CHECK (trial, active, expired, suspended)
- [ ] `pro_accounts.trial_ends_at` : colonne existe
- [ ] `pro_accounts.billing_anchor_day` : colonne existe

### Webhooks Stripe
- [ ] `checkout.session.completed` : gère individual (PREMIUM/LIFETIME) ET pro_license_lifetime
- [ ] `invoice.paid` : gère renouvellement individual ET pro (traitement canceled/unlinked)
- [ ] `invoice.payment_failed` : individual → EXPIRED immédiat, pro → suspended licences mensuelles
- [ ] `customer.subscription.updated` : gère cancel_at_period_end
- [ ] `customer.subscription.deleted` : → EXPIRED
- [ ] Idempotence : vérification que l'événement n'a pas déjà été traité
- [ ] Gestion erreurs : try/catch avec logs

### RPC Functions
- [ ] `assign_license_to_collaborator` : existe et gère tous les cas (LIFETIME blocage, PREMIUM résiliation, TRIAL/EXPIRED direct)
- [ ] `cancel_license` : existe et met status = 'canceled'
- [ ] `unlink_collaborator` : existe, met status = 'unlinked', calcule unlink_effective_at
- [ ] `check_premium_access` : existe et gère tous les subscription_types
- [ ] `finalize_license_assignment` : existe (pour après résiliation Stripe)

### Logique métier dans webhooks
- [ ] `processProRenewal` : supprime mensuelles canceled/unlinked, remet lifetime en available
- [ ] `processProRenewal` : passe collaborateurs concernés en EXPIRED
- [ ] `processProRenewal` : réactive les suspended → active
- [ ] `activateProIfNeeded` : définit billing_anchor_day au 1er achat mensuel
- [ ] Attribution : ne permet PAS d'attribuer à un LIFETIME ou LICENSED existant

### Code Kotlin (si applicable)
- [ ] Enum `SubscriptionType` : TRIAL, PREMIUM, LIFETIME, LICENSED, EXPIRED (pas FREE)
- [ ] Enum `LicenseStatus` : AVAILABLE, ACTIVE, SUSPENDED, CANCELED, UNLINKED (pas PENDING)
- [ ] Repository utilise les bonnes RPC functions
- [ ] Gestion du cas `NeedsCancelExisting` lors de l'attribution

---

## COMMANDE RALPH LOOP

Exécute cette boucle jusqu'à score 100/100 :

```
ralph loop "Audite et corrige le système abonnements/licences Motium.

PROCESSUS:
1. Scanner tous les fichiers liés (webhooks, migrations, RPC, Kotlin)
2. Valider avec 3 personas (Ralph, Expert, Avocat)
3. Cross-check avec les SPÉCIFICATIONS DE RÉFÉRENCE ci-dessus
4. Identifier TOUS les bugs/incohérences/manques
5. Corriger chaque problème
6. Re-valider jusqu'à 100/100

FICHIERS À ANALYSER:
- supabase/functions/stripe-webhook/**
- supabase/migrations/**
- **/repository/**Subscription**.kt
- **/model/**Subscription**.kt
- **/model/**License**.kt
- **/viewmodel/**Subscription**.kt ou **Pro**.kt

CRITÈRES DE SUCCÈS (TOUS requis):
- Toutes les contraintes CHECK présentes et correctes
- Aucun statut obsolète (FREE, pending)
- Webhooks gèrent TOUS les événements listés
- RPC functions existent et sont correctes
- Logique métier conforme aux specs
- Pas de bug bloquant détecté

Output <promise>AUDIT_COMPLETE_100</promise> quand score = 100/100."

--max-iterations 30
--score-threshold 100
--verbose
```

---

## FORMAT DU RAPPORT ATTENDU

À chaque itération, génère :

```
╔══════════════════════════════════════════════════════════════════╗
║           🔍 AUDIT MOTIUM - Iteration #N                         ║
╠══════════════════════════════════════════════════════════════════╣
║ SCORE GLOBAL: XX/100                                             ║
╠══════════════════════════════════════════════════════════════════╣
║ 📁 FICHIERS ANALYSÉS                                             ║
╠══════════════════════════════════════════════════════════════════╣
║ ✅ fichier1.ts - 100/100                                         ║
║ ⚠️ fichier2.kt - 75/100 (3 issues)                               ║
║ ❌ fichier3.sql - 50/100 (5 issues)                              ║
╠══════════════════════════════════════════════════════════════════╣
║ 🚨 ISSUES DÉTECTÉES                                              ║
╠══════════════════════════════════════════════════════════════════╣
║ [BLOCKER] fichier:ligne - Description                            ║
║ [MAJOR] fichier:ligne - Description                              ║
║ [WARNING] fichier:ligne - Description                            ║
╠══════════════════════════════════════════════════════════════════╣
║ 🔧 CORRECTIONS APPLIQUÉES                                        ║
╠══════════════════════════════════════════════════════════════════╣
║ ✓ Corrigé: description                                           ║
║ ✓ Ajouté: description                                            ║
║ ✓ Supprimé: description                                          ║
╠══════════════════════════════════════════════════════════════════╣
║ ⏭️ PROCHAINE ITÉRATION                                           ║
╠══════════════════════════════════════════════════════════════════╣
║ Issues restantes: X                                              ║
║ Action: [continuer / terminer]                                   ║
╚══════════════════════════════════════════════════════════════════╝
```

---

## NOTES IMPORTANTES

1. **Ne pas réécrire ce qui fonctionne** - Corriger uniquement les bugs/incohérences
2. **Vérifier avant de modifier** - Lire le code existant avant de proposer des changements
3. **Tester les migrations** - S'assurer que les ALTER TABLE sont idempotents (IF NOT EXISTS, DROP CONSTRAINT IF EXISTS)
4. **Backup mental** - Noter ce qui existait avant modification
5. **Cohérence** - S'assurer que Kotlin, Supabase et Stripe sont alignés

---

## DÉMARRAGE

Commence par :
1. `view` sur le dossier racine du projet pour voir la structure
2. `view` sur supabase/ pour voir les fonctions et migrations existantes
3. `view` sur le code Kotlin lié aux subscriptions
4. Générer le premier rapport d'audit

GO! 🚀
