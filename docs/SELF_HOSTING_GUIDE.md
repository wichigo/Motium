# 🚀 Guide d'auto-hébergement Motium

Ce guide explique comment héberger Motium (APIs + site web) sur ton propre serveur.

## 📦 Fichiers fournis

Télécharge l'archive `motium-server-setup.tar.gz` qui contient :

- `docker-compose.yml` - Configuration Docker pour tous les services
- `.env.example` - Template des variables d'environnement
- `nginx-motium.conf` - Configuration Nginx
- `install.sh` - Script d'installation automatique

## 🏗️ Architecture

```
Internet → Nginx (SSL) → Services Docker
                           ├── Supabase (Auth, API, Storage, Realtime)
                           ├── PostgreSQL
                           ├── Site web Motium (Next.js)
                           ├── Nominatim (Géocodage)
                           ├── Tileserver (Cartes OSM)
                           └── OSRM (Itinéraires)
```

## 📖 Documentation complète

Voir le fichier `TUTORIEL_HEBERGEMENT_MOTIUM.md` pour le guide étape par étape.

## ⚡ Installation rapide

```bash
# 1. Télécharge et extrait les fichiers
tar -xzf motium-server-setup.tar.gz
cd motium-server-setup

# 2. Lance le script d'installation
chmod +x install.sh
sudo ./install.sh

# 3. Suis les instructions affichées
```

## 🔗 Liens utiles

- [Documentation Supabase Self-Hosting](https://supabase.com/docs/guides/self-hosting)
- [Nominatim Installation](https://nominatim.org/release-docs/latest/admin/Installation/)
- [OSRM Docker](https://hub.docker.com/r/osrm/osrm-backend)
- [Let's Encrypt](https://letsencrypt.org/)
