# 🧪 TESTS AUTOMATISÉS - Système Abonnements/Licences Motium

## Contexte

Tu vas créer et exécuter une suite de tests automatisés pour valider **tous les use cases** du système d'abonnements/licences avant déploiement.

**Outils disponibles :**
- MCP Stripe (accès direct à l'API Stripe)
- MCP Supabase (accès direct à la BDD)
- Stripe Test Clocks (simulation du temps)
- Stripe CLI (trigger webhooks)

**Mode :** Stripe Test Mode (`sk_test_xxx`)

---

## STRIPE TEST CLOCKS - Concept Clé

Les Test Clocks permettent de "voyager dans le temps" pour tester :
- Fin de période d'essai (7 jours)
- Renouvellement mensuel
- Échec de paiement
- Résiliation effective

```
Création Test Clock → Attacher Customer → Avancer le temps → Vérifier états
```

---

## STRUCTURE DES TESTS

### Catégories
1. **INDIVIDUAL** - Parcours utilisateur individuel
2. **PRO** - Parcours compte pro + licences
3. **ATTRIBUTION** - Attribution de licences aux collaborateurs
4. **RÉSILIATION** - Résiliation licences et abonnements
5. **DÉLINKAGE** - Délinkage collaborateurs
6. **RENOUVELLEMENT** - Date groupée et paiements
7. **EDGE CASES** - Cas limites et erreurs

---

## TESTS INDIVIDUAL

### TEST 1.1 : Inscription → TRIAL
```
SETUP:
1. Créer user dans Supabase avec subscription_type = 'TRIAL'
2. Définir trial_ends_at = NOW() + 7 days

ACTIONS:
- (aucune)

VÉRIFICATIONS:
✓ user.subscription_type = 'TRIAL'
✓ user.trial_ends_at est dans 7 jours
✓ check_premium_access(user_id) retourne { has_access: true, type: 'TRIAL' }
```

### TEST 1.2 : TRIAL expiré → Accès bloqué
```
SETUP:
1. User existant en TRIAL
2. Modifier trial_ends_at = NOW() - 1 day (passé)

VÉRIFICATIONS:
✓ check_premium_access(user_id) retourne { has_access: false, reason: 'TRIAL_EXPIRED' }
```

### TEST 1.3 : Paiement mensuel → PREMIUM
```
SETUP:
1. Créer Stripe Test Clock
2. Créer Customer attaché au Test Clock
3. User en TRIAL dans Supabase

ACTIONS:
1. Créer Checkout Session avec metadata { type: 'individual', user_id: X, plan: 'monthly' }
2. Simuler paiement réussi (ou trigger webhook checkout.session.completed)

VÉRIFICATIONS:
✓ user.subscription_type = 'PREMIUM'
✓ user.subscription_expires_at = ~30 jours
✓ user.stripe_subscription_id est défini
✓ stripe_subscriptions a une entrée
✓ check_premium_access retourne { has_access: true, type: 'PREMIUM' }
```

### TEST 1.4 : Paiement lifetime → LIFETIME
```
SETUP:
1. User en TRIAL

ACTIONS:
1. Créer Checkout Session avec metadata { type: 'individual', user_id: X, plan: 'lifetime' }
2. Simuler paiement réussi

VÉRIFICATIONS:
✓ user.subscription_type = 'LIFETIME'
✓ user.subscription_expires_at = NULL
✓ check_premium_access retourne { has_access: true, type: 'LIFETIME' }
```

### TEST 1.5 : Renouvellement mensuel réussi
```
SETUP:
1. User PREMIUM avec Test Clock
2. Subscription active

ACTIONS:
1. Avancer Test Clock de 31 jours (stripe.testHelpers.testClocks.advance)
2. Attendre/trigger invoice.paid webhook

VÉRIFICATIONS:
✓ user.subscription_type = 'PREMIUM' (toujours)
✓ user.subscription_expires_at = nouvelle date (+30j)
✓ stripe_payments a nouvelle entrée
```

### TEST 1.6 : Échec paiement → EXPIRED immédiat
```
SETUP:
1. User PREMIUM avec Test Clock
2. Mettre une carte qui échoue (4000000000000341)

ACTIONS:
1. Avancer Test Clock de 31 jours
2. Attendre/trigger invoice.payment_failed webhook

VÉRIFICATIONS:
✓ user.subscription_type = 'EXPIRED'
✓ check_premium_access retourne { has_access: false, reason: 'SUBSCRIPTION_EXPIRED' }
```

### TEST 1.7 : Résiliation volontaire
```
SETUP:
1. User PREMIUM actif

ACTIONS:
1. Stripe: subscription.update({ cancel_at_period_end: true })
2. Trigger customer.subscription.updated webhook

VÉRIFICATIONS (avant fin période):
✓ user.subscription_type = 'PREMIUM' (toujours actif)
✓ user.subscription_expires_at = date fin période

ACTIONS (avancer temps):
1. Avancer Test Clock après subscription_expires_at
2. Trigger customer.subscription.deleted webhook

VÉRIFICATIONS (après fin période):
✓ user.subscription_type = 'EXPIRED'
```

---

## TESTS PRO

### TEST 2.1 : Création compte pro → trial
```
SETUP:
1. Créer pro_account dans Supabase

VÉRIFICATIONS:
✓ pro_accounts.status = 'trial'
✓ pro_accounts.trial_ends_at = +7 jours
✓ pro_accounts.billing_anchor_day = NULL
```

### TEST 2.2 : Achat 1ère licence mensuelle → active + billing_anchor
```
SETUP:
1. Pro en trial avec Test Clock

ACTIONS:
1. Créer subscription Stripe avec metadata { type: 'pro', pro_account_id: X }
2. Trigger invoice.paid

VÉRIFICATIONS:
✓ pro_accounts.status = 'active'
✓ pro_accounts.billing_anchor_day = jour actuel du mois
✓ licenses a 1 entrée avec status = 'available'
```

### TEST 2.3 : Achat licence lifetime → paiement immédiat
```
SETUP:
1. Pro actif

ACTIONS:
1. Créer Checkout Session avec metadata { type: 'pro_license_lifetime', pro_account_id: X, quantity: 2 }
2. Simuler paiement réussi

VÉRIFICATIONS:
✓ 2 nouvelles licences avec is_lifetime = true, status = 'available'
✓ stripe_payments a entrée avec payment_type = 'license_lifetime'
```

### TEST 2.4 : Pro essai expiré sans achat → expired
```
SETUP:
1. Pro en trial
2. trial_ends_at dans le passé
3. Aucune licence

VÉRIFICATIONS:
✓ pro_accounts.status devrait être 'expired' (ou logique côté app)
```

---

## TESTS ATTRIBUTION

### TEST 3.1 : Attribution à collaborateur TRIAL/EXPIRED → LICENSED
```
SETUP:
1. Pro actif avec 1 licence available
2. Collaborateur (user) en TRIAL ou EXPIRED

ACTIONS:
1. Appeler RPC assign_license_to_collaborator(license_id, collab_id, pro_id)

VÉRIFICATIONS:
✓ Retour: { success: true, action: 'ASSIGNED' }
✓ license.status = 'active'
✓ license.linked_account_id = collab_id
✓ user.subscription_type = 'LICENSED'
✓ check_premium_access(collab_id) retourne { has_access: true, type: 'LICENSED' }
```

### TEST 3.2 : Attribution à collaborateur PREMIUM → résiliation + LICENSED
```
SETUP:
1. Pro actif avec 1 licence available
2. Collaborateur en PREMIUM avec stripe_subscription_id

ACTIONS:
1. Appeler RPC assign_license_to_collaborator(...)

VÉRIFICATIONS:
✓ Retour: { success: true, action: 'CANCEL_EXISTING_SUB', stripe_subscription_id: '...' }
✓ License PAS encore attribuée (en attente résiliation)

ACTIONS (suite):
2. Annuler subscription Stripe du collaborateur
3. Appeler RPC finalize_license_assignment(license_id, collab_id)

VÉRIFICATIONS:
✓ license.status = 'active'
✓ user.subscription_type = 'LICENSED'
```

### TEST 3.3 : Attribution à collaborateur LIFETIME → BLOCAGE
```
SETUP:
1. Pro actif avec 1 licence available
2. Collaborateur en LIFETIME

ACTIONS:
1. Appeler RPC assign_license_to_collaborator(...)

VÉRIFICATIONS:
✓ Retour: { success: false, error: 'COLLABORATOR_HAS_LIFETIME' }
✓ License toujours status = 'available'
✓ User toujours LIFETIME
```

### TEST 3.4 : Attribution à collaborateur déjà LICENSED → BLOCAGE
```
SETUP:
1. Pro actif avec 1 licence available
2. Collaborateur déjà LICENSED (autre licence)

ACTIONS:
1. Appeler RPC assign_license_to_collaborator(...)

VÉRIFICATIONS:
✓ Retour: { success: false, error: 'ALREADY_LICENSED' }
```

### TEST 3.5 : Attribution licence non-available → erreur
```
SETUP:
1. Licence avec status = 'active' (déjà attribuée)

ACTIONS:
1. Appeler RPC assign_license_to_collaborator(cette_license, autre_collab, pro_id)

VÉRIFICATIONS:
✓ Retour: { success: false, error: 'LICENSE_NOT_AVAILABLE' }
```

---

## TESTS RÉSILIATION LICENCE

### TEST 4.1 : Résiliation licence active mensuelle
```
SETUP:
1. Licence mensuelle active, liée à un collaborateur

ACTIONS:
1. Appeler RPC cancel_license(license_id, pro_id)

VÉRIFICATIONS:
✓ Retour: { success: true, is_lifetime: false, linked_account_id: '...' }
✓ license.status = 'canceled'
✓ Collaborateur TOUJOURS LICENSED (jusqu'à date groupée)
```

### TEST 4.2 : Résiliation licence lifetime
```
SETUP:
1. Licence lifetime active, liée à un collaborateur

ACTIONS:
1. Appeler RPC cancel_license(license_id, pro_id)

VÉRIFICATIONS:
✓ Retour: { success: true, is_lifetime: true, linked_account_id: '...' }
✓ license.status = 'canceled'
```

### TEST 4.3 : Traitement à date groupée - mensuelle → supprimée
```
SETUP:
1. Licence mensuelle canceled
2. Test Clock Pro

ACTIONS:
1. Avancer Test Clock jusqu'à billing_anchor_day
2. Trigger invoice.paid (renouvellement pro)

VÉRIFICATIONS:
✓ Licence SUPPRIMÉE de la table
✓ Collaborateur.subscription_type = 'EXPIRED'
```

### TEST 4.4 : Traitement à date groupée - lifetime → available
```
SETUP:
1. Licence lifetime canceled

ACTIONS:
1. Trigger invoice.paid (renouvellement pro)

VÉRIFICATIONS:
✓ license.status = 'available'
✓ license.linked_account_id = NULL
✓ Collaborateur.subscription_type = 'EXPIRED'
```

---

## TESTS DÉLINKAGE

### TEST 5.1 : Délinkage → unlinked + effective_at calculé
```
SETUP:
1. Licence active liée à collaborateur
2. billing_anchor_day = 15

ACTIONS:
1. Appeler RPC unlink_collaborator(license_id, pro_id)
2. (Supposons qu'on est le 10 du mois)

VÉRIFICATIONS:
✓ Retour: { success: true, effective_at: '15 du mois courant', collaborator_id: '...' }
✓ license.status = 'unlinked'
✓ license.unlink_requested_at = NOW
✓ license.unlink_effective_at = 15 du mois
✓ Collaborateur TOUJOURS LICENSED (jusqu'au 15)
```

### TEST 5.2 : Délinkage après billing_anchor_day → effective mois suivant
```
SETUP:
1. billing_anchor_day = 15
2. On est le 20 du mois

ACTIONS:
1. Appeler RPC unlink_collaborator(...)

VÉRIFICATIONS:
✓ effective_at = 15 du mois SUIVANT
```

### TEST 5.3 : Traitement délinkage mensuelle à date groupée → supprimée
```
SETUP:
1. Licence mensuelle unlinked

ACTIONS:
1. Trigger invoice.paid à date groupée

VÉRIFICATIONS:
✓ Licence SUPPRIMÉE
✓ Collaborateur = EXPIRED
```

### TEST 5.4 : Traitement délinkage lifetime à date groupée → available
```
SETUP:
1. Licence lifetime unlinked

ACTIONS:
1. Trigger invoice.paid à date groupée

VÉRIFICATIONS:
✓ license.status = 'available'
✓ license.linked_account_id = NULL
✓ license.unlink_requested_at = NULL
✓ license.unlink_effective_at = NULL
✓ Collaborateur = EXPIRED
```

---

## TESTS RENOUVELLEMENT PRO

### TEST 6.1 : Renouvellement OK → traitement canceled/unlinked
```
SETUP:
1. Pro avec Test Clock
2. 3 licences: 1 active, 1 canceled (mensuelle), 1 unlinked (lifetime)

ACTIONS:
1. Avancer Test Clock jusqu'à billing_anchor_day
2. Paiement réussi
3. Trigger invoice.paid

VÉRIFICATIONS:
✓ License active → toujours active
✓ License canceled mensuelle → SUPPRIMÉE
✓ License unlinked lifetime → available
✓ Collaborateurs concernés → EXPIRED
```

### TEST 6.2 : Échec paiement pro → suspended
```
SETUP:
1. Pro avec Test Clock
2. Carte qui échoue
3. 2 licences mensuelles active, 1 licence lifetime active

ACTIONS:
1. Avancer Test Clock
2. Trigger invoice.payment_failed

VÉRIFICATIONS:
✓ Licences mensuelles → status = 'suspended'
✓ Licence lifetime → PAS DE CHANGEMENT (toujours active)
✓ pro_accounts.status = 'suspended'
```

### TEST 6.3 : Régularisation après échec → réactivation
```
SETUP:
1. Pro suspended avec licences suspended

ACTIONS:
1. Paiement manuel réussi
2. Trigger invoice.paid

VÉRIFICATIONS:
✓ Licences suspended → active
✓ pro_accounts.status = 'active'
✓ Collaborateurs retrouvent l'accès
```

---

## TESTS EDGE CASES

### TEST 7.1 : Webhook idempotence (doublon)
```
ACTIONS:
1. Trigger invoice.paid une première fois
2. Trigger invoice.paid une deuxième fois (même event)

VÉRIFICATIONS:
✓ Pas de doublon dans stripe_payments
✓ Pas d'erreur
✓ État final identique
```

### TEST 7.2 : Attribution pendant période essai pro
```
SETUP:
1. Pro en trial (pas encore de licence)
2. Collaborateur PREMIUM

ACTIONS:
1. Lier le collaborateur au pro (table de liaison, pas d'attribution licence)

VÉRIFICATIONS:
✓ Pro peut voir infos PERSO du collaborateur
✓ Pro NE PEUT PAS voir trajets/véhicules (collaborateur pas LICENSED)
```

### TEST 7.3 : Double attribution même licence
```
SETUP:
1. Licence active liée à Collab A

ACTIONS:
1. Tenter assign_license vers Collab B

VÉRIFICATIONS:
✓ Erreur: LICENSE_NOT_AVAILABLE
```

### TEST 7.4 : Collaborateur LICENSED dont la licence passe suspended
```
SETUP:
1. Collaborateur LICENSED via licence mensuelle
2. Pro ne paie pas → licence suspended

VÉRIFICATIONS:
✓ check_premium_access(collab) retourne { has_access: false, reason: 'LICENSE_NOT_ACTIVE' }
```

---

## SCRIPT D'EXÉCUTION

### Commandes Stripe CLI utiles
```bash
# Écouter les webhooks
stripe listen --forward-to http://localhost:54321/functions/v1/stripe-webhook

# Trigger manuel d'un webhook
stripe trigger checkout.session.completed
stripe trigger invoice.paid
stripe trigger invoice.payment_failed
stripe trigger customer.subscription.updated
stripe trigger customer.subscription.deleted
```

### Créer un Test Clock
```javascript
// Via MCP Stripe
const testClock = await stripe.testHelpers.testClocks.create({
  frozen_time: Math.floor(Date.now() / 1000),
  name: 'Motium Test Clock'
});
```

### Attacher Customer au Test Clock
```javascript
const customer = await stripe.customers.create({
  email: 'test@example.com',
  test_clock: testClock.id
});
```

### Avancer le temps
```javascript
// Avancer de 7 jours
await stripe.testHelpers.testClocks.advance(testClock.id, {
  frozen_time: Math.floor(Date.now() / 1000) + (7 * 24 * 60 * 60)
});

// Avancer de 31 jours
await stripe.testHelpers.testClocks.advance(testClock.id, {
  frozen_time: Math.floor(Date.now() / 1000) + (31 * 24 * 60 * 60)
});
```

### Cartes de test Stripe
| Numéro | Comportement |
|--------|--------------|
| 4242424242424242 | Succès |
| 4000000000000341 | Échec au paiement (attach OK, charge fail) |
| 4000000000009995 | Fonds insuffisants |
| 4000000000000002 | Carte refusée |

---

## COMMANDE RALPH LOOP

```
ralph loop "Crée et exécute la suite de tests automatisés pour le système abonnements/licences Motium.

OUTILS:
- MCP Stripe (créer customers, subscriptions, test clocks, avancer temps)
- MCP Supabase (créer users, vérifier états, appeler RPC)
- Stripe CLI si besoin pour trigger webhooks

PROCESSUS:
1. Créer les helpers de test (création user, pro, licence, etc.)
2. Implémenter chaque test de la liste ci-dessus
3. Exécuter tous les tests
4. Logger les résultats (✓ pass / ✗ fail)
5. Si échec: identifier le bug, ne pas corriger ici, juste reporter

LIVRABLES:
1. Fichier tests/subscription-tests.ts (ou .js)
2. Rapport d'exécution avec résultats
3. Liste des bugs trouvés (si any)

CRITÈRES DE SUCCÈS:
- Tous les tests INDIVIDUAL passent
- Tous les tests PRO passent
- Tous les tests ATTRIBUTION passent
- Tous les tests RÉSILIATION passent
- Tous les tests DÉLINKAGE passent
- Tous les tests RENOUVELLEMENT passent
- Tous les tests EDGE CASES passent

Output <promise>ALL_TESTS_PASS</promise> si 100% pass.
Output <promise>TESTS_DONE_WITH_FAILURES</promise> si certains échouent, avec rapport détaillé."

--max-iterations 30
--verbose
```

---

## FORMAT DU RAPPORT DE TESTS

```
╔══════════════════════════════════════════════════════════════════╗
║           🧪 RAPPORT DE TESTS - Motium Subscriptions             ║
╠══════════════════════════════════════════════════════════════════╣
║ RÉSULTAT GLOBAL: XX/YY tests passés (ZZ%)                        ║
╠══════════════════════════════════════════════════════════════════╣
║ 📋 INDIVIDUAL (7 tests)                                          ║
╠══════════════════════════════════════════════════════════════════╣
║ ✓ 1.1 Inscription → TRIAL                                        ║
║ ✓ 1.2 TRIAL expiré → Accès bloqué                                ║
║ ✓ 1.3 Paiement mensuel → PREMIUM                                 ║
║ ✓ 1.4 Paiement lifetime → LIFETIME                               ║
║ ✓ 1.5 Renouvellement réussi                                      ║
║ ✗ 1.6 Échec paiement → EXPIRED                                   ║
║   └─ ERREUR: user.subscription_type = 'PREMIUM' (attendu EXPIRED)║
║ ✓ 1.7 Résiliation volontaire                                     ║
╠══════════════════════════════════════════════════════════════════╣
║ 📋 PRO (4 tests)                                                 ║
╠══════════════════════════════════════════════════════════════════╣
║ ✓ 2.1 Création → trial                                           ║
║ ✓ 2.2 Achat licence mensuelle                                    ║
║ ✓ 2.3 Achat licence lifetime                                     ║
║ ✓ 2.4 Essai expiré                                               ║
╠══════════════════════════════════════════════════════════════════╣
║ ... (autres catégories) ...                                      ║
╠══════════════════════════════════════════════════════════════════╣
║ 🐛 BUGS TROUVÉS                                                  ║
╠══════════════════════════════════════════════════════════════════╣
║ BUG-001: Webhook invoice.payment_failed ne met pas EXPIRED       ║
║   Fichier: supabase/functions/stripe-webhook/index.ts            ║
║   Ligne probable: ~handlePaymentFailed()                         ║
║   Attendu: user.subscription_type = 'EXPIRED'                    ║
║   Actuel: user.subscription_type = 'PREMIUM'                     ║
╚══════════════════════════════════════════════════════════════════╝
```

---

## NOTES

1. **Cleanup après tests** : Supprimer les données de test (users test_*, etc.)
2. **Test Clock limite** : Stripe permet max 3 Test Clocks simultanés en mode gratuit
3. **Webhooks async** : Après avancement du temps, attendre ~2-3 sec pour le webhook
4. **Ordre des tests** : Certains tests dépendent d'autres, respecter l'ordre
