# Project: Motium

## Description
Application Android de suivi de mobilité et trajets avec OCR, cartographie et synchronisation cloud via architecture offline-first

## Tech Stack
- **Language**: Kotlin
- **Min SDK**: 31 (Android 12)
- **Target SDK**: 34 (Android 14)
- **Compile SDK**: 36
- **UI**: Jetpack Compose + Material 3
- **Architecture**: MVVM (Hilt prévu mais désactivé)
- **Database locale**: Room + KSP
- **Backend**: Supabase (Postgrest, Auth, Realtime, Storage)
- **Auth**: Supabase Auth + Google Sign-In
- **Maps**: OSMDroid (OpenStreetMap)
- **Location**: Google Play Services Location
- **OCR**: ML Kit Text Recognition (on-device)
- **Images**: Coil
- **Networking**: Retrofit + Moshi + OkHttp
- **Async**: Kotlin Coroutines + Flow
- **Background**: WorkManager
- **Security**: EncryptedSharedPreferences
- **PDF**: iTextPDF
- **Permissions**: Accompanist Permissions
- **Date/Time**: kotlinx-datetime
- **Serialization**: kotlinx-serialization-json
- **Testing**: JUnit, MockK, Mockito, Turbine, Robolectric, Espresso

## MCP Tools

### Context7 (AUTO-INVOKE - IMPORTANT)
Always use context7 when I need code generation, setup or configuration steps,
or library/API documentation. Automatically use the Context7 MCP tools
(resolve-library-id, get-library-docs) without me having to explicitly ask.

**Priority libraries for Context7:**
- `/supabase/supabase` - Backend, Auth, Realtime, Storage
- `/androidx/compose` - Jetpack Compose UI
- `/androidx/room` - Database locale
- `/square/retrofit` - Networking
- `/square/moshi` - JSON parsing
- `/coil-kt/coil` - Image loading
- `/google/mlkit` - OCR Text Recognition
- `/osmdroid/osmdroid` - OpenStreetMap maps

---

# SUPABASE DATABASE SCHEMA

> **RÈGLE D'OR : NE PAS MODIFIER CE SCHÉMA SAUF DEMANDE EXPLICITE DE L'UTILISATEUR**
>
> Ce schéma est la source de vérité. Toute modification de la structure des tables,
> colonnes, contraintes, RLS policies, fonctions ou triggers doit être explicitement
> demandée par l'utilisateur. En cas de doute, demander confirmation avant toute modification.

## Project Info (Supabase Self-Hosted)
- **Server IP**: `176.168.117.243`
- **API URL**: `https://api.motium.app`
- **Studio URL**: `https://studio.motium.app`
- **Nominatim URL**: `https://nominatim.motium.app`
- **OSRM URL**: `https://osrm.motium.app`
- **Tiles URL**: `https://tiles.motium.app`
- **MapStyle URL**: `https://mapstyle.motium.app`
- **Pooler Port**: `6543`

## Tables (Schema: public)

### users
Profil utilisateur principal, lié à auth.users via auth_id.
| Column | Type | Constraints | Default | Description |
|--------|------|-------------|---------|-------------|
| id | uuid | PK | uuid_generate_v4() | |
| auth_id | uuid | FK → auth.users.id, nullable | | Lien vers Supabase Auth |
| name | varchar | NOT NULL | | |
| email | varchar | UNIQUE, NOT NULL | | |
| role | varchar | CHECK (INDIVIDUAL, ENTERPRISE) | 'INDIVIDUAL' | |
| subscription_type | varchar | CHECK (FREE, TRIAL, EXPIRED, PREMIUM, LIFETIME, LICENSED) | 'FREE' | Cache du statut d'abonnement |
| subscription_expires_at | timestamptz | nullable | | Cache de la date d'expiration |
| phone_number | varchar | nullable | '' | |
| address | text | nullable | '' | |
| favorite_colors | jsonb | nullable | '[]' | |
| stripe_customer_id | text | nullable | | |
| stripe_subscription_id | text | nullable | | DEPRECATED |
| trial_started_at | timestamptz | nullable | | |
| trial_ends_at | timestamptz | nullable | | |
| phone_verified | boolean | nullable | false | |
| verified_phone | varchar | nullable | | |
| device_fingerprint_id | text | nullable | | |
| consider_full_distance | boolean | nullable | false | |
| version | integer | nullable | 1 | Pour sync offline |
| created_at | timestamptz | nullable | now() | |
| updated_at | timestamptz | nullable | now() | |

### vehicles
Véhicules de l'utilisateur pour le calcul des indemnités kilométriques.
| Column | Type | Constraints | Default | Description |
|--------|------|-------------|---------|-------------|
| id | uuid | PK | uuid_generate_v4() | |
| user_id | uuid | FK → users.id, nullable | | |
| name | varchar | NOT NULL | | |
| type | varchar | CHECK (CAR, MOTORCYCLE, SCOOTER, BIKE) | | |
| license_plate | varchar | nullable | | |
| power | varchar | CHECK (3CV, 4CV, 5CV, 6CV, 7CV+), nullable | | Puissance fiscale |
| fuel_type | varchar | CHECK (GASOLINE, DIESEL, ELECTRIC, HYBRID, OTHER), nullable | | |
| mileage_rate | numeric | NOT NULL | | Taux au km |
| is_default | boolean | nullable | false | |
| total_mileage_perso | float8 | nullable | 0.0 | |
| total_mileage_pro | float8 | nullable | 0.0 | |
| total_mileage_work_home | float8 | nullable | 0.0 | |
| deleted_at | timestamptz | nullable | | Soft delete |
| version | integer | nullable | 1 | |
| created_at | timestamptz | nullable | now() | |
| updated_at | timestamptz | nullable | now() | |

