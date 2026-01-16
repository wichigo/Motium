# Configuration Supabase pour Motium

## 1. Créer le projet Supabase

1. Allez sur [supabase.com](https://supabase.com) et créez un compte
2. Cliquez sur "New Project"
3. Choisissez votre organisation
4. Nommez votre projet "Motium"
5. Créez un mot de passe pour votre base de données
6. Choisissez une région (Europe West pour la France)
7. Cliquez sur "Create new project"

## 2. Exécuter le schéma SQL

**Option A - Version Simplifiée (Recommandée)** :
1. Dans votre dashboard Supabase, allez dans l'onglet "SQL Editor"
2. Copiez tout le contenu du fichier `database/supabase_schema_simple.sql`
3. Collez-le dans l'éditeur SQL
4. Cliquez sur "Run" pour exécuter le schéma

**Option B - Version Complète** :
Si vous voulez toutes les optimisations, utilisez `database/supabase_schema.sql` mais exécutez-le section par section si vous rencontrez des erreurs.

**En cas d'erreur avec les index** :
Les fonctions dans les expressions d'index doivent être marquées IMMUTABLE. La version simple évite ce problème.

## 3. Configurer les clés dans l'application

1. Dans votre dashboard Supabase, allez dans "Settings" > "API"
2. Copiez votre "Project URL" et "anon public key"
3. Dans le fichier `SupabaseClient.kt`, remplacez :

```kotlin
const val SUPABASE_URL = "http://176.168.117.243:8000"  // Votre Project URL (self-hosted)
const val SUPABASE_ANON_KEY = "your-anon-key-here"          // Votre anon public key
```

## 4. Configurer l'authentification

1. Dans Supabase, allez dans "Authentication" > "Settings"
2. Activez "Enable email confirmations" si vous voulez la confirmation par email
3. Dans "Site URL", ajoutez l'URL de votre app (par défaut: `http://localhost:3000`)
4. Configurez les redirections selon vos besoins

## 5. Configurer Row Level Security (RLS)

Le schéma SQL active automatiquement RLS sur toutes les tables. Les utilisateurs ne peuvent accéder qu'à leurs propres données.

## 6. Test de la base de données

Vous pouvez tester vos requêtes dans l'onglet "Table Editor" de Supabase.

## 7. Structure des tables créées

- **users** : Profils utilisateurs avec abonnements
- **vehicles** : Véhicules avec barèmes kilométriques français
- **settings** : Paramètres utilisateur et configuration GPS
- **trips** : Trajets avec traces GPS en JSONB
- **Vues** : `trip_summary_view`, `daily_trip_summary`
- **Fonctions** : `get_trip_stats()`, `get_monthly_trip_count()`

## 8. Fonctionnalités automatiques

- **Triggers** : Mise à jour automatique des timestamps
- **RLS** : Sécurité au niveau des lignes
- **Indexes** : Optimisation des performances
- **Constraints** : Validation des données

## 9. Points d'attention

- Les traces GPS sont stockées en JSONB pour performance
- Les barèmes kilométriques français sont pré-configurés
- La limite de 10 trajets/mois est gérée côté application
- L'authentification Supabase gère automatiquement les sessions

## 10. Migration des données existantes (optionnel)

Si vous avez des données existantes à migrer, vous pouvez :
1. Exporter vos données depuis Room/SQLite
2. Les formater selon le schéma Supabase
3. Les importer via l'interface ou des scripts SQL

## 11. Monitoring et logs

- Utilisez l'onglet "Logs" pour surveiller les requêtes
- L'onglet "API" montre l'utilisation en temps réel
- "Database" > "Extensions" permet d'ajouter PostGIS si besoin

## Variables d'environnement (recommandé)

Pour plus de sécurité, créez un fichier `local.properties` :

```
SUPABASE_URL=http://176.168.117.243:8000
SUPABASE_ANON_KEY=your-anon-key-here
```

Et modifiez `build.gradle.kts` pour les utiliser :

```kotlin
android {
    defaultConfig {
        buildConfigField("String", "SUPABASE_URL", "\"${project.findProperty("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${project.findProperty("SUPABASE_ANON_KEY")}\"")
    }
}
```