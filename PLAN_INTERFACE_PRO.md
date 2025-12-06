# 📋 Plan de Développement - Interface Pro Motium

## 🎯 Objectif Principal
Créer une interface "Pro" qui reprend l'interface individuelle existante, avec comme seule différence visible :
- **Menu de base** : Remplacer l'icône "Véhicules" par un bouton "+" 
- **Clic sur "+"** : Étale le menu sur deux rangées pour afficher les fonctionnalités Pro
- Le Pro peut utiliser l'app en mode individuel tout en ayant accès à la gestion de ses comptes associés

---

## 🛠️ Outils et Méthodologie

### MCP Context7
**OBLIGATOIRE** : Utiliser le MCP context7 pour toutes les implémentations. Cela permet d'avoir accès à la documentation à jour des librairies utilisées.

```bash
# Avant chaque implémentation, charger le contexte
mcp context7 load supabase-kotlin
mcp context7 load stripe-android
mcp context7 load jetpack-compose
```

### Skills à utiliser
Consulter les skills disponibles dans `/mnt/skills/` avant chaque tâche :
- `/mnt/skills/user/supabase-kotlin/SKILL.md` - Pour toutes les interactions Supabase
- `/mnt/skills/user/stripe-saas-billing/SKILL.md` - Pour l'intégration Stripe
- `/mnt/skills/user/android-kotlin-dev/SKILL.md` - Pour le développement Android/Compose
- `/mnt/skills/public/frontend-design/SKILL.md` - Pour respecter l'UX/UI existante

### Règle d'or UX/UI
**REPRENDRE EXACTEMENT** l'UX/UI existante de l'application :
- Mêmes composants (`Card`, `MotiumDropdown`, etc.)
- Mêmes couleurs (`MotiumPrimary`, thème existant)
- Mêmes animations et transitions
- Mêmes patterns de navigation

---

## 📐 Architecture Actuelle (Référence)

### Structure existante :
```
presentation/
├── individual/          # Interface utilisateur individuel ✅ EXISTANTE
│   ├── home/
│   ├── calendar/
│   ├── vehicles/        # À déplacer dans le menu Pro étendu
│   ├── export/
│   └── settings/
├── enterprise/          # Interface entreprise actuelle (à renommer/adapter)
│   ├── employees/       # Base existante pour comptes associés
│   └── ...
└── components/
    ├── BottomNavigation.kt              # Nav individuel
    └── EnterpriseBottomNavigation.kt    # Nav Pro avec "+"
```

### Modèles existants :
- `User.kt` : roles `INDIVIDUAL` / `ENTERPRISE`, préférences de partage déjà présentes
- `Subscription.kt` : types `FREE` / `PREMIUM` / `LIFETIME`

---

## 🔧 Phase 0 : Données Entreprise Pro (1 jour)

### 0.1 Nouveau modèle `ProAccount.kt`
```kotlin
// domain/model/ProAccount.kt
data class ProAccount(
    val id: String,
    val userId: String,                    // Lié au User principal
    
    // Informations légales entreprise
    val companyName: String,               // Raison sociale
    val siret: String,                     // N° SIRET (14 chiffres)
    val siren: String,                     // N° SIREN (9 premiers chiffres du SIRET)
    val vatNumber: String?,                // N° TVA intracommunautaire (FR + 11 chiffres)
    val legalForm: LegalForm,              // Forme juridique (SARL, SAS, etc.)
    val shareCapital: Double?,             // Capital social
    val rcsNumber: String?,                // N° RCS
    val apeCode: String?,                  // Code APE/NAF
    
    // Adresse de facturation
    val billingAddress: Address,
    
    // Contact facturation
    val billingEmail: String,
    val billingPhone: String?,
    
    // Informations bancaires (pour prélèvement SEPA optionnel)
    val iban: String?,
    val bic: String?,
    
    // Stripe
    val stripeCustomerId: String?,
    val stripePaymentMethodId: String?,
    
    // Métadonnées
    val createdAt: Instant,
    val updatedAt: Instant
)

data class Address(
    val street: String,
    val streetComplement: String?,
    val postalCode: String,
    val city: String,
    val country: String = "FR"
)

enum class LegalForm(val displayName: String, val shortName: String) {
    AUTO_ENTREPRENEUR("Auto-entrepreneur", "AE"),
    EI("Entreprise Individuelle", "EI"),
    EIRL("EIRL", "EIRL"),
    EURL("EURL", "EURL"),
    SARL("SARL", "SARL"),
    SAS("SAS", "SAS"),
    SASU("SASU", "SASU"),
    SA("SA", "SA"),
    SCI("SCI", "SCI"),
    SNC("SNC", "SNC"),
    ASSOCIATION("Association", "ASSO"),
    OTHER("Autre", "AUTRE")
}
```