### trips
Trajets enregistrés par l'utilisateur (manuels ou auto-tracking).
| Column | Type | Constraints | Default | Description |
|--------|------|-------------|---------|-------------|
| id | uuid | PK | uuid_generate_v4() | |
| user_id | uuid | FK → users.id, nullable | | |
| vehicle_id | uuid | FK → vehicles.id, nullable | | |
| start_time | timestamptz | NOT NULL | | |
| end_time | timestamptz | nullable | | |
| start_latitude | numeric | NOT NULL | | |
| start_longitude | numeric | NOT NULL | | |
| end_latitude | numeric | nullable | | |
| end_longitude | numeric | nullable | | |
| start_address | text | nullable | | |
| end_address | text | nullable | | |
| distance_km | numeric | nullable | 0.0 | |
| duration_ms | bigint | nullable | 0 | |
| type | varchar | CHECK (PROFESSIONAL, PERSONAL) | 'PERSONAL' | |
| is_validated | boolean | nullable | false | |
| cost | numeric | nullable | 0.0 | Indemnité calculée |
| trace_gps | jsonb | nullable | | Coordonnées GPS |
| is_work_home_trip | boolean | nullable | false | |
| reimbursement_amount | float8 | nullable | | |
| notes | text | nullable | | |
| matched_route_coordinates | text | nullable | | Cache OSRM map-matching |
| deleted_at | timestamptz | nullable | | Soft delete |
| version | integer | nullable | 1 | |
| created_at | timestamptz | nullable | now() | |
| updated_at | timestamptz | nullable | now() | |

### expenses_trips
Frais professionnels (carburant, péages, repas, etc.).
| Column | Type | Constraints | Default | Description |
|--------|------|-------------|---------|-------------|
| id | uuid | PK | gen_random_uuid() | |
| user_id | uuid | FK → users.id, nullable | | |
| type | varchar | CHECK (FUEL, HOTEL, TOLL, PARKING, RESTAURANT, MEAL_OUT, OTHER) | | |
| amount | numeric | CHECK (>= 0), NOT NULL | | Montant TTC |
| amount_ht | float8 | nullable | | Montant HT |
| note | text | nullable | '' | |
| photo_uri | text | nullable | | URI du justificatif |
| date | date | nullable | | |
| deleted_at | timestamptz | nullable | | Soft delete |
| created_at | timestamptz | NOT NULL | now() | |
| updated_at | timestamptz | NOT NULL | now() | |

### work_schedules
Horaires de travail pour l'auto-tracking intelligent.
| Column | Type | Constraints | Default | Description |
|--------|------|-------------|---------|-------------|
| id | uuid | PK | uuid_generate_v4() | |
| user_id | uuid | FK → users.id, NOT NULL | | |
| day_of_week | integer | CHECK (1-7), NOT NULL | | 1=Lundi, 7=Dimanche |
| start_hour | integer | CHECK (0-23), NOT NULL | | |
| start_minute | integer | CHECK (0-59), NOT NULL | | |
| end_hour | integer | CHECK (0-23), NOT NULL | | |
| end_minute | integer | CHECK (0-59), NOT NULL | | |
| is_active | boolean | nullable | true | |
| is_overnight | boolean | nullable | false | Horaire de nuit |
| deleted_at | timestamptz | nullable | | Soft delete |
| created_at | timestamptz | nullable | now() | |
| updated_at | timestamptz | nullable | now() | |

### auto_tracking_settings
Paramètres d'auto-tracking par utilisateur.
| Column | Type | Constraints | Default | Description |
|--------|------|-------------|---------|-------------|
| id | uuid | PK | uuid_generate_v4() | |
| user_id | uuid | FK → users.id, UNIQUE, NOT NULL | | |
| tracking_mode | text | CHECK (ALWAYS, WORK_HOURS_ONLY, DISABLED) | 'DISABLED' | |
| min_trip_distance_meters | integer | nullable | 100 | |
| min_trip_duration_seconds | integer | nullable | 60 | |
| created_at | timestamptz | nullable | now() | |
| updated_at | timestamptz | nullable | now() | |

### pro_accounts
Comptes entreprise (Pro) pour gérer des employés.
| Column | Type | Constraints | Default | Description |
|--------|------|-------------|---------|-------------|
| id | uuid | PK | gen_random_uuid() | |
| user_id | uuid | FK → users.id, UNIQUE, NOT NULL | | |
| company_name | text | NOT NULL | | |
| siret | text | nullable | | |
| vat_number | text | nullable | | |
| legal_form | text | CHECK (SARL, SAS, SASU, EURL, SA, EI, MICRO, OTHER), nullable | | |
| billing_address | text | nullable | | |
| billing_email | text | nullable | | |
| departments | jsonb | nullable | '[]' | Départements/services |
| billing_day | integer | CHECK (1-28), nullable | 5 | |
| billing_anchor_day | integer | CHECK (1-28), nullable | | Jour de facturation groupée |
| stripe_subscription_id | text | nullable | | ID subscription Stripe principale |
| created_at | timestamptz | NOT NULL | now() | |
| updated_at | timestamptz | NOT NULL | now() | |

### licenses
Licences Pro attribuables aux employés.
| Column | Type | Constraints | Default | Description |
|--------|------|-------------|---------|-------------|
| id | uuid | PK | gen_random_uuid() | |
| pro_account_id | uuid | FK → pro_accounts.id, NOT NULL | | |
| linked_account_id | uuid | nullable | | ID du compte lié |
| price_monthly_ht | numeric | NOT NULL | 5.00 | |
| vat_rate | numeric | NOT NULL | 0.20 | |
| status | text | CHECK (pending, active, expired, cancelled, paused) | 'pending' | |
| start_date | timestamptz | nullable | | |
| end_date | timestamptz | nullable | | |
| stripe_subscription_ref | uuid | FK → stripe_subscriptions.id, nullable | | |
| stripe_subscription_item_id | text | nullable | | |
| stripe_price_id | text | nullable | | |
| stripe_payment_intent_id | text | nullable | | |
| linked_at | timestamptz | nullable | | |
| is_lifetime | boolean | nullable | false | |
| unlink_requested_at | timestamptz | nullable | | |
| unlink_effective_at | timestamptz | nullable | | |
| billing_starts_at | timestamptz | nullable | | |
| paused_at | timestamptz | nullable | | Timestamp when the license was paused. Only unassigned licenses can be paused. |
| created_at | timestamptz | NOT NULL | now() | |
| updated_at | timestamptz | NOT NULL | now() | |

### company_links
Liens entre employés et comptes Pro.
| Column | Type | Constraints | Default | Description |
|--------|------|-------------|---------|-------------|
| id | uuid | PK | gen_random_uuid() | |
| user_id | uuid | FK → users.id, nullable | | Employé |
| linked_pro_account_id | uuid | FK → pro_accounts.id, NOT NULL | | Entreprise |
| company_name | text | NOT NULL | | Cache du nom entreprise |
| department | text | nullable | | |
| status | text | CHECK (PENDING, ACTIVE, INACTIVE, REVOKED) | 'PENDING' | |
| share_professional_trips | boolean | NOT NULL | true | |
| share_personal_trips | boolean | NOT NULL | false | |
| share_personal_info | boolean | NOT NULL | true | |
| share_expenses | boolean | NOT NULL | false | |
| invitation_token | text | UNIQUE, nullable | | |
| invitation_email | text | nullable | | Email de l'invité |
| invitation_expires_at | timestamptz | nullable | | |
| linked_at | timestamptz | nullable | | |
| linked_activated_at | timestamptz | nullable | | |
| unlinked_at | timestamptz | nullable | | |
| created_at | timestamptz | NOT NULL | now() | |
| updated_at | timestamptz | NOT NULL | now() | |

### stripe_subscriptions
Abonnements Stripe (Individual et Pro).
| Column | Type | Constraints | Default | Description |
|--------|------|-------------|---------|-------------|
| id | uuid | PK | gen_random_uuid() | |
| user_id | uuid | FK → users.id, nullable | | Pour Individual |
| pro_account_id | uuid | FK → pro_accounts.id, nullable | | Pour Pro |
| stripe_subscription_id | text | UNIQUE, nullable | | |
| stripe_customer_id | text | NOT NULL | | |
| stripe_price_id | text | nullable | | |
| stripe_product_id | text | nullable | | |
| subscription_type | text | CHECK (individual_monthly, individual_lifetime, pro_license_monthly, pro_license_lifetime) | | |
| status | text | CHECK (incomplete, incomplete_expired, trialing, active, past_due, canceled, unpaid, paused) | 'incomplete' | |
| quantity | integer | nullable | 1 | |
| currency | text | nullable | 'eur' | |
| unit_amount_cents | integer | nullable | | |
| current_period_start | timestamptz | nullable | | |
| current_period_end | timestamptz | nullable | | |
| cancel_at_period_end | boolean | nullable | false | |
| canceled_at | timestamptz | nullable | | |
| ended_at | timestamptz | nullable | | |
| metadata | jsonb | nullable | '{}' | |
| created_at | timestamptz | nullable | now() | |
| updated_at | timestamptz | nullable | now() | |

### stripe_payments
Paiements Stripe (factures, PaymentIntents).
| Column | Type | Constraints | Default | Description |
|--------|------|-------------|---------|-------------|
| id | uuid | PK | gen_random_uuid() | |
| user_id | uuid | FK → users.id, nullable | | |
| pro_account_id | uuid | FK → pro_accounts.id, nullable | | |
| stripe_subscription_ref | uuid | FK → stripe_subscriptions.id, nullable | | |
| stripe_payment_intent_id | text | nullable | | |
| stripe_invoice_id | text | nullable | | |
| stripe_charge_id | text | nullable | | |
| stripe_customer_id | text | NOT NULL | | |
| payment_type | text | CHECK (subscription_payment, one_time_payment, setup_payment) | | |
| amount_cents | integer | NOT NULL | | |
| amount_received_cents | integer | nullable | | |
| currency | text | nullable | 'eur' | |
| status | text | CHECK (pending, processing, succeeded, failed, refunded, partially_refunded, disputed, canceled) | 'pending' | |
| failure_code | text | nullable | | |
| failure_message | text | nullable | | |
| invoice_number | text | nullable | | |
| invoice_pdf_url | text | nullable | | |
| hosted_invoice_url | text | nullable | | |
| period_start | timestamptz | nullable | | |
| period_end | timestamptz | nullable | | |
| refund_id | text | nullable | | |
| refund_amount_cents | integer | nullable | | |
| refund_reason | text | nullable | | |
| refunded_at | timestamptz | nullable | | |
| receipt_url | text | nullable | | |
| receipt_email | text | nullable | | |
| metadata | jsonb | nullable | '{}' | |
| paid_at | timestamptz | nullable | | |
| created_at | timestamptz | nullable | now() | |
| updated_at | timestamptz | nullable | now() | |

### mileage_allowance_rates
Barèmes officiels des indemnités kilométriques (lecture seule).
| Column | Type | Constraints | Default | Description |
|--------|------|-------------|---------|-------------|
| id | uuid | PK | uuid_generate_v4() | |
| vehicle_type | text | CHECK (CAR, MOTORCYCLE, MOPED, SCOOTER_50, SCOOTER_125) | | |
| fiscal_power | text | CHECK (3CV_AND_LESS, 4CV, 5CV, 6CV, 7CV_AND_MORE), nullable | | |
| cylinder_capacity | text | CHECK (50CC_AND_LESS, 51CC_TO_125CC, MORE_THAN_125CC), nullable | | |
| mileage_bracket_min | integer | NOT NULL | | |
| mileage_bracket_max | integer | nullable | | |
| calculation_type | text | CHECK (FORMULA, FIXED_RATE) | | |
| coefficient_a | numeric | nullable | | |
| coefficient_b | numeric | nullable | | |
| rate_per_km | numeric | nullable | | |
| year | integer | NOT NULL | 2024 | |
| created_at | timestamptz | nullable | now() | |
| updated_at | timestamptz | nullable | now() | |

### user_consents
Consentements RGPD de l'utilisateur.
| Column | Type | Constraints | Default | Description |
|--------|------|-------------|---------|-------------|
| id | uuid | PK | gen_random_uuid() | |
| user_id | uuid | FK → users.id, NOT NULL | | |
| consent_type | text | CHECK (location_tracking, data_collection, company_data_sharing, analytics, marketing) | | |
| granted | boolean | NOT NULL | false | |
| granted_at | timestamptz | nullable | | |
| revoked_at | timestamptz | nullable | | |
| consent_version | text | NOT NULL | | |
| ip_address | text | nullable | | |
| user_agent | text | nullable | | |
| created_at | timestamptz | nullable | now() | |
| updated_at | timestamptz | nullable | now() | |

### privacy_policy_acceptances
Acceptations des CGU/politique de confidentialité.
| Column | Type | Constraints | Default | Description |
|--------|------|-------------|---------|-------------|
| id | uuid | PK | gen_random_uuid() | |
| user_id | uuid | FK → users.id, NOT NULL | | |
| policy_version | text | NOT NULL | | |
| policy_url | text | NOT NULL | | |
| policy_hash | text | nullable | | |
| accepted_at | timestamptz | NOT NULL | now() | |
| ip_address | text | nullable | | |
| user_agent | text | nullable | | |
| created_at | timestamptz | nullable | now() | |

### gdpr_data_requests
Demandes RGPD (export, suppression).
| Column | Type | Constraints | Default | Description |
|--------|------|-------------|---------|-------------|
| id | uuid | PK | gen_random_uuid() | |
| user_id | uuid | FK → users.id, nullable | | |
| user_email | text | nullable | | |
| request_type | text | CHECK (data_export, data_deletion, data_rectification) | | |
| status | text | CHECK (pending, processing, completed, failed, cancelled) | 'pending' | |
| requested_at | timestamptz | NOT NULL | now() | |
| processed_at | timestamptz | nullable | | |
| completed_at | timestamptz | nullable | | |
| expires_at | timestamptz | nullable | | |
| export_file_url | text | nullable | | |
| export_format | text | CHECK (json, pdf, zip), nullable | | |
| export_size_bytes | bigint | nullable | | |
| deletion_reason | text | nullable | | |
| data_deleted | jsonb | nullable | | |
| stripe_cleanup_status | text | nullable | | |
| error_message | text | nullable | | |
| retry_count | integer | nullable | 0 | |
| ip_address | text | nullable | | |
| user_agent | text | nullable | | |
| processed_by | text | nullable | | |
| created_at | timestamptz | nullable | now() | |
| updated_at | timestamptz | nullable | now() | |

### gdpr_audit_logs
Logs d'audit RGPD.
| Column | Type | Constraints | Default | Description |
|--------|------|-------------|---------|-------------|
| id | uuid | PK | gen_random_uuid() | |
| user_id | uuid | FK → users.id, nullable | | |
| action | text | CHECK (consent_granted, consent_revoked, policy_accepted, data_export_requested, data_export_completed, data_export_downloaded, data_deletion_requested, data_deletion_completed, account_created, account_login, profile_updated) | | |
| details | jsonb | nullable | | |
| consent_type | text | nullable | | |
| ip_address | text | nullable | | |
| user_agent | text | nullable | | |
| created_at | timestamptz | nullable | now() | |

