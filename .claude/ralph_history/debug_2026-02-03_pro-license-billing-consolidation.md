# Feature: Consolidation des paiements Pro sur billing_anchor_day

**Date**: 2026-02-03
**Type**: Feature (pas un bug fix)
**Statut**: ✅ IMPLÉMENTÉ

---

## Problème Initial

Chaque achat de licences Pro créait une **nouvelle subscription Stripe**, résultant en :
- Plusieurs paiements distincts chaque mois
- Dates de renouvellement différentes pour chaque lot de licences
- `billing_anchor_day` dans `pro_accounts` non utilisé

---

## Solution Implémentée : Approche 1 - Subscription Unique

### Principe

```
Pro Account → 1 Subscription → quantity = total licences
                            → billing_cycle_anchor = billing_anchor_day
                            → UN seul paiement/mois
```

### Flow

1. **Premier achat** : Crée subscription avec `billing_cycle_anchor` aligné sur `billing_anchor_day`
2. **Achats suivants** : Met à jour `quantity` de la subscription existante
3. **Prorata** : Stripe calcule et facture le prorata automatiquement

---

## Modifications

### `create-payment-intent/index.ts`

#### 1. Nouvelle fonction `calculateBillingCycleAnchor()`

```typescript
function calculateBillingCycleAnchor(billingAnchorDay: number): number {
  // Calcule le prochain jour d'ancrage (ce mois ou le suivant)
  // Retourne un Unix timestamp pour Stripe
}
```

#### 2. Récupération des données Pro étendues

```typescript
const { data: proData } = await supabase
  .from("pro_accounts")
  .select("user_id, billing_email, billing_anchor_day, stripe_subscription_id")
  .eq("id", proAccountId)
  .single()
```

#### 3. Trois branches de création

| Condition | Action |
|-----------|--------|
| `isLifetime` | PaymentIntent one-time (inchangé) |
| `isProLicense && stripe_subscription_id existe` | **UPDATE** quantity de la subscription existante |
| Sinon | **CREATE** nouvelle subscription (avec `billing_cycle_anchor` pour Pro) |

#### 4. Nouvelle réponse enrichie

```json
{
  "client_secret": "...",
  "requires_payment": true/false,
  "is_subscription_update": true/false,
  "amount_cents": 123,  // Montant proraté si update
  "message": "Ajout de X licence(s)..."
}
```

---

## Comportement Détaillé

### Premier achat Pro (billing_anchor_day = 15)

```
Aujourd'hui: 3 février
Achat: 5 licences

→ Crée subscription avec:
  - quantity: 5
  - billing_cycle_anchor: 15 février (prochain 15)
  - Paiement immédiat: prorata du 3 au 15 février

→ Sauvegarde stripe_subscription_id dans pro_accounts
```

### Deuxième achat Pro (10 février)

```
Subscription existante: 5 licences
Achat: 3 licences supplémentaires

→ Update subscription:
  - quantity: 5 → 8
  - proration_behavior: "create_prorations"
  - Paiement immédiat: prorata du 10 au 15 février pour 3 licences

→ Le 15 février: UN paiement pour 8 licences
```

### Troisième achat Pro (20 février)

```
Subscription existante: 8 licences
Achat: 2 licences supplémentaires

→ Update subscription:
  - quantity: 8 → 10
  - Paiement immédiat: prorata du 20 février au 15 mars pour 2 licences

→ Le 15 mars: UN paiement pour 10 licences
```

---

## Fichiers Modifiés

| Fichier | Lignes | Description |
|---------|--------|-------------|
| `create-payment-intent/index.ts` | +120 | Logique de consolidation |

---

## Déploiement

```bash
supabase functions deploy create-payment-intent
```

---

## Impact sur l'App Android

L'app doit gérer la nouvelle réponse :

```kotlin
data class PaymentIntentResponse(
    val client_secret: String?,
    val requires_payment: Boolean,
    val is_subscription_update: Boolean,
    val amount_cents: Int,
    val message: String?
)

// Si requires_payment = false, pas besoin d'afficher PaymentSheet
if (response.requires_payment && response.client_secret != null) {
    // Afficher PaymentSheet avec client_secret
} else {
    // Afficher message de confirmation directement
    showToast(response.message)
}
```

---

## Non-régression

- ✅ Abonnements individuels mensuels : inchangés (pas de `billing_cycle_anchor`)
- ✅ Achats lifetime (individual et pro) : inchangés (PaymentIntent one-time)
- ✅ Premier achat Pro : crée subscription avec anchor
- ✅ Webhooks : continuent de fonctionner (même structure Stripe)

---

## Limitations Connues

1. **Subscription canceled** : Si la subscription Pro est annulée, le prochain achat créera une nouvelle subscription (comportement voulu)

2. **billing_anchor_day non modifiable** : Une fois défini au premier achat, il ne peut pas être changé facilement (nécessiterait annulation + recréation)

3. **Prorata immédiat** : Stripe facture le prorata immédiatement, pas à la fin de période