### 0.2 Table Supabase `pro_accounts`
```sql
-- Table des comptes professionnels
CREATE TABLE pro_accounts (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE UNIQUE,
    
    -- Informations légales
    company_name TEXT NOT NULL,
    siret TEXT NOT NULL,
    siren TEXT GENERATED ALWAYS AS (SUBSTRING(siret, 1, 9)) STORED,
    vat_number TEXT,                       -- Format: FR + 11 chiffres
    legal_form TEXT NOT NULL DEFAULT 'SARL',
    share_capital DECIMAL(15,2),
    rcs_number TEXT,
    ape_code TEXT,
    
    -- Adresse de facturation
    billing_street TEXT NOT NULL,
    billing_street_complement TEXT,
    billing_postal_code TEXT NOT NULL,
    billing_city TEXT NOT NULL,
    billing_country TEXT NOT NULL DEFAULT 'FR',
    
    -- Contact facturation
    billing_email TEXT NOT NULL,
    billing_phone TEXT,
    
    -- Informations bancaires
    iban TEXT,
    bic TEXT,
    
    -- Stripe
    stripe_customer_id TEXT,
    stripe_payment_method_id TEXT,
    
    -- Métadonnées
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    
    -- Contraintes
    CONSTRAINT valid_siret CHECK (LENGTH(siret) = 14 AND siret ~ '^[0-9]+$'),
    CONSTRAINT valid_vat CHECK (vat_number IS NULL OR vat_number ~ '^FR[0-9]{11}$')
);

-- Index pour recherche rapide
CREATE INDEX idx_pro_accounts_siret ON pro_accounts(siret);
CREATE INDEX idx_pro_accounts_stripe ON pro_accounts(stripe_customer_id);

-- Trigger pour updated_at
CREATE TRIGGER update_pro_accounts_updated_at
    BEFORE UPDATE ON pro_accounts
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- RLS
ALTER TABLE pro_accounts ENABLE ROW LEVEL SECURITY;

CREATE POLICY "users_can_view_own_pro_account" ON pro_accounts
    FOR SELECT USING (user_id = auth.uid());

CREATE POLICY "users_can_update_own_pro_account" ON pro_accounts
    FOR UPDATE USING (user_id = auth.uid());
```

---

## 🔧 Phase 1 : Refactoring du Menu Pro (1-2 jours)

### 1.1 Modifier `EnterpriseBottomNavigation.kt`
**Objectif** : Menu de base = Home, Calendar, Export, Settings, **+** (au lieu de Vehicles)

```kotlin
// Menu de base (première rangée) - IDENTIQUE à Individual sauf le "+"
val proBottomNavItems = listOf(
    NavItem("enterprise_home", Icons.Outlined.Home, "Accueil"),
    NavItem("enterprise_calendar", Icons.Outlined.CalendarToday, "Agenda"),
    NavItem("enterprise_export", Icons.Outlined.IosShare, "Export"),
    NavItem("enterprise_settings", Icons.Outlined.Settings, "Paramètres")
    // Le "+" est ajouté séparément avec le cercle coloré
)

// Menu étendu (deux rangées au-dessus quand "+" cliqué)
val proExpandedMenuItems = listOf(
    // Rangée 1 - Gestion des comptes
    NavItem("linked_accounts", Icons.Filled.People, "Comptes liés"),
    NavItem("licenses", Icons.Filled.CardMembership, "Licences"),
    
    // Rangée 2 - Outils Pro
    NavItem("enterprise_vehicles", Icons.Filled.DirectionsCar, "Véhicules"),
    NavItem("pro_export", Icons.Filled.FileDownload, "Export Pro"),
)
```

### 1.2 Fichiers à modifier :
- [ ] `EnterpriseBottomNavigation.kt` - Restructurer le menu
- [ ] `EnterpriseBottomNavigationSimple.kt` - Aligner
- [ ] `MotiumNavHost.kt` - Ajouter les nouvelles routes