### data_retention_policies
Politiques de rétention des données (admin).
| Column | Type | Constraints | Default | Description |
|--------|------|-------------|---------|-------------|
| id | uuid | PK | gen_random_uuid() | |
| data_type | text | UNIQUE, CHECK (trips, expenses, vehicles, work_schedules, company_links, stripe_payments, gdpr_requests, audit_logs) | | |
| retention_days | integer | NOT NULL | | |
| auto_delete | boolean | nullable | false | |
| notify_before_days | integer | nullable | | |
| description | text | nullable | | |
| legal_basis | text | nullable | | |
| created_at | timestamptz | nullable | now() | |
| updated_at | timestamptz | nullable | now() | |

### password_reset_tokens
Tokens de réinitialisation de mot de passe (via Resend).
| Column | Type | Constraints | Default | Description |
|--------|------|-------------|---------|-------------|
| id | uuid | PK | gen_random_uuid() | |
| user_id | uuid | FK → auth.users.id, nullable | | |
| email | text | CHECK (length > 0), NOT NULL | | |
| token | text | UNIQUE, CHECK (length > 0), NOT NULL | | |
| expires_at | timestamptz | NOT NULL | | |
| used_at | timestamptz | nullable | | |
| created_at | timestamptz | nullable | now() | |

### email_verification_tokens
Tokens de vérification d'email.
| Column | Type | Constraints | Default | Description |
|--------|------|-------------|---------|-------------|
| id | uuid | PK | gen_random_uuid() | |
| user_id | uuid | FK → auth.users.id, nullable | | |
| email | text | NOT NULL | | |
| token | text | UNIQUE, NOT NULL | | |
| expires_at | timestamptz | NOT NULL | | |
| verified_at | timestamptz | nullable | | |
| created_at | timestamptz | nullable | now() | |

### unlink_confirmation_tokens
Tokens de confirmation pour délier un employé.
| Column | Type | Constraints | Default | Description |
|--------|------|-------------|---------|-------------|
| id | uuid | PK | gen_random_uuid() | |
| company_link_id | uuid | FK → company_links.id, NOT NULL | | |
| employee_user_id | uuid | FK → auth.users.id, NOT NULL | | |
| pro_account_id | uuid | FK → pro_accounts.id, NOT NULL | | |
| initiated_by | text | CHECK (employee, pro_account), NOT NULL | | |
| initiator_email | text | NOT NULL | | |
| token | text | UNIQUE, NOT NULL | | |
| expires_at | timestamptz | NOT NULL | | |
| confirmed_at | timestamptz | nullable | | |
| cancelled_at | timestamptz | nullable | | |
| created_at | timestamptz | nullable | now() | |

### withdrawal_waivers
Consentements de renonciation au droit de rétractation (Article L221-28 Code de la consommation).
| Column | Type | Constraints | Default | Description |
|--------|------|-------------|---------|-------------|
| id | uuid | PK | gen_random_uuid() | |
| user_id | uuid | FK → auth.users.id, NOT NULL | | |
| accepted_immediate_execution | boolean | NOT NULL | false | Accepte exécution immédiate |
| accepted_waiver | boolean | NOT NULL | false | Renonce au droit de rétractation |
| ip_address | text | nullable | | |
| user_agent | text | nullable | | |
| app_version | text | NOT NULL | | |
| consented_at | timestamptz | NOT NULL | now() | |
| subscription_id | text | nullable | | ID Stripe associé |
| created_at | timestamptz | NOT NULL | now() | |

## SQL Functions (RPCs)

### Authentification & Utilisateurs
| Function | Arguments | Returns | Security | Description |
|----------|-----------|---------|----------|-------------|
| `get_my_user_id()` | - | uuid | DEFINER | Retourne l'ID public.users de l'utilisateur connecté |
| `get_user_id_from_auth()` | - | uuid | DEFINER | Retourne l'ID public.users depuis auth.uid() |
| `get_current_user_id()` | - | uuid | DEFINER | Alias pour get_user_id_from_auth |

### Calcul d'indemnités
| Function | Arguments | Returns | Security | Description |
|----------|-----------|---------|----------|-------------|
| `calculate_car_reimbursement()` | power_cv, previous_km, trip_km, is_electric | float8 | INVOKER | Calcule l'indemnité voiture |
| `calculate_two_wheeler_reimbursement()` | vehicle_type, previous_km, trip_km, is_electric | float8 | INVOKER | Calcule l'indemnité 2 roues |
| `calculate_mileage_allowance()` | p_vehicle_type, p_annual_mileage_km, p_fiscal_power, p_cylinder_capacity, p_year | numeric | INVOKER | Calcul général |
| `get_mileage_rate_per_km()` | p_vehicle_type, p_annual_mileage_km, p_fiscal_power, p_cylinder_capacity, p_year | numeric | INVOKER | Taux au km |

### Auto-tracking
| Function | Arguments | Returns | Security | Description |
|----------|-----------|---------|----------|-------------|
| `is_in_work_hours()` | p_user_id, p_timestamp | boolean | INVOKER | Vérifie si dans horaires de travail |
| `should_autotrack()` | p_user_id, p_timestamp | boolean | INVOKER | Doit-on auto-tracker ? |
| `get_today_work_schedules()` | p_user_id, p_date | TABLE | INVOKER | Horaires du jour |

### Synchronisation
| Function | Arguments | Returns | Security | Description |
|----------|-----------|---------|----------|-------------|
| `get_changes()` | since (timestamptz) | TABLE(entity_type, entity_id, action, data, updated_at) | DEFINER | Delta sync depuis timestamp |

