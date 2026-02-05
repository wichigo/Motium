# Debug Report: Stripe Webhook Signature Mismatch

**Date**: 2026-02-03
**Bug ID**: stripe-webhook-signature-mismatch
**Status**: RESOLVED

---

## Symptômes

- Les appels API Stripe retournaient **200 OK** (logs Stripe)
- Les webhooks Stripe retournaient **400 ERR** (événements envoyés)
- Message d'erreur : `Webhook Error: No signatures found matching the expected signature for payload`

## Investigation

### Hypothèses testées

1. **❌ Configuration nginx** - Ajout de `proxy_request_buffering off` pour préserver le body brut → N'a pas résolu le problème

2. **❌ Mauvais domaine** - Vérification que `api.motium.app` (pas `.org`) était configuré → Correct

3. **✅ Secret webhook désynchronisé** - Le container Docker utilisait un ancien secret

### Root Cause

Le container `motium-edge-functions` utilisait un **ancien webhook secret** qui ne correspondait pas à celui configuré dans Stripe Dashboard.

**Cause** : `docker compose restart` ne relit pas le fichier `.env`. Le container continuait d'utiliser l'ancien secret en cache.

| Source | Secret |
|--------|--------|
| Stripe Dashboard | `whsec_5raUEy4agpEw5AJABZ0zDU9z7MSh6FFG` |
| `.env` (à jour) | `whsec_5raUEy4agpEw5AJABZ0zDU9z7MSh6FFG` |
| Container (avant fix) | `whsec_c25fcccfe69...` (ancien) |

## Solution

```bash
docker compose up -d --force-recreate functions
```

Cette commande **recrée** le container et relit le `.env` avec le bon secret.

## Vérification

```bash
docker exec motium-edge-functions env | grep STRIPE_WEBHOOK
# Doit afficher le secret correspondant à Stripe Dashboard
```

## Leçons apprises

1. **`docker compose restart` ≠ relire le `.env`** - Utiliser `up -d --force-recreate` après modification de variables d'environnement

2. **Toujours vérifier les variables dans le container** avec `docker exec <container> env | grep <VAR>`

3. **Le dashboard Stripe a deux vues distinctes** :
   - "Logs" = appels API (200 OK = l'app fonctionne)
   - "Webhooks > Événements envoyés" = events vers ton endpoint (400 = problème de signature)

## Fichiers concernés

- `/etc/nginx/conf.d/motium.conf` - Config nginx (modifié mais pas la cause)
- `docker-compose.yml` - Service `functions` avec variables d'env
- `.env` - Secret `STRIPE_WEBHOOK_SECRET`

## Commandes utiles

```bash
# Vérifier le secret dans le container
docker exec motium-edge-functions env | grep STRIPE

# Recréer le container après modification .env
docker compose up -d --force-recreate functions

# Redéployer les edge functions
supabase functions deploy stripe-webhook --no-verify-jwt
```