---

## 🔧 Phase 2 : Gestionnaire des Comptes Associés (3-4 jours)

### 2.1 Nouveau modèle `LinkedAccount.kt`
```kotlin
// domain/model/LinkedAccount.kt
data class LinkedAccount(
    val id: String,
    val userId: String,                    // ID du compte individuel lié
    val proAccountId: String,              // ID du compte Pro propriétaire
    val userEmail: String,
    val userName: String,
    val licenseStatus: LicenseStatus,      // ACTIVE, PENDING, EXPIRED
    val licenseStartDate: Instant?,
    val licenseEndDate: Instant?,
    val sharingPreferences: SharingPreferences,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class SharingPreferences(
    val shareProfessionalTrips: Boolean = true,
    val sharePersonalTrips: Boolean = false,
    val shareVehicleInfo: Boolean = true,
    val shareExpenses: Boolean = false
)

enum class LicenseStatus {
    ACTIVE,      // Licence payée et valide
    PENDING,     // En attente d'acceptation par l'utilisateur
    EXPIRED,     // Licence expirée
    CANCELLED    // Licence annulée
}
```

### 2.2 Nouveau écran `LinkedAccountsScreen.kt`
```
presentation/pro/
├── accounts/
│   ├── LinkedAccountsScreen.kt      # Liste des comptes associés
│   ├── LinkedAccountsViewModel.kt
│   ├── AccountDetailsScreen.kt      # Fiche détaillée d'un compte
│   └── InviteAccountDialog.kt       # Dialog pour inviter un compte
```

**Fonctionnalités** :
- [ ] Liste des comptes liés avec statut (actif/inactif/en attente)
- [ ] Badge indiquant si licence payante ou non
- [ ] Bouton pour inviter un nouveau compte (via email ou lien)
- [ ] Accès à la fiche détaillée de chaque compte

### 2.3 Écran `AccountDetailsScreen.kt` (Fiche utilisateur)
**Affiche selon les préférences de l'utilisateur** :
- Informations de base (nom, email) - toujours visible
- Trajets professionnels - si `shareProfessionalTrips = true`
- Trajets personnels - si `sharePersonalTrips = true`
- Liste des véhicules - si `shareVehicleInfo = true`
- Dépenses - si `shareExpenses = true`

---

## 🔧 Phase 3 : Système de Licences avec Stripe (3-4 jours)

### 3.1 Tarification
```
💰 TARIF LICENCE PRO
- 5€ HT / mois / utilisateur lié
- TVA 20% = 1€
- Total TTC = 6€ / mois / utilisateur

Exemple : 10 employés = 50€ HT/mois = 60€ TTC/mois
```

### 3.2 Nouveau modèle `License.kt`
```kotlin
// domain/model/License.kt
data class License(
    val id: String,
    val proAccountId: String,              // Compte Pro propriétaire
    val linkedAccountId: String,           // Compte individuel bénéficiaire
    val pricePerMonthHT: Double = 5.0,     // 5€ HT par utilisateur
    val vatRate: Double = 0.20,            // 20% TVA
    val status: LicenseStatus,
    val startDate: Instant,
    val endDate: Instant?,
    val stripeSubscriptionId: String?,
    val stripeSubscriptionItemId: String?, // Pour facturation à l'usage
    val createdAt: Instant
) {
    val pricePerMonthTTC: Double
        get() = pricePerMonthHT * (1 + vatRate)
    
    val vatAmount: Double
        get() = pricePerMonthHT * vatRate
}
```

### 3.3 Table Supabase `licenses`
```sql
-- Table des licences
CREATE TABLE licenses (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    pro_account_id UUID REFERENCES pro_accounts(id) ON DELETE CASCADE,
    linked_account_id UUID REFERENCES linked_accounts(id) ON DELETE CASCADE,
    
    -- Tarification
    price_monthly_ht DECIMAL(10,2) NOT NULL DEFAULT 5.00,
    vat_rate DECIMAL(5,4) NOT NULL DEFAULT 0.20,
    
    -- Statut
    status TEXT NOT NULL DEFAULT 'pending', -- pending, active, expired, cancelled
    start_date TIMESTAMPTZ,
    end_date TIMESTAMPTZ,
    
    -- Stripe
    stripe_subscription_id TEXT,
    stripe_subscription_item_id TEXT,
    stripe_price_id TEXT,
    
    -- Métadonnées
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    
    UNIQUE(pro_account_id, linked_account_id)
);

-- RLS
ALTER TABLE licenses ENABLE ROW LEVEL SECURITY;

CREATE POLICY "pro_can_manage_licenses" ON licenses
    FOR ALL USING (
        pro_account_id IN (
            SELECT id FROM pro_accounts WHERE user_id = auth.uid()
        )
    );
```