### Comptes Pro & Licences
| Function | Arguments | Returns | Security | Description |
|----------|-----------|---------|----------|-------------|
| `get_license_pool()` | p_pro_account_id | TABLE | DEFINER | Liste des licences |
| `get_license_summary()` | p_pro_account_id | TABLE | DEFINER | Résumé licences (total, actives, etc.) |
| `get_linked_accounts()` | p_pro_account_id | TABLE | DEFINER | Liste des employés liés |
| `get_pro_departments()` | p_pro_account_id | jsonb | DEFINER | Départements de l'entreprise |
| `update_pro_departments()` | p_pro_account_id, p_departments | boolean | DEFINER | Maj départements |

### Company Links
| Function | Arguments | Returns | Security | Description |
|----------|-----------|---------|----------|-------------|
| `activate_company_link()` | p_token, p_user_id | json | DEFINER | Active un lien via token invitation |
| `update_company_link_preferences()` | p_link_id, p_share_* | json | DEFINER | Maj préférences de partage |
| `unlink_company()` | p_link_id | json | DEFINER | Délie un employé |
| `create_unlink_confirmation_token()` | p_company_link_id, p_initiated_by, p_initiator_email | json | DEFINER | Crée token de confirmation unlink |
| `confirm_unlink_token()` | p_token | json | DEFINER | Confirme unlink |
| `cancel_unlink_token()` | p_token | json | DEFINER | Annule unlink |

### Password Reset
| Function | Arguments | Returns | Security | Description |
|----------|-----------|---------|----------|-------------|
| `create_password_reset_token()` | p_email, p_token, p_expires_in_hours | uuid | DEFINER | Crée token reset |
| `validate_password_reset_token()` | p_token | TABLE(user_id, email, is_valid, error_message) | DEFINER | Valide token |
| `cleanup_expired_password_reset_tokens()` | - | integer | DEFINER | Nettoie tokens expirés |

### Email Verification
| Function | Arguments | Returns | Security | Description |
|----------|-----------|---------|----------|-------------|
| `create_email_verification_token()` | p_user_id, p_email | text | DEFINER | Crée token vérification |
| `verify_email_token()` | p_token | json | DEFINER | Vérifie email |

### GDPR
| Function | Arguments | Returns | Security | Description |
|----------|-----------|---------|----------|-------------|
| `get_user_consents()` | p_user_id | TABLE | DEFINER | Consentements utilisateur |
| `update_user_consent()` | p_user_id, p_consent_type, p_granted, p_consent_version, ... | boolean | DEFINER | Maj consentement |
| `initialize_default_consents()` | p_user_id, p_consent_version | void | DEFINER | Init consentements par défaut |
| `accept_privacy_policy()` | p_user_id, p_policy_version, p_policy_url, ... | boolean | DEFINER | Accepte CGU |
| `has_accepted_policy()` | p_user_id, p_policy_version | boolean | DEFINER | A accepté ? |
| `log_gdpr_action()` | p_user_id, p_action, p_details, ... | uuid | DEFINER | Log action GDPR |
| `export_user_data_json()` | p_user_id | jsonb | DEFINER | Export données JSON |
| `delete_user_data_complete()` | p_user_id, p_deletion_reason, p_cancel_stripe | jsonb | DEFINER | Suppression complète |

### Abonnements
| Function | Arguments | Returns | Security | Description |
|----------|-----------|---------|----------|-------------|
| `get_user_active_subscription()` | p_user_id | TABLE | DEFINER | Abonnement actif |

### Statistiques
| Function | Arguments | Returns | Security | Description |
|----------|-----------|---------|----------|-------------|
| `get_trip_stats()` | p_user_id, p_start_date, p_end_date | json | DEFINER | Stats trajets |

### Utilitaires
| Function | Arguments | Returns | Security | Description |
|----------|-----------|---------|----------|-------------|
| `generate_invitation_token()` | - | text | INVOKER | Génère token invitation |
| `generate_test_trips()` | p_user_id, p_vehicle_id, p_trip_type, p_month, p_year, p_num_trips, p_avg_distance_km | void | INVOKER | Génère trajets de test |

## Triggers

| Table | Trigger | Event | Function |
|-------|---------|-------|----------|
| users | create_settings_on_user_insert | AFTER INSERT | create_default_settings_for_user() |
| users | update_users_updated_at | BEFORE UPDATE | update_updated_at_column() |
| users | users_updated_at_trigger | BEFORE UPDATE | update_users_timestamp() |
| vehicles | update_vehicles_updated_at | BEFORE UPDATE | update_updated_at_column() |
| vehicles | vehicles_updated_at_trigger | BEFORE UPDATE | update_vehicles_timestamp() |
| trips | trips_updated_at_trigger | BEFORE UPDATE | update_trips_timestamp() |
| trips | update_trips_updated_at | BEFORE UPDATE | update_updated_at_column() |
| expenses_trips | trigger_update_expenses_updated_at | BEFORE UPDATE | update_expenses_updated_at() |
| work_schedules | update_work_schedules_updated_at | BEFORE UPDATE | update_updated_at_column() |
| auto_tracking_settings | update_auto_tracking_settings_updated_at | BEFORE UPDATE | update_updated_at_column() |
| pro_accounts | update_pro_accounts_updated_at | BEFORE UPDATE | update_updated_at_column() |
| pro_accounts | trigger_sync_company_name | AFTER UPDATE | sync_company_name_to_links() |
| licenses | update_licenses_updated_at | BEFORE UPDATE | update_updated_at_column() |
| licenses | on_license_change | AFTER UPDATE | sync_subscription_type() |
| licenses | on_license_insert | AFTER INSERT | sync_subscription_type_on_insert() |
| company_links | company_links_updated_at | BEFORE UPDATE | update_company_links_updated_at() |
| company_links | company_links_sync_company_name | BEFORE INSERT | sync_company_name_on_insert() |
| company_links | trigger_prevent_company_name_update | BEFORE UPDATE | prevent_company_name_update() |
| stripe_subscriptions | trigger_stripe_subscriptions_updated_at | BEFORE UPDATE | update_stripe_subscriptions_updated_at() |
| stripe_subscriptions | trigger_sync_user_subscription | AFTER INSERT/UPDATE | sync_user_subscription_cache() |
| stripe_payments | trigger_stripe_payments_updated_at | BEFORE UPDATE | update_stripe_payments_updated_at() |