### 3.4 Intégration Stripe
**Consulter** : `/mnt/skills/user/stripe-saas-billing/SKILL.md`

```kotlin
// Configuration Stripe (à créer dans l'app)
object StripeConfig {
    // TODO: Remplacer par les vraies clés une fois créées
    const val PUBLISHABLE_KEY = "pk_test_XXXXXXXXXXXXXXXX"
    const val PRICE_ID_LICENSE = "price_XXXXXXXXXXXXXXXX" // Prix 5€/mois
    
    // Webhook endpoint pour Supabase Edge Function
    const val WEBHOOK_SECRET = "whsec_XXXXXXXXXXXXXXXX"
}
```

**Produit Stripe à créer** :
1. Créer un produit "Licence Motium Pro"
2. Créer un prix récurrent : 5€/mois (HT)
3. Configurer la quantité variable (par siège)
4. Activer la facturation métrée si besoin

### 3.5 Nouvel écran `LicensesScreen.kt`
```
presentation/pro/
├── licenses/
│   ├── LicensesScreen.kt           # Gestion des licences
│   ├── LicensesViewModel.kt
│   └── PurchaseLicenseDialog.kt    # Achat de nouvelle licence
```

**Fonctionnalités** :
- [ ] Vue d'ensemble : nombre de licences actives / total
- [ ] Coût mensuel : X licences × 5€ HT = XX€ HT (+ TVA)
- [ ] Liste avec statut de paiement
- [ ] Bouton "Ajouter une licence" → Flow Stripe
- [ ] Historique des factures (via Stripe Customer Portal)

### 3.6 Logique d'activation Premium
```kotlin
// Quand une licence est activée (webhook Stripe)
suspend fun onLicenseActivated(licenseId: String) {
    val license = licenseRepository.getById(licenseId)
    val linkedAccount = linkedAccountRepository.getById(license.linkedAccountId)
    
    // Activer le Premium chez l'utilisateur lié
    userRepository.updateSubscription(
        userId = linkedAccount.userId,
        subscription = Subscription(
            type = SubscriptionType.PREMIUM,
            expiresAt = null, // Géré par Stripe
            source = "PRO_LICENSE",
            proAccountId = license.proAccountId
        )
    )
}

// Quand une licence expire/est annulée
suspend fun onLicenseExpired(licenseId: String) {
    val license = licenseRepository.getById(licenseId)
    val linkedAccount = linkedAccountRepository.getById(license.linkedAccountId)
    
    // Retour en FREE
    userRepository.updateSubscription(
        userId = linkedAccount.userId,
        subscription = Subscription(
            type = SubscriptionType.FREE,
            expiresAt = null
        )
    )
}
```

---

## 🔧 Phase 4 : Export des Données Pro (1-2 jours)

### 4.1 Nouvel écran `ProExportScreen.kt`
```
presentation/pro/
├── export/
│   ├── ProExportScreen.kt          # Export données comptes associés
│   └── ProExportViewModel.kt
```

**Fonctionnalités** :
- [ ] Sélection des comptes à inclure (tous ou sélection)
- [ ] Filtrage par période
- [ ] Filtrage par type de trajet (pro/perso selon droits)
- [ ] Format d'export : CSV, PDF, Excel
- [ ] Export consolidé (tous les comptes) ou individuel

---

## 🔧 Phase 5 : Backend Supabase Complet (2-3 jours)