## Edge Functions

| Function | JWT Required | Description |
|----------|--------------|-------------|
| `create-payment-intent` | Yes | Crée un PaymentIntent Stripe |
| `confirm-payment-intent` | Yes | Confirme un PaymentIntent |
| `stripe-webhook` | No | Webhook Stripe (events) |
| `cancel-subscription` | Yes | Annule un abonnement |
| `send-invitation` | Yes | Envoie invitation employé |
| `accept-invitation` | Yes | Accepte invitation |
| `set-initial-password` | Yes | Définit mot de passe initial |
| `request-password-reset` | Yes | Demande reset password |
| `reset-password` | Yes | Reset password avec token |
| `request-email-verification` | Yes | Demande vérification email |
| `verify-email` | Yes | Vérifie email avec token |
| `request-unlink-confirmation` | Yes | Demande confirmation unlink |
| `confirm-unlink` | Yes | Confirme unlink |
| `gdpr-export` | Yes | Export données RGPD |
| `gdpr-delete-account` | Yes | Suppression compte RGPD |
| `send-gdpr-email` | Yes | Envoie email RGPD |
| `quick-action` | Yes | Actions rapides |

## Storage Buckets

| Bucket | Public | File Size Limit | Allowed MIME Types |
|--------|--------|-----------------|-------------------|
| `receipts` | Yes | 5MB | image/jpeg, image/jpg, image/png, image/webp |
| `gdpr-exports` | No | - | - |

### Storage Policies
- **receipts**: Anyone can view, authenticated users can upload, users can delete their own
- **gdpr-exports**: Users can only download their own exports (folder = user ID)

## Extensions Installées
- `pgcrypto` - Fonctions cryptographiques
- `pg_graphql` - Support GraphQL
- `pg_stat_statements` - Stats SQL
- `plpgsql` - PL/pgSQL
- `uuid-ossp` - Génération UUID
- `supabase_vault` - Gestion secrets

---

## Package Structure
```
com.application.motium/
├── data/
│   ├── local/              # Room database, DAOs, entities
│   ├── remote/             # Supabase services, API, DTOs
│   └── repository/         # Repository implementations
├── domain/
│   ├── model/              # Domain entities
│   ├── repository/         # Repository interfaces
│   └── usecase/            # Business logic
├── presentation/
│   ├── ui/
│   │   ├── components/     # Composables réutilisables
│   │   ├── screens/        # Écrans (Screen composables)
│   │   └── theme/          # Material 3 theme
│   ├── viewmodel/          # ViewModels
│   └── navigation/         # Navigation graphs
├── service/                # Services (Location, Sync)
├── worker/                 # WorkManager workers
├── util/                   # Extensions, helpers
└── MotiumApp.kt            # Application class
```

## Commands
```bash
# Build
./gradlew assembleDebug
./gradlew assembleRelease

# Tests
./gradlew test                      # Unit tests
./gradlew testDebugUnitTest         # Debug unit tests
./gradlew connectedAndroidTest      # Instrumented tests

# Lint & Quality
./gradlew lint
./gradlew lintDebug

# Install
./gradlew installDebug

# Clean
./gradlew clean

# Room schema export (si configuré)
./gradlew kspDebugKotlin
```

## Code Conventions

### Kotlin Style
- Kotlin official conventions
- 4 espaces d'indentation
- Max 120 caractères par ligne
- Pas de wildcard imports
- Trailing commas pour multi-lignes

### Naming
| Type | Convention | Exemple |
|------|------------|---------|
| Package | lowercase | `com.application.motium.data` |
| Class | PascalCase | `TripRepository` |
| Composable | PascalCase | `TripCard`, `HomeScreen` |
| Function | camelCase | `getTrips()`, `syncData()` |
| Property | camelCase | `isLoading`, `tripList` |
| Constant | SCREAMING_SNAKE | `MAX_SYNC_INTERVAL` |
| ViewModel | Suffix `ViewModel` | `HomeViewModel` |
| UseCase | Suffix `UseCase` | `GetTripsUseCase` |
| Repository | Suffix `Repository` | `TripRepository` |
| DAO | Suffix `Dao` | `TripDao` |
| Entity (Room) | Suffix `Entity` | `TripEntity` |
| DTO | Suffix `Dto` | `TripDto` |