### 5.1 Migration complète
```sql
-- ================================================
-- MIGRATION : Interface Pro Motium
-- ================================================

-- 1. Table des comptes professionnels
CREATE TABLE IF NOT EXISTS pro_accounts (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE UNIQUE,
    
    -- Informations légales
    company_name TEXT NOT NULL,
    siret TEXT NOT NULL,
    vat_number TEXT,
    legal_form TEXT NOT NULL DEFAULT 'SARL',
    share_capital DECIMAL(15,2),
    rcs_number TEXT,
    ape_code TEXT,
    
    -- Adresse de facturation
    billing_street TEXT NOT NULL,
    billing_street_complement TEXT,
    billing_postal_code TEXT NOT NULL,
    billing_city TEXT NOT NULL,
    billing_country TEXT NOT NULL DEFAULT 'FR',
    
    -- Contact facturation
    billing_email TEXT NOT NULL,
    billing_phone TEXT,
    
    -- Informations bancaires
    iban TEXT,
    bic TEXT,
    
    -- Stripe
    stripe_customer_id TEXT,
    stripe_payment_method_id TEXT,
    
    -- Métadonnées
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- 2. Table des comptes liés
CREATE TABLE IF NOT EXISTS linked_accounts (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    pro_account_id UUID REFERENCES pro_accounts(id) ON DELETE CASCADE,
    user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE,
    
    -- Statut
    status TEXT NOT NULL DEFAULT 'pending', -- pending, active, revoked
    
    -- Préférences de partage (définies par l'utilisateur individuel)
    sharing_preferences JSONB DEFAULT '{
        "shareProfessionalTrips": true,
        "sharePersonalTrips": false,
        "shareVehicleInfo": true,
        "shareExpenses": false
    }',
    
    -- Invitation
    invitation_token TEXT UNIQUE,
    invitation_expires_at TIMESTAMPTZ,
    invited_email TEXT,
    
    -- Métadonnées
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    
    UNIQUE(pro_account_id, user_id)
);

-- 3. Table des licences
CREATE TABLE IF NOT EXISTS licenses (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    pro_account_id UUID REFERENCES pro_accounts(id) ON DELETE CASCADE,
    linked_account_id UUID REFERENCES linked_accounts(id) ON DELETE CASCADE,
    
    -- Tarification
    price_monthly_ht DECIMAL(10,2) NOT NULL DEFAULT 5.00,
    vat_rate DECIMAL(5,4) NOT NULL DEFAULT 0.20,
    
    -- Statut
    status TEXT NOT NULL DEFAULT 'pending',
    start_date TIMESTAMPTZ,
    end_date TIMESTAMPTZ,
    
    -- Stripe
    stripe_subscription_id TEXT,
    stripe_subscription_item_id TEXT,
    stripe_price_id TEXT,
    
    -- Métadonnées
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    
    UNIQUE(pro_account_id, linked_account_id)
);

-- 4. Index
CREATE INDEX IF NOT EXISTS idx_pro_accounts_user ON pro_accounts(user_id);
CREATE INDEX IF NOT EXISTS idx_linked_accounts_pro ON linked_accounts(pro_account_id);
CREATE INDEX IF NOT EXISTS idx_linked_accounts_user ON linked_accounts(user_id);
CREATE INDEX IF NOT EXISTS idx_licenses_pro ON licenses(pro_account_id);
CREATE INDEX IF NOT EXISTS idx_licenses_status ON licenses(status);

-- 5. RLS Policies
ALTER TABLE pro_accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE linked_accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE licenses ENABLE ROW LEVEL SECURITY;

-- Pro accounts
CREATE POLICY "pro_accounts_select" ON pro_accounts
    FOR SELECT USING (user_id = auth.uid());
CREATE POLICY "pro_accounts_update" ON pro_accounts
    FOR UPDATE USING (user_id = auth.uid());

-- Linked accounts
CREATE POLICY "linked_accounts_pro_select" ON linked_accounts
    FOR SELECT USING (
        pro_account_id IN (SELECT id FROM pro_accounts WHERE user_id = auth.uid())
        OR user_id = auth.uid()
    );
CREATE POLICY "linked_accounts_pro_insert" ON linked_accounts
    FOR INSERT WITH CHECK (
        pro_account_id IN (SELECT id FROM pro_accounts WHERE user_id = auth.uid())
    );

-- Licenses
CREATE POLICY "licenses_pro_all" ON licenses
    FOR ALL USING (
        pro_account_id IN (SELECT id FROM pro_accounts WHERE user_id = auth.uid())
    );
```

### 5.2 Edge Function pour Webhook Stripe
```typescript
// supabase/functions/stripe-webhook/index.ts
import { serve } from 'https://deno.land/std@0.168.0/http/server.ts'
import Stripe from 'https://esm.sh/stripe@12.0.0'
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'

const stripe = new Stripe(Deno.env.get('STRIPE_SECRET_KEY')!, {
  apiVersion: '2023-10-16',
})

serve(async (req) => {
  const signature = req.headers.get('stripe-signature')!
  const body = await req.text()
  
  const event = stripe.webhooks.constructEvent(
    body,
    signature,
    Deno.env.get('STRIPE_WEBHOOK_SECRET')!
  )
  
  const supabase = createClient(
    Deno.env.get('SUPABASE_URL')!,
    Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!
  )
  
  switch (event.type) {
    case 'customer.subscription.created':
    case 'customer.subscription.updated':
      // Activer/mettre à jour la licence
      await handleSubscriptionUpdate(supabase, event.data.object)
      break
      
    case 'customer.subscription.deleted':
      // Désactiver la licence
      await handleSubscriptionDeleted(supabase, event.data.object)
      break
      
    case 'invoice.paid':
      // Enregistrer le paiement
      await handleInvoicePaid(supabase, event.data.object)
      break
  }
  
  return new Response(JSON.stringify({ received: true }), {
    headers: { 'Content-Type': 'application/json' },
  })
})
```

---

## 🔧 Phase 6 : Préférences de Partage (1 jour)

### 6.1 Modifier `SettingsScreen.kt` (Individual)
Ajouter une section "Partage avec mon entreprise" (visible si `linkedToCompany = true`) :

```kotlin
// Section dans SettingsScreen.kt
if (user.linkedToCompany) {
    SettingsSection(title = "Partage avec mon entreprise") {
        SwitchPreference(
            title = "Trajets professionnels",
            subtitle = "Votre entreprise peut voir vos trajets pro",
            checked = user.shareProfessionalTrips,
            onCheckedChange = { viewModel.updateSharingPreference("professional", it) }
        )
        SwitchPreference(
            title = "Trajets personnels",
            subtitle = "Votre entreprise peut voir vos trajets perso",
            checked = user.sharePersonalTrips,
            onCheckedChange = { viewModel.updateSharingPreference("personal", it) }
        )
        SwitchPreference(
            title = "Véhicules",
            subtitle = "Votre entreprise peut voir vos véhicules",
            checked = user.shareVehicles,
            onCheckedChange = { viewModel.updateSharingPreference("vehicles", it) }
        )
        SwitchPreference(
            title = "Dépenses",
            subtitle = "Votre entreprise peut voir vos notes de frais",
            checked = user.shareExpenses,
            onCheckedChange = { viewModel.updateSharingPreference("expenses", it) }
        )
    }
}
```

### 6.2 Modifier `User.kt`
```kotlin
// Ajouter ces champs
val shareVehicles: Boolean = true,
val shareExpenses: Boolean = false,
```

---

## 📁 Structure Finale des Fichiers

```
presentation/
├── individual/              # Inchangé
├── pro/                     # NOUVEAU - Interface Pro
│   ├── accounts/
│   │   ├── LinkedAccountsScreen.kt
│   │   ├── LinkedAccountsViewModel.kt
│   │   ├── AccountDetailsScreen.kt
│   │   └── InviteAccountDialog.kt
│   ├── licenses/
│   │   ├── LicensesScreen.kt
│   │   ├── LicensesViewModel.kt
│   │   └── PurchaseLicenseDialog.kt
│   ├── export/
│   │   ├── ProExportScreen.kt
│   │   └── ProExportViewModel.kt
│   ├── setup/
│   │   ├── ProAccountSetupScreen.kt   # Formulaire infos entreprise
│   │   └── ProAccountSetupViewModel.kt
│   └── billing/
│       └── BillingScreen.kt           # Historique factures Stripe
├── components/
│   └── ProBottomNavigation.kt
└── navigation/
    └── MotiumNavHost.kt

domain/model/
├── ProAccount.kt            # NOUVEAU
├── LinkedAccount.kt         # NOUVEAU
├── License.kt               # NOUVEAU
└── User.kt                  # Modifier

data/
├── repository/
│   ├── ProAccountRepository.kt      # NOUVEAU
│   ├── LinkedAccountRepository.kt   # NOUVEAU
│   └── LicenseRepository.kt         # NOUVEAU
└── remote/
    └── StripeService.kt             # NOUVEAU
```

---