### Compose Guidelines
```kotlin
// ✅ Bon - Stateless, modifier en premier param optionnel
@Composable
fun TripCard(
    trip: Trip,
    onTripClick: (Trip) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        // ...
    }
}

// ✅ Preview avec showBackground
@Preview(showBackground = true)
@Composable
private fun TripCardPreview() {
    MotiumTheme {
        TripCard(trip = previewTrip, onTripClick = {})
    }
}
```

### Null Safety
```kotlin
// ❌ Jamais
val name = user!!.name

// ✅ Préférer
val name = user?.name ?: "Unknown"
user?.let { processUser(it) }
```

### Coroutines & Flow
```kotlin
// ViewModel - utiliser viewModelScope
fun loadTrips() {
    viewModelScope.launch {
        tripRepository.getTrips()
            .catch { e -> _uiState.update { it.copy(error = e.message) } }
            .collect { trips -> _uiState.update { it.copy(trips = trips) } }
    }
}

// Repository - utiliser Dispatchers.IO pour I/O
suspend fun syncTrips() = withContext(Dispatchers.IO) {
    // ...
}
```

### Room Database
```kotlin
// Entity
@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey val id: String,
    val startTime: Long,
    val endTime: Long?,
    @ColumnInfo(name = "is_synced") val isSynced: Boolean = false,
)

// DAO - Flow pour les queries réactives
@Dao
interface TripDao {
    @Query("SELECT * FROM trips ORDER BY startTime DESC")
    fun getAllTrips(): Flow<List<TripEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrip(trip: TripEntity)
}
```

### Supabase
```kotlin
// Utiliser les clients depuis BuildConfig
val supabase = createSupabaseClient(
    supabaseUrl = BuildConfig.SUPABASE_URL,
    supabaseKey = BuildConfig.SUPABASE_ANON_KEY
) {
    install(Auth)
    install(Postgrest)
    install(Realtime)
    install(Storage)
}

// Queries Postgrest
suspend fun getTrips(): List<TripDto> {
    return supabase.postgrest["trips"]
        .select()
        .decodeList()
}
```

## Testing

### Naming Convention
```kotlin
// Format: should_expectedBehavior_when_condition
@Test
fun should_returnTrips_when_databaseHasData() = runTest {
    // Given
    val trips = listOf(createTestTrip())
    coEvery { tripDao.getAllTrips() } returns flowOf(trips)

    // When
    val result = repository.getTrips().first()

    // Then
    assertEquals(trips, result)
}
```

### Test Dependencies
- **Unit tests**: JUnit, MockK, Turbine (Flow testing), Robolectric
- **Instrumented**: Espresso, MockK-Android, Compose UI Testing

## Git Workflow

### Commit Format
```
type(scope): description

Types: feat, fix, docs, style, refactor, test, chore, perf
```

**Exemples:**
```
feat(trip): add trip recording with location tracking
fix(ocr): handle camera permission denial gracefully
refactor(auth): migrate to Supabase Auth
test(repository): add unit tests for TripRepository
```

### Branches
- `main` - Production stable
- `develop` - Développement
- `feature/*` - Nouvelles features
- `bugfix/*` - Corrections de bugs
- `hotfix/*` - Fixes urgents

## Security

### Clés API
⚠️ **ATTENTION** : Les clés Supabase sont actuellement en dur dans `build.gradle.kts`.

**TODO** : Migrer vers `local.properties` :
```properties
# local.properties (NE PAS COMMIT)
SUPABASE_URL=https://api.motium.app
SUPABASE_ANON_KEY=eyJ...
```

```kotlin
// build.gradle.kts
val localProperties = Properties().apply {
    load(rootProject.file("local.properties").inputStream())
}
buildConfigField("String", "SUPABASE_URL", "\"${localProperties["SUPABASE_URL"]}\"")
```

### Données sensibles
- Utiliser `EncryptedSharedPreferences` pour les tokens
- Ne jamais logger les tokens/clés
- Valider les inputs utilisateur

## Permissions Android
```xml
<!-- Déjà utilisées ou à prévoir -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

## Known Issues & TODOs
- [ ] Hilt désactivé - activer quand l'architecture sera stabilisée
- [ ] Clés Supabase en dur dans build.gradle.kts (à sécuriser)
- [ ] Configurer ProGuard pour release (`isMinifyEnabled = false`)

## Important Files
| Fichier | Description |
|---------|-------------|
| `app/build.gradle.kts` | Dépendances et config |
| `gradle/libs.versions.toml` | Version catalog |
| `local.properties` | Clés API (gitignored) |
| `app/src/main/AndroidManifest.xml` | Permissions, components |

## Do Not Modify Without Review
- Database migrations Room (créer une nouvelle migration)
- `BuildConfig` fields (impact sur tout le projet)
- **Schemas Supabase** (voir règle d'or ci-dessus)

## Useful Resources
- [Supabase Kotlin Docs](https://supabase.com/docs/reference/kotlin/introduction)
- [Jetpack Compose](https://developer.android.com/jetpack/compose)
- [Room Database](https://developer.android.com/training/data-storage/room)
- [OSMDroid Wiki](https://github.com/osmdroid/osmdroid/wiki)
- [ML Kit Text Recognition](https://developers.google.com/ml-kit/vision/text-recognition/android)
- [Accompanist Permissions](https://google.github.io/accompanist/permissions/)