## ✅ Checklist de Développement

### Phase 0 : Données Entreprise
- [ ] Créer `ProAccount.kt`
- [ ] Créer table `pro_accounts` dans Supabase
- [ ] Créer `ProAccountRepository.kt`
- [ ] Créer `ProAccountSetupScreen.kt` (formulaire SIRET, TVA, etc.)

### Phase 1 : Menu Pro
- [ ] Refactorer `EnterpriseBottomNavigation.kt`
- [ ] Mettre à jour les items du menu de base
- [ ] Implémenter le menu étendu sur 2 rangées
- [ ] Mettre à jour `MotiumNavHost.kt`

### Phase 2 : Comptes Associés
- [ ] Créer `LinkedAccount.kt`
- [ ] Créer table `linked_accounts`
- [ ] Créer `LinkedAccountsScreen.kt`
- [ ] Créer `AccountDetailsScreen.kt`
- [ ] Créer `InviteAccountDialog.kt`

### Phase 3 : Licences + Stripe
- [ ] Créer compte Stripe et produit/prix
- [ ] Créer `License.kt`
- [ ] Créer table `licenses`
- [ ] Intégrer Stripe SDK Android
- [ ] Créer `LicensesScreen.kt`
- [ ] Créer Edge Function webhook
- [ ] Implémenter logique activation Premium

### Phase 4 : Export Pro
- [ ] Créer `ProExportScreen.kt`
- [ ] Implémenter filtres multi-comptes
- [ ] Générer exports consolidés

### Phase 5 : Backend
- [ ] Exécuter migration SQL complète
- [ ] Configurer RLS
- [ ] Déployer Edge Functions
- [ ] Tester webhooks Stripe

### Phase 6 : Préférences
- [ ] Modifier `SettingsScreen.kt`
- [ ] Modifier `User.kt`
- [ ] Synchroniser avec Supabase

---

## 🚀 Ordre d'Exécution Recommandé

1. **Phase 0** (Données entreprise) - Fondation nécessaire
2. **Phase 5** (Backend) - Préparer les tables
3. **Phase 1** (Menu) - Base visible
4. **Phase 2** (Comptes) - Fonctionnalité principale
5. **Phase 6** (Préférences) - Nécessaire pour Phase 2
6. **Phase 3** (Licences + Stripe) - Monétisation
7. **Phase 4** (Export) - Fonctionnalité bonus

---

## 💡 Notes Importantes

1. **L'interface Pro = Interface Individual + Menu étendu**
   - Ne PAS dupliquer les écrans individuels
   - Réutiliser les composants existants
   - Seul le menu change

2. **Tarification**
   - **5€ HT/mois par utilisateur lié**
   - TVA 20% = 1€
   - Total TTC = 6€/mois/utilisateur
   - La licence Pro active le Premium chez l'utilisateur

3. **Respect de la vie privée**
   - L'utilisateur individuel contrôle ce qu'il partage
   - Par défaut : trajets pro = partagés, trajets perso = non partagés
   - Le Pro ne peut voir que ce qui lui est autorisé

4. **UX/UI**
   - Reprendre EXACTEMENT les composants existants
   - Mêmes couleurs, mêmes animations
   - Consulter les fichiers dans `presentation/theme/`

---

## 🔗 Commandes Claude Code

```bash
# Avant chaque développement, charger le contexte
mcp context7 load supabase-kotlin
mcp context7 load stripe-android

# Lire les skills
view /mnt/skills/user/supabase-kotlin/SKILL.md
view /mnt/skills/user/stripe-saas-billing/SKILL.md
view /mnt/skills/user/android-kotlin-dev/SKILL.md

# Phase 0 - Données entreprise
/new-feature "Créer ProAccount.kt avec SIRET, TVA, adresse facturation"
/new-feature "Créer table pro_accounts dans Supabase"

# Phase 1 - Menu
/new-feature "Refactorer EnterpriseBottomNavigation avec menu étendu 2 rangées"

# Phase 2 - Comptes
/new-feature "Créer LinkedAccountsScreen avec liste et invitation"

# Phase 3 - Stripe
/new-feature "Intégrer Stripe pour licences à 5€/mois"
```

---

*Document généré pour le projet Motium - Interface Pro*
*Tarif licence : 5€ HT/mois par utilisateur*
*Dernière mise à jour : Décembre 2025*
