# Motium - Project Context
> Last updated: 2025-12-24 | Updated by: Claude

## Quick Summary

Motium is a **professional mileage tracking application** for Android that automatically records trips using GPS and activity recognition. It calculates tax-compliant reimbursements (French "indemnites kilometriques"), manages expenses with OCR receipt scanning, and syncs data to Supabase. The app supports both **Individual users** (freelancers, employees) and **Enterprise/Pro accounts** (companies managing multiple employees with a license pool system).

Key value propositions:
- **Automatic trip detection** via Activity Recognition API (vehicle/bicycle detection)
- **French tax compliance** with progressive mileage brackets and work-home trip caps
- **Offline-first architecture** with seamless cloud sync
- **Pro/Enterprise features** for fleet management and multi-employee export

---

## Tech Stack

| Category | Technology | Version/Details |
|----------|------------|-----------------|
| **Language** | Kotlin | 1.9.x |
| **Min SDK** | 31 | Android 12 |
| **Target SDK** | 34 | Android 14 |
| **Compile SDK** | 36 | Android 15 |
| **UI Framework** | Jetpack Compose | BOM 2024.02.00 |
| **Design System** | Material 3 | Dynamic theming |
| **Architecture** | MVVM | Clean Architecture layers |
| **Local Database** | Room | 2.8.3 + KSP |
| **Backend** | Supabase | Postgrest, Auth, Realtime, Storage |
| **Auth** | Supabase Auth | + Google Sign-In, Phone OTP |
| **Maps** | MapLibre | 11.8.4 (vector tiles, self-hosted) |
| **Location** | Play Services Location | 21.0.1 |
| **Payments** | Stripe Android | 22.0.0 |
| **PDF Export** | iTextPDF | 7.2.5 |
| **Excel Export** | Apache POI | 5.2.5 |
| **OCR** | ML Kit Text Recognition | 16.0.1 |
| **Images** | Coil | 2.5.0 |
| **Networking** | Retrofit + Moshi + OkHttp | 2.9.0 / 1.15.0 / 4.12.0 |
| **Async** | Coroutines + Flow | 1.7.3 |
| **Background** | WorkManager | 2.9.0 |
| **Security** | EncryptedSharedPreferences | 1.1.0-alpha06 |
| **Credentials** | AndroidX Credentials | 1.3.0 (Samsung Pass, Google) |

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                        PRESENTATION LAYER                        │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐  │
│  │   Screens   │  │  ViewModels │  │  Navigation (Compose)   │  │
│  │  (Compose)  │  │ (StateFlow) │  │  MotiumNavHost.kt       │  │
│  └─────────────┘  └─────────────┘  └─────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                         DOMAIN LAYER                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐  │
│  │   Models    │  │ Repositories│  │      Use Cases          │  │
│  │ (Trip,User) │  │ (Interfaces)│  │ (Business Logic)        │  │
│  └─────────────┘  └─────────────┘  └─────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                          DATA LAYER                              │
│  ┌─────────────────────┐       ┌─────────────────────────────┐  │
│  │   LOCAL (Room DB)   │       │    REMOTE (Supabase)        │  │
│  │  ├─ Entities        │       │  ├─ SupabaseAuthRepository  │  │
│  │  ├─ DAOs            │       │  ├─ SupabaseTripRepository  │  │
│  │  └─ TypeConverters  │       │  ├─ LicenseRepository       │  │
│  │                     │       │  └─ Edge Functions          │  │
│  └─────────────────────┘       └─────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                       SERVICES LAYER                             │
│  ┌───────────────────┐  ┌───────────────────┐  ┌─────────────┐  │
│  │ LocationTracking  │  │ ActivityRecognition│  │  SyncManager│  │
│  │    Service        │  │     Service        │  │             │  │
│  └───────────────────┘  └───────────────────┘  └─────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    OFFLINE-FIRST SYNC LAYER                      │
│  ┌───────────────────┐  ┌───────────────────┐  ┌─────────────┐  │
│  │OfflineFirstSync   │  │ DeltaSyncWorker   │  │PendingOp    │  │
│  │    Manager        │  │  (WorkManager)    │  │   Queue     │  │
│  └───────────────────┘  └───────────────────┘  └─────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

### Key Architectural Decisions

1. **Offline-First with Delta Sync**: UI reads ONLY from Room (source of truth), writes queue operations for background sync via WorkManager
2. **Delta Sync Pattern**: Only changes since `lastSyncTimestamp` are synchronized, using `syncStatus`, `localUpdatedAt`, `serverUpdatedAt`, and `version` fields
3. **Pending Operations Queue**: Room-based `PendingOperationEntity` replaces SharedPreferences-based sync queue, with exponential backoff retry
4. **State Machine for Tracking**: `STANDBY → BUFFERING → TRIP_ACTIVE → FINALIZING`
5. **Two User Types**: INDIVIDUAL (personal use) vs ENTERPRISE (company with licenses)
6. **Anti-Disconnection**: Network errors never cause logout - only permanent auth errors (401/400) trigger sign out

---

## Database Schema

### Room Database (Local) - Version 15

#### users
| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| id | String | PK | User UUID |
| name | String | NOT NULL | Display name |
| email | String | NOT NULL | User email |
| role | String | NOT NULL | `INDIVIDUAL` or `ENTERPRISE` |
| subscriptionType | String | NOT NULL | `TRIAL`, `EXPIRED`, `PREMIUM`, `LIFETIME` |
| subscriptionExpiresAt | String? | | ISO-8601 timestamp |
| trialStartedAt | String? | | Trial start date |
| trialEndsAt | String? | | Trial end date (7 days after start) |
| stripeCustomerId | String? | | Stripe customer ID |
| stripeSubscriptionId | String? | | Stripe subscription ID |
| phoneNumber | String | | Phone number |
| address | String | | User address |
| phoneVerified | Boolean | default false | Phone verification status |
| verifiedPhone | String? | | Verified phone number |
| deviceFingerprintId | String? | | Anti-fraud device ID |
| considerFullDistance | Boolean | default false | Tax: use full distance for work-home |
| favoriteColors | String | | JSON array of hex colors |
| createdAt | String | NOT NULL | ISO-8601 |
| updatedAt | String | NOT NULL | ISO-8601 |
| lastSyncedAt | Long? | | Sync timestamp |
| isLocallyConnected | Boolean | | Logged in locally |
| syncStatus | String | default SYNCED | `SYNCED`, `PENDING_UPLOAD`, `PENDING_DELETE`, `CONFLICT`, `ERROR` |
| localUpdatedAt | Long | | Local modification timestamp |
| serverUpdatedAt | Long? | | Server's updated_at |
| version | Int | default 1 | Optimistic locking version |

#### trips
| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| id | String | PK | Trip UUID |
| userId | String | FK | Owner user ID |
| vehicleId | String? | FK | Vehicle used |
| startTime | Long | NOT NULL | Epoch millis |
| endTime | Long? | | Epoch millis |
| locations | List<TripLocation> | TypeConverter | GPS trace as JSON |
| totalDistance | Double | NOT NULL | Distance in meters |
| startAddress | String? | | Geocoded start |
| endAddress | String? | | Geocoded end |
| notes | String? | | User notes |
| tripType | String? | | `PROFESSIONAL` or `PERSONAL` |
| isValidated | Boolean | default false | User validated |
| reimbursementAmount | Double? | | Cached calculation |
| isWorkHomeTrip | Boolean | default false | Work-home commute |
| matchedRouteCoordinates | String? | | OSRM map-matched route cache |
| createdAt | Long | NOT NULL | Creation timestamp |
| updatedAt | Long | NOT NULL | Last update |
| lastSyncedAt | Long? | | Sync timestamp |
| needsSync | Boolean | default true | Dirty flag |
| syncStatus | String | default SYNCED | Sync status enum |
| localUpdatedAt | Long | | Local modification timestamp |
| serverUpdatedAt | Long? | | Server's updated_at |
| version | Int | default 1 | Optimistic locking version |
| deletedAt | Long? | | Soft delete timestamp |

#### vehicles
| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| id | String | PK | Vehicle UUID |
| userId | String | FK | Owner user ID |
| name | String | NOT NULL | Vehicle name |
| type | String | NOT NULL | `CAR`, `MOTORCYCLE`, `SCOOTER`, `BIKE` |
| licensePlate | String? | | License plate |
| power | String? | | `3CV`, `4CV`, `5CV`, `6CV`, `7CV+` |
| fuelType | String? | | `GASOLINE`, `DIESEL`, `ELECTRIC`, `HYBRID`, `OTHER` |
| mileageRate | Double | NOT NULL | €/km rate |
| isDefault | Boolean | default false | Default vehicle |
| totalMileagePerso | Double | default 0 | Personal km (annual) |
| totalMileagePro | Double | default 0 | Professional km (annual) |
| createdAt | String | NOT NULL | ISO-8601 |
| updatedAt | String | NOT NULL | ISO-8601 |
| lastSyncedAt | Long? | | Sync timestamp |
| needsSync | Boolean | default true | Dirty flag |
| syncStatus | String | default SYNCED | Sync status enum |
| localUpdatedAt | Long | | Local modification timestamp |
| serverUpdatedAt | Long? | | Server's updated_at |
| version | Int | default 1 | Optimistic locking version |
| deletedAt | Long? | | Soft delete timestamp |

#### expenses
| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| id | String | PK | Expense UUID |
| userId | String | FK | Owner user ID |
| date | String | NOT NULL | YYYY-MM-DD format |
| type | String | NOT NULL | `FUEL`, `HOTEL`, `TOLL`, `PARKING`, `RESTAURANT`, `MEAL_OUT`, `OTHER` |
| amount | Double | NOT NULL | Amount TTC (with tax) |
| amountHT | Double? | | Amount HT (without tax) |
| note | String | | Description |
| photoUri | String? | | Receipt photo URI |
| createdAt | String | NOT NULL | ISO-8601 |
| updatedAt | String | NOT NULL | ISO-8601 |
| lastSyncedAt | Long? | | Sync timestamp |
| needsSync | Boolean | default true | Dirty flag |
| syncStatus | String | default SYNCED | Sync status enum |
| localUpdatedAt | Long | | Local modification timestamp |
| serverUpdatedAt | Long? | | Server's updated_at |
| version | Int | default 1 | Optimistic locking version |
| deletedAt | Long? | | Soft delete timestamp |

#### pending_operations (NEW - Delta Sync Queue)
| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| id | String | PK | Operation UUID |
| entityType | String | NOT NULL, INDEX | `TRIP`, `VEHICLE`, `EXPENSE`, `USER` |
| entityId | String | NOT NULL, INDEX | Entity UUID |
| action | String | NOT NULL | `CREATE`, `UPDATE`, `DELETE` |
| payload | String? | | Serialized JSON (optional) |
| createdAt | Long | NOT NULL, INDEX | Creation timestamp |
| retryCount | Int | default 0 | Retry attempts |
| lastAttemptAt | Long? | | Last retry timestamp |
| lastError | String? | | Last error message |
| priority | Int | default 0 | Higher = more urgent |

#### sync_metadata (NEW - Delta Sync Timestamps)
| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| entityType | String | PK | `TRIP`, `VEHICLE`, `EXPENSE` |
| lastSyncTimestamp | Long | NOT NULL | Last successful delta sync |
| lastFullSyncTimestamp | Long | NOT NULL | Last full sync |
| syncInProgress | Boolean | default false | Sync lock flag |
| totalSynced | Int | default 0 | Total records synced |
| lastSyncError | String? | | Last sync error |

#### work_schedules
| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| id | String | PK | Schedule UUID |
| userId | String | FK | Owner user ID |
| dayOfWeek | Int | NOT NULL | 1-7 (ISO 8601: Monday=1) |
| startHour | Int | NOT NULL | 0-23 |
| startMinute | Int | NOT NULL | 0-59 |
| endHour | Int | NOT NULL | 0-23 |
| endMinute | Int | NOT NULL | 0-59 |
| isOvernight | Boolean | default false | Spans midnight |
| isActive | Boolean | default true | Enabled |
| createdAt | String | NOT NULL | ISO-8601 |
| updatedAt | String | NOT NULL | ISO-8601 |
| lastSyncedAt | Long? | | Sync timestamp |
| needsSync | Boolean | default true | Dirty flag |

#### auto_tracking_settings
| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| id | String | PK | Settings UUID |
| userId | String | FK | Owner user ID |
| trackingMode | String | NOT NULL | `ALWAYS`, `WORK_HOURS_ONLY`, `DISABLED` |
| minTripDistanceMeters | Int | default 100 | Minimum trip distance |
| minTripDurationSeconds | Int | default 60 | Minimum trip duration |
| createdAt | String | NOT NULL | ISO-8601 |
| updatedAt | String | NOT NULL | ISO-8601 |
| lastSyncedAt | Long? | | Sync timestamp |
| needsSync | Boolean | default true | Dirty flag |

#### company_links
| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| id | String | PK | Link UUID |
| userId | String | FK, INDEX | Individual user ID |
| linkedProAccountId | String | FK, INDEX | Pro account ID |
| companyName | String | NOT NULL | Company display name |
| department | String? | | Department/service |
| status | String | NOT NULL | `PENDING`, `ACTIVE`, `INACTIVE`, `REVOKED` |
| shareProfessionalTrips | Boolean | default true | Share pro trips |
| sharePersonalTrips | Boolean | default false | Share personal trips |
| sharePersonalInfo | Boolean | default true | Share profile info |
| shareExpenses | Boolean | default false | Share expenses |
| invitationToken | String? | | Invitation deep link token |
| linkedAt | String? | | When linked |
| linkedActivatedAt | String? | | When user accepted |
| unlinkedAt | String? | | When unlinked |
| createdAt | String | NOT NULL | ISO-8601 |
| updatedAt | String | NOT NULL | ISO-8601 |
| lastSyncedAt | Long? | | Sync timestamp |
| needsSync | Boolean | default true | Dirty flag |

### Supabase Tables (Remote)

Mirrors Room schema with additional tables:

#### pro_accounts
| Column | Type | Description |
|--------|------|-------------|
| id | UUID | PK |
| user_id | UUID | FK to users (owner) |
| company_name | VARCHAR(255) | Company name |
| siret | VARCHAR(14) | French SIRET number |
| vat_number | VARCHAR(20) | VAT number (FR + 11 digits) |
| legal_form | VARCHAR(50) | `AUTO_ENTREPRENEUR`, `SARL`, `SAS`, etc. |
| billing_address | TEXT | Billing address |
| billing_email | VARCHAR(255) | Invoice email |
| billing_day | INT | Day of month for billing (1-28) |
| departments | TEXT[] | Array of department names |
| stripe_subscription_id | TEXT | Stripe subscription |
| created_at | TIMESTAMPTZ | |
| updated_at | TIMESTAMPTZ | |

#### licenses
| Column | Type | Description |
|--------|------|-------------|
| id | UUID | PK |
| pro_account_id | UUID | FK to pro_accounts |
| linked_account_id | UUID? | FK to company_links (null = in pool) |
| is_lifetime | BOOLEAN | One-time vs monthly |
| price_monthly_ht | DECIMAL | €5.00 default |
| vat_rate | DECIMAL | 0.20 (20%) |
| status | VARCHAR | `PENDING`, `ACTIVE`, `EXPIRED`, `CANCELLED` |
| start_date | TIMESTAMPTZ | License start |
| end_date | TIMESTAMPTZ? | License end (null for lifetime) |
| unlink_requested_at | TIMESTAMPTZ? | 30-day notice start |
| unlink_effective_at | TIMESTAMPTZ? | When unlink takes effect |
| billing_starts_at | TIMESTAMPTZ? | First billing date |
| stripe_subscription_id | TEXT | Stripe sub ID |
| stripe_subscription_item_id | TEXT | Stripe item ID |
| stripe_price_id | TEXT | Stripe price ID |
| created_at | TIMESTAMPTZ | |
| updated_at | TIMESTAMPTZ | |

#### stripe_subscriptions
| Column | Type | Description |
|--------|------|-------------|
| id | UUID | PK |
| user_id | UUID? | For individual subscriptions |
| pro_account_id | UUID? | For pro subscriptions |
| stripe_subscription_id | TEXT | Stripe ID |
| stripe_customer_id | TEXT | Stripe customer |
| subscription_type | VARCHAR | `individual_monthly`, `individual_lifetime`, `pro_license_monthly`, `pro_license_lifetime` |
| status | VARCHAR | `incomplete`, `active`, `past_due`, `canceled`, `unpaid` |
| current_period_start | TIMESTAMPTZ | |
| current_period_end | TIMESTAMPTZ | |
| cancel_at_period_end | BOOLEAN | |
| metadata | JSONB | Extra data |
| created_at | TIMESTAMPTZ | |
| updated_at | TIMESTAMPTZ | |

#### stripe_payments
| Column | Type | Description |
|--------|------|-------------|
| id | UUID | PK |
| user_id | UUID? | |
| pro_account_id | UUID? | |
| stripe_payment_intent_id | TEXT | |
| stripe_invoice_id | TEXT? | |
| payment_type | VARCHAR | `subscription_payment`, `one_time_payment` |
| amount_cents | INT | |
| currency | VARCHAR(3) | `eur` |
| status | VARCHAR | `pending`, `succeeded`, `failed`, `refunded` |
| invoice_pdf_url | TEXT? | |
| period_start | TIMESTAMPTZ? | |
| period_end | TIMESTAMPTZ? | |
| created_at | TIMESTAMPTZ | |
| paid_at | TIMESTAMPTZ? | |

#### user_consents (GDPR)
| Column | Type | Description |
|--------|------|-------------|
| id | UUID | PK |
| user_id | UUID | FK |
| consent_type | VARCHAR | `location_tracking`, `data_collection`, `company_data_sharing`, `analytics`, `marketing` |
| granted | BOOLEAN | |
| granted_at | TIMESTAMPTZ? | |
| revoked_at | TIMESTAMPTZ? | |
| consent_version | VARCHAR | |
| ip_address | INET? | |
| user_agent | TEXT? | |
| created_at | TIMESTAMPTZ | |
| **UNIQUE** | (user_id, consent_type) | |

#### gdpr_data_requests
| Column | Type | Description |
|--------|------|-------------|
| id | UUID | PK |
| user_id | UUID | FK |
| request_type | VARCHAR | `data_export`, `data_deletion`, `data_rectification` |
| status | VARCHAR | `pending`, `processing`, `completed`, `failed` |
| requested_at | TIMESTAMPTZ | |
| completed_at | TIMESTAMPTZ? | |
| export_file_url | TEXT? | |
| deletion_reason | TEXT? | |
| error_message | TEXT? | |
| created_at | TIMESTAMPTZ | |

---

## API Routes / Endpoints

### Supabase Edge Functions

| Function | Method | Auth | Description |
|----------|--------|------|-------------|
| `create-payment-intent` | POST | User | Create Stripe PaymentIntent for subscription |
| `confirm-payment-intent` | POST | User | Confirm deferred payment |
| `stripe-webhook` | POST | Stripe | Handle Stripe webhook events |
| `gdpr-export` | POST | User | Export all user data (GDPR Art. 15) |
| `gdpr-delete-account` | POST | User | Delete account (GDPR Art. 17) |
| `send-gdpr-email` | POST | System | Send GDPR notification emails |
| `request-password-reset` | POST | Public | Send password reset email |
| `reset-password` | POST | Public | Complete password reset |

### create-payment-intent
```typescript
// Input
{
  userId: string,
  proAccountId?: string,
  email: string,
  priceType: 'individual_monthly' | 'individual_lifetime' | 'pro_license_monthly' | 'pro_license_lifetime',
  quantity?: number // For pro licenses
}

// Output
{
  clientSecret: string,
  customerId: string,
  ephemeralKey: string
}
```

### stripe-webhook
Handles events:
- `payment_intent.succeeded` → Update subscription status
- `invoice.paid` → Record payment
- `invoice.payment_failed` → Mark subscription as past_due
- `customer.subscription.created/updated/deleted` → Sync subscription state

### gdpr-export
```typescript
// Output: JSON containing all user data
{
  profile: User,
  trips: Trip[],
  vehicles: Vehicle[],
  expenses: Expense[],
  workSchedules: WorkSchedule[],
  companyLinks: CompanyLink[],
  consents: Consent[],
  payments: Payment[]
}
```

### gdpr-delete-account
```typescript
// Input
{
  confirmation: "DELETE_MY_ACCOUNT", // Required exact string
  reason?: string
}

// Process
1. Export summary for audit
2. Cancel Stripe subscriptions
3. Delete all user data (cascading)
4. Anonymize payment records (6-year retention)
5. Create audit log entry
```

---

## Authentication & Authorization

### Auth Flow

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   LOGIN     │────▶│   PHONE     │────▶│   HOME      │
│   SCREEN    │     │ VERIFICATION│     │   SCREEN    │
└─────────────┘     └─────────────┘     └─────────────┘
       │                                       │
       │ Google Sign-In                        │
       ▼                                       ▼
┌─────────────┐                        ┌─────────────┐
│  REGISTER   │                        │   TRIAL     │
│   SCREEN    │                        │  EXPIRED    │
└─────────────┘                        └─────────────┘
```

### Authentication Methods
1. **Email + Password** (Supabase Auth)
2. **Google Sign-In** (OAuth2)
3. **Phone OTP** (Registration verification only)

### Session Management
- **Access Token**: 1 hour expiration, auto-refresh
- **Refresh Token**: Long-lived, stored in EncryptedSharedPreferences
- **Offline Support**: Room DB stores user for immediate UI, background refresh

### User Roles
| Role | Description | Features |
|------|-------------|----------|
| `INDIVIDUAL` | Personal use | Trip tracking, export, single user |
| `ENTERPRISE` | Company admin | All individual + license management, employee linking, multi-export |

### Subscription Types
| Type | Duration | Price | Features |
|------|----------|-------|----------|
| `TRIAL` | 7 days | Free | Full access |
| `EXPIRED` | - | - | Read-only, no new trips |
| `PREMIUM` | Monthly | €4.99/month | Full access |
| `LIFETIME` | Forever | €120 one-time | Full access |

### Authorization Rules (RLS)
- Users can only access their own data (`user_id = auth.uid()`)
- Pro account owners can view linked users' shared data
- Linked users control sharing via `company_links` preferences

---

## Screens & Navigation

### Navigation Structure

```
AUTH FLOW                           INDIVIDUAL FLOW
──────────                          ───────────────
splash ─┬─▶ login                   home ◀─────────────────────┐
        │     │                       │                         │
        │     ├─▶ register            ├─▶ trip_details/{id}     │
        │     │     │                 │     └─▶ edit_trip/{id}  │
        │     │     └─▶ phone_verify  ├─▶ add_trip              │
        │     │                       ├─▶ add_expense/{date}    │
        │     ├─▶ forgot_password     │                         │
        │     │     └─▶ reset_pass    ├─▶ calendar ─────────────┤
        │     │                       │     └─▶ planning        │
        │     └─▶ [Google Auth]       ├─▶ vehicles              │
        │                             ├─▶ export                │
        └─▶ trial_expired             └─▶ settings              │
              └─▶ upgrade ────────────────────────────────────▶─┘

PRO/ENTERPRISE FLOW
───────────────────
enterprise_home ◀──────────────────────────────────┐
    │                                               │
    ├─▶ pro_linked_accounts                         │
    │     ├─▶ pro_account_details/{id}              │
    │     └─▶ pro_user_trips/{userId}               │
    │           └─▶ pro_trip_details/{tripId}       │
    │                                               │
    ├─▶ pro_licenses                                │
    │     ├─▶ [PurchaseLicenseDialog]               │
    │     ├─▶ [AssignLicenseDialog]                 │
    │     └─▶ [UnlinkConfirmDialog]                 │
    │                                               │
    ├─▶ pro_export_advanced (multi-account)         │
    │                                               │
    ├─▶ pro_calendar / pro_vehicles / pro_settings ─┤
    │                                               │
    └─▶ pro_trial_expired (no license assigned) ────┘
```

### Screen Details

#### HomeScreen (NewHomeScreen.kt)
**Route**: `home` | `enterprise_home`
**Purpose**: Main dashboard with trip list and stats

**Sections**:
1. **Stats Header**: Today's distance/reimbursement, monthly totals
2. **Tracking Mode Dropdown**: AUTO/MANUAL selection
3. **Quick Actions**: Add Trip, Add Expense, Planning
4. **Trips List**: Paginated, grouped by date, pull-to-refresh
5. **Trial Banner**: Days remaining (if applicable)

**User Actions**:
| Element | Action | Result |
|---------|--------|--------|
| Trip Card | Tap | Navigate to TripDetailsScreen |
| Add Trip Button | Tap | Navigate to AddTripScreen |
| Add Expense Button | Tap | Navigate to AddExpenseScreen with date |
| Tracking Dropdown | Select | Change tracking mode |
| Pull down | Swipe | Refresh trips from Supabase |

#### AddTripScreen
**Route**: `add_trip`
**Purpose**: Create new trip manually

**Sections**:
1. **Location Input**: Start/end address autocomplete (Nominatim)
2. **Route Preview**: MiniMap with calculated route
3. **Date/Time Pickers**: Start and end time
4. **Trip Type**: Professional/Personal toggle, Work-home checkbox
5. **Vehicle Selector**: Dropdown with user's vehicles
6. **Expenses**: Dynamic list with photo capture
7. **Notes**: Free text

**Validation**:
- Start location required
- End location required
- Distance > 0
- Duration > 0

#### TripDetailsScreen
**Route**: `trip_details/{tripId}` | `pro_trip_details/{tripId}/{linkedUserId?}`
**Purpose**: View full trip details with map

**Sections**:
1. **Header**: Date, type badge, distance, duration, reimbursement
2. **Map**: Full route with GPS trace, snap-to-road
3. **Addresses**: Start and end locations
4. **Vehicle**: Vehicle used
5. **Expenses**: Associated expenses with photos
6. **Notes**: Trip notes
7. **Actions**: Edit, Delete, Validate

#### CalendarScreen
**Route**: `calendar` | `pro_calendar`
**Purpose**: Calendar view of trips and work schedule planning

**Tabs**:
1. **Calendar Tab**: Month grid, days with trips highlighted, tap to see trips
2. **Planning Tab**: Work schedule configuration per day

#### VehiclesScreen
**Route**: `vehicles` | `pro_vehicles`
**Purpose**: Manage vehicles

**Features**:
- List all vehicles with type, power, default badge
- Add vehicle (FAB)
- Edit/Delete vehicle
- Set default vehicle

#### ExportScreen
**Route**: `export` | `pro_export`
**Purpose**: Export trip data

**Options**:
- Quick Export: Last month, current month, all
- Custom Filters: Date range, vehicles, trip types
- Formats: PDF, CSV, Excel
- Statistics preview before export

#### ProExportAdvancedScreen
**Route**: `pro_export_advanced`
**Purpose**: Multi-account export for Pro users

**Additional Features**:
- Select multiple linked accounts
- "Select All" checkbox
- Combined statistics

#### SettingsScreen
**Route**: `settings` | `pro_settings`
**Purpose**: User profile and app settings

**Sections**:
1. **Profile Card**: Name, email, phone, address (editable)
2. **Subscription**: Current plan, manage button
3. **Company Links**: Linked companies with sharing preferences
4. **Work Schedule**: Auto-tracking configuration
5. **Trip Settings**: Consider full distance toggle
6. **GDPR**: Export data, Delete account
7. **Support**: Privacy policy, terms, contact
8. **Logout Button**

#### LicensesScreen (Pro)
**Route**: `pro_licenses`
**Purpose**: License pool management

**Sections**:
1. **Summary Card**: Total, active, available counts
2. **License List**: Each license with user assignment, status
3. **Actions**: Purchase, Assign, Unassign, Cancel

**Dialogs**:
- `PurchaseLicenseDialog`: Quantity input, Stripe payment
- `AssignLicenseDialog`: Select user from linked accounts
- `UnlinkConfirmDialog`: 30-day notice warning

#### LinkedAccountsScreen (Pro)
**Route**: `pro_linked_accounts`
**Purpose**: Manage linked employees

**Features**:
- List linked users with status badges
- Tap to view account details
- View user's trips
- Invite new user (generates deep link)

#### TrialExpiredScreen
**Route**: `trial_expired`
**Purpose**: Blocking screen when trial ends

**Options**:
- Monthly plan: €4.99/month
- Lifetime plan: €120 one-time
- Logout button

#### ProTrialExpiredScreen
**Route**: `pro_trial_expired`
**Purpose**: Blocking screen when Pro user has no license

**Message**: Owner must assign license to themselves
**Actions**: Go to Licenses, Purchase license

---

## User Flows

### Flow: Trip Recording (Automatic)

```
1. USER grants location + activity recognition permissions
2. ActivityRecognitionService detects IN_VEHICLE transition
3. LocationTrackingService starts BUFFERING state
   └─ GPS updates every 4 seconds
4. USER sees notification "Trip detected - Tap to confirm"
5. USER confirms vehicle selection
6. State changes to TRIP_ACTIVE
7. GPS continues recording trace
8. ActivityRecognitionService detects WALKING or STILL
9. State changes to STOP_PENDING (3 min stillness detection)
10. If no movement for 3 minutes:
    └─ State changes to FINALIZING
11. Trip saved to Room DB
12. Sync attempted to Supabase (async)
13. USER sees trip in HomeScreen list
```

### Flow: Trip Recording (Manual)

```
1. USER taps "Add Trip" on HomeScreen
2. Navigate to AddTripScreen
3. USER enters start address (autocomplete via Nominatim)
4. USER enters end address
5. Route calculated (OSRM API or Haversine fallback)
6. Distance displayed on MiniMap
7. USER selects date/time
8. USER selects vehicle
9. USER toggles Professional/Personal
10. USER optionally adds expenses (with photos)
11. USER taps "Save"
12. Trip saved to Room with needsSync=true
13. Sync to Supabase (async)
14. Navigate back to HomeScreen
```

### Flow: Company Invitation (Pro → Individual)

```
PRO USER:
1. Navigate to LinkedAccountsScreen
2. Tap "Invite Person"
3. Enter employee email
4. Select sharing permissions
5. System generates invitation token
6. Email sent with deep link: https://motium.app/link?token=xxx

INDIVIDUAL USER:
7. Opens email, taps link
8. App opens via deep link handler
9. If authenticated: Navigate to Settings with pending token
10. LinkActivationDialog shows company name
11. USER reviews sharing permissions
12. USER taps "Accept"
13. CompanyLink created with status=ACTIVE
14. Pro user can now see linked user's trips
```

### Flow: Subscription Purchase

```
1. USER on TrialExpiredScreen (or UpgradeScreen)
2. USER selects plan (Monthly or Lifetime)
3. StripePaymentSheet opens
4. USER enters payment details
5. Payment processed via create-payment-intent function
6. stripe-webhook receives payment_intent.succeeded
7. User record updated: subscriptionType = PREMIUM/LIFETIME
8. App polls for profile update (5 attempts, 2s delay)
9. On success: Navigate to HomeScreen
10. Full access restored
```

### Flow: GDPR Data Export

```
1. USER in SettingsScreen
2. Tap "Export My Data"
3. DataExportDialog opens
4. USER enters password for confirmation
5. USER selects format (JSON/CSV/PDF)
6. Request sent to gdpr-export Edge Function
7. Function aggregates all user data
8. ZIP file generated with all data
9. Download link provided (valid 30 days)
10. Audit log entry created
```

### Flow: GDPR Account Deletion

```
1. USER in SettingsScreen
2. Tap "Delete Account"
3. DeleteAccountDialog opens
4. USER reads warning about data loss
5. USER checks acknowledgment checkbox
6. USER types "DELETE" to confirm
7. USER enters password
8. Request sent to gdpr-delete-account
9. Function:
   a. Exports summary for audit
   b. Cancels Stripe subscriptions
   c. Deletes all user data (cascading)
   d. Anonymizes payment records
   e. Creates audit log
10. App clears local data
11. USER logged out
12. Navigate to LoginScreen
```

---

## Design System

### Colors

#### Primary Palette
| Name | Light | Dark | Usage |
|------|-------|------|-------|
| Primary | `#16a34a` | `#22c55e` | Buttons, links, accents |
| Primary Variant | `#15803d` | `#16a34a` | Pressed states |
| On Primary | `#FFFFFF` | `#FFFFFF` | Text on primary |

#### Background/Surface
| Name | Light | Dark | Usage |
|------|-------|------|-------|
| Background | `#f6f7f8` | `#101922` | Screen background |
| Surface | `#FFFFFF` | `#1a2332` | Cards, dialogs |
| Surface Variant | `#f1f5f9` | `#1e293b` | Alternate surfaces |

#### Text
| Name | Light | Dark | Usage |
|------|-------|------|-------|
| On Background | `#101922` | `#f6f7f8` | Primary text |
| On Surface | `#101922` | `#f6f7f8` | Card text |
| Secondary | `#64748b` | `#94a3b8` | Subtle text |

#### Semantic Colors
| Name | Value | Usage |
|------|-------|-------|
| Success | `#16a34a` | Validated, confirmed |
| Warning | `#f59e0b` | Pending, attention |
| Error | `#ef4444` | Errors, delete |
| Professional | `#3b82f6` | Professional trips |
| Personal | `#a855f7` | Personal trips |

### Typography

Using Material 3 typography scale with system fonts:

| Style | Weight | Size | Usage |
|-------|--------|------|-------|
| Display Large | Bold | 57sp | Hero numbers |
| Headline Large | SemiBold | 32sp | Screen titles |
| Headline Medium | SemiBold | 28sp | Section headers |
| Title Large | Medium | 22sp | Card titles |
| Title Medium | Medium | 16sp | Subtitles |
| Body Large | Regular | 16sp | Primary body |
| Body Medium | Regular | 14sp | Secondary body |
| Label Large | Medium | 14sp | Buttons |
| Label Medium | Medium | 12sp | Chips, badges |

### Components

#### Buttons
- **Primary**: Filled, primary color, white text
- **Secondary**: Outlined, primary border, primary text
- **Text**: No background, primary text
- **Destructive**: Error color background

#### Cards
- **Elevated**: 1dp elevation, rounded corners (12dp)
- **Outlined**: 1dp border, no elevation
- **Trip Card**: Start/end time, distance, vehicle badge, type indicator

#### Inputs
- **OutlinedTextField**: Default for forms
- **SearchBar**: With leading icon, trailing clear
- **Dropdown**: Exposed dropdown menu

#### Navigation
- **BottomNavigation**: 5 items, filled/outlined icons
- **ProBottomNavigation**: Expandable 2-row design

### Spacing Scale
| Token | Value |
|-------|-------|
| xs | 4dp |
| sm | 8dp |
| md | 16dp |
| lg | 24dp |
| xl | 32dp |
| xxl | 48dp |

---

## Key Business Logic

### Mileage Calculation (French Tax Compliance)

#### Vehicle Power Rates (Bareme Fiscal 2024)
| Power | 0-5000km | 5001-20000km | >20000km |
|-------|----------|--------------|----------|
| 3CV | 0.529 €/km | 0.316 €/km + 1065€ | 0.370 €/km |
| 4CV | 0.606 €/km | 0.340 €/km + 1330€ | 0.407 €/km |
| 5CV | 0.636 €/km | 0.357 €/km + 1395€ | 0.427 €/km |
| 6CV | 0.665 €/km | 0.374 €/km + 1457€ | 0.447 €/km |
| 7CV+ | 0.697 €/km | 0.394 €/km + 1515€ | 0.470 €/km |

#### Calculation Logic
```kotlin
fun calculateReimbursement(
    distanceKm: Double,
    vehiclePower: VehiclePower,
    annualMileageSoFar: Double
): Double {
    val bracket = when {
        annualMileageSoFar + distanceKm <= 5000 -> BRACKET_1
        annualMileageSoFar + distanceKm <= 20000 -> BRACKET_2
        else -> BRACKET_3
    }
    return distanceKm * bracket.rate + bracket.fixedAmount
}
```

#### Work-Home Trip Rules
- **Default**: Capped at 40km per trip (80km round-trip daily)
- **Consider Full Distance**: User setting to use actual distance
- **Separate Tracking**: `totalMileageWorkHome` on vehicles

### Trip Validation Rules

```kotlin
const val MIN_TRIP_DISTANCE_METERS = 10.0
const val MIN_TRIP_DURATION_SECONDS = 15
const val MAX_GPS_ACCURACY_METERS = 50.0
const val INACTIVITY_TIMEOUT_MINUTES = 5
const val STILLNESS_DETECTION_MINUTES = 3
const val STILLNESS_RADIUS_METERS = 30.0
```

### Activity Recognition Thresholds

| Activity | Confidence | Action |
|----------|------------|--------|
| IN_VEHICLE | 75% | Start buffering GPS |
| ON_BICYCLE | 70% | Start buffering GPS |
| WALKING | 60% | End trip if active |
| STILL | 60% | Confirm trip end |

### License Lifecycle

```
PURCHASE → PENDING → (payment) → ACTIVE
                                    │
                    ┌───────────────┴───────────────┐
                    │                               │
                 ASSIGNED                      AVAILABLE
                    │                            (pool)
                    │ unlink request
                    ▼
              PENDING_UNLINK (30 days)
                    │
                    ▼
               AVAILABLE (back to pool)
```

### Sync Strategy (Offline-First with Delta Sync)

```kotlin
// Architecture: UI → ViewModel → Repository → Room (source of truth)
//                                           → PendingOperationDao (queue)
//                                           → DeltaSyncWorker → WorkManager → Supabase

// On trip save (TripRepository.kt)
suspend fun saveTrip(trip: Trip) {
    // 1. Save locally immediately with PENDING_UPLOAD status
    val entity = trip.toEntity(userId).copy(
        syncStatus = SyncStatus.PENDING_UPLOAD.name,
        localUpdatedAt = System.currentTimeMillis(),
        needsSync = true
    )
    tripDao.insertTrip(entity)

    // 2. Queue operation for background sync (non-blocking)
    syncManager.queueOperation(
        entityType = PendingOperationEntity.TYPE_TRIP,
        entityId = trip.id,
        action = if (isNewTrip) ACTION_CREATE else ACTION_UPDATE
    )
    // DeltaSyncWorker will process the queue automatically
}

// Delta Sync Process (DeltaSyncWorker.kt)
// 1. UPLOAD PHASE: Process pending operations with exponential backoff
// 2. DOWNLOAD PHASE: Fetch changes since lastSyncTimestamp
// 3. CONFLICT RESOLUTION: Last-write-wins based on timestamps
```

### Sync Status Enum

```kotlin
enum class SyncStatus {
    SYNCED,           // In sync with server
    PENDING_UPLOAD,   // Local changes to upload
    PENDING_DELETE,   // Deletion pending
    CONFLICT,         // Local/server conflict
    ERROR             // Sync error (max retries reached)
}
```

---

## Environment Variables

### Android (BuildConfig)

| Variable | Description | Location |
|----------|-------------|----------|
| `SUPABASE_URL` | Supabase project URL | `build.gradle.kts` |
| `SUPABASE_ANON_KEY` | Supabase anonymous key | `build.gradle.kts` |
| `STRIPE_PUBLISHABLE_KEY` | Stripe public key | `build.gradle.kts` |
| `MAPS_TILE_SERVER_URL` | Self-hosted tile server | `build.gradle.kts` |

### Supabase Edge Functions

| Variable | Description |
|----------|-------------|
| `STRIPE_SECRET_KEY` | Stripe secret key |
| `STRIPE_WEBHOOK_SECRET` | Webhook signing secret |
| `RESEND_API_KEY` | Email service API key |
| `SUPABASE_SERVICE_ROLE_KEY` | Admin access key |

---

## File Structure

```
app/src/main/java/com/application/motium/
├── data/
│   ├── local/
│   │   ├── dao/                    # Room DAOs
│   │   │   ├── UserDao.kt
│   │   │   ├── TripDao.kt
│   │   │   ├── VehicleDao.kt
│   │   │   ├── ExpenseDao.kt
│   │   │   ├── WorkScheduleDao.kt
│   │   │   ├── CompanyLinkDao.kt
│   │   │   ├── PendingOperationDao.kt  # Sync queue DAO
│   │   │   └── SyncMetadataDao.kt      # Delta sync DAO
│   │   ├── entities/               # Room entities
│   │   │   ├── UserEntity.kt
│   │   │   ├── TripEntity.kt
│   │   │   ├── VehicleEntity.kt
│   │   │   ├── ExpenseEntity.kt
│   │   │   ├── WorkScheduleEntity.kt
│   │   │   ├── AutoTrackingSettingsEntity.kt
│   │   │   ├── CompanyLinkEntity.kt
│   │   │   ├── SyncStatus.kt              # Sync status enum
│   │   │   ├── PendingOperationEntity.kt  # Sync queue
│   │   │   └── SyncMetadataEntity.kt      # Delta sync timestamps
│   │   └── MotiumDatabase.kt       # Database class (v15)
│   │
│   ├── supabase/                   # Remote repositories
│   │   ├── SupabaseAuthRepository.kt
│   │   ├── SupabaseTripRepository.kt
│   │   ├── SupabaseVehicleRepository.kt
│   │   ├── SupabaseExpenseRepository.kt
│   │   ├── LicenseRepository.kt
│   │   ├── LinkedAccountRepository.kt
│   │   ├── ProAccountRepository.kt
│   │   ├── StripeRepository.kt
│   │   └── SupabaseGdprRepository.kt
│   │
│   ├── geocoding/                  # Location services
│   │   └── NominatimService.kt
│   │
│   ├── preferences/                # Encrypted storage
│   │   └── SecureSessionStorage.kt
│   │
│   ├── subscription/
│   │   └── SubscriptionManager.kt
│   │
│   ├── sync/                       # Offline-first sync layer
│   │   ├── OfflineFirstSyncManager.kt  # Central sync coordinator
│   │   ├── DeltaSyncWorker.kt          # WorkManager delta sync
│   │   └── SupabaseSyncManager.kt      # Legacy sync (deprecated)
│   │
│   └── TripRepository.kt           # Main trip repository
│
├── domain/
│   ├── model/                      # Domain models
│   │   ├── User.kt
│   │   ├── Trip.kt
│   │   ├── Vehicle.kt
│   │   ├── Expense.kt
│   │   ├── WorkSchedule.kt
│   │   ├── CompanyLink.kt
│   │   ├── ProAccount.kt
│   │   ├── License.kt
│   │   └── LinkedAccount.kt
│   │
│   └── repository/                 # Repository interfaces
│       ├── AuthRepository.kt
│       ├── TripRepository.kt
│       ├── UserRepository.kt
│       ├── VehicleRepository.kt
│       └── GdprRepository.kt
│
├── presentation/
│   ├── auth/                       # Auth screens
│   │   ├── LoginScreen.kt
│   │   ├── RegisterScreen.kt
│   │   ├── PhoneVerificationScreen.kt
│   │   ├── ForgotPasswordScreen.kt
│   │   ├── ResetPasswordScreen.kt
│   │   └── AuthViewModel.kt
│   │
│   ├── individual/                 # Individual user screens
│   │   ├── home/
│   │   │   └── NewHomeScreen.kt
│   │   ├── addtrip/
│   │   │   └── AddTripScreen.kt
│   │   ├── tripdetails/
│   │   │   └── TripDetailsScreen.kt
│   │   ├── edittrip/
│   │   │   └── EditTripScreen.kt
│   │   ├── calendar/
│   │   │   └── CalendarScreen.kt
│   │   ├── vehicles/
│   │   │   └── VehiclesScreen.kt
│   │   ├── expense/
│   │   │   ├── AddExpenseScreen.kt
│   │   │   ├── EditExpenseScreen.kt
│   │   │   └── ExpenseDetailsScreen.kt
│   │   ├── export/
│   │   │   ├── ExportScreen.kt
│   │   │   └── ExportViewModel.kt
│   │   ├── settings/
│   │   │   └── SettingsScreen.kt
│   │   └── upgrade/
│   │       └── UpgradeScreen.kt
│   │
│   ├── pro/                        # Pro/Enterprise screens
│   │   ├── accounts/
│   │   │   ├── LinkedAccountsScreen.kt
│   │   │   ├── LinkedAccountsViewModel.kt
│   │   │   ├── AccountDetailsScreen.kt
│   │   │   └── LinkedUserTripsScreen.kt
│   │   ├── licenses/
│   │   │   ├── LicensesScreen.kt
│   │   │   ├── LicensesViewModel.kt
│   │   │   ├── PurchaseLicenseDialog.kt
│   │   │   ├── AssignLicenseDialog.kt
│   │   │   └── UnlinkConfirmDialog.kt
│   │   └── export/
│   │       ├── ProExportAdvancedScreen.kt
│   │       └── ProExportAdvancedViewModel.kt
│   │
│   ├── subscription/
│   │   ├── TrialExpiredScreen.kt
│   │   └── ProTrialExpiredScreen.kt
│   │
│   ├── components/                 # Reusable components
│   │   ├── MiniMap.kt
│   │   ├── AddressAutocomplete.kt
│   │   ├── CompanyLinkCard.kt
│   │   ├── PremiumDialog.kt
│   │   ├── UpgradeDialog.kt
│   │   ├── StripePaymentSheet.kt
│   │   ├── SyncStatusIndicator.kt  # Offline/sync status in TopAppBar
│   │   └── gdpr/
│   │       ├── DataExportDialog.kt
│   │       └── DeleteAccountDialog.kt
│   │
│   ├── navigation/
│   │   └── MotiumNavHost.kt
│   │
│   ├── theme/
│   │   ├── Theme.kt
│   │   ├── Color.kt
│   │   └── Type.kt
│   │
│   └── MainActivity.kt
│
├── service/
│   ├── LocationTrackingService.kt
│   ├── ActivityRecognitionService.kt
│   ├── ActivityRecognitionReceiver.kt
│   └── SupabaseConnectionService.kt
│
├── utils/
│   ├── DeepLinkHandler.kt
│   ├── ExportManager.kt
│   ├── PermissionManager.kt
│   ├── FrenchMileageCalculator.kt
│   ├── TripCalculator.kt
│   ├── LocationUtils.kt
│   ├── CalendarUtils.kt
│   ├── CredentialManagerHelper.kt
│   ├── GoogleSignInHelper.kt
│   ├── NetworkConnectionManager.kt
│   ├── ThemeManager.kt
│   └── AppLogger.kt
│
└── MotiumApp.kt                    # Application class

supabase/
├── functions/
│   ├── create-payment-intent/
│   │   └── index.ts
│   ├── confirm-payment-intent/
│   │   └── index.ts
│   ├── stripe-webhook/
│   │   └── index.ts
│   ├── gdpr-export/
│   │   └── index.ts
│   ├── gdpr-delete-account/
│   │   └── index.ts
│   ├── send-gdpr-email/
│   │   └── index.ts
│   ├── request-password-reset/
│   │   └── index.ts
│   └── reset-password/
│       └── index.ts
│
└── migrations/                     # Database migrations
    └── *.sql

database/
├── supabase_schema_simple.sql      # Main schema
├── stripe_integration.sql          # Stripe tables
├── gdpr_schema.sql                 # GDPR tables
└── sync_schema.sql                 # Sync tables
```

---

## iOS Conversion Checklist

### Core Services to Implement
- [ ] **Location Tracking**: Core Location + CLLocationManager with state machine
- [ ] **Activity Recognition**: CMMotionActivityManager (more limited than Android)
- [ ] **Background Tasks**: BGProcessingTask + BGAppRefreshTask
- [ ] **Significant Location Change**: For battery-efficient tracking

### Platform Differences
| Feature | Android | iOS Equivalent |
|---------|---------|----------------|
| Activity Recognition | Google Play Services | CMMotionActivityManager |
| Background Location | Foreground Service | Background Location Mode |
| Doze Mode | AlarmManager exact | Background App Refresh |
| Notifications | NotificationChannel | UNUserNotificationCenter |
| Credentials | CredentialManager | Keychain + Sign in with Apple |
| Maps | MapLibre Android | MapLibre iOS / MapKit |

### Data Migration
- Export Room schema as JSON
- CoreData equivalent entities
- Keychain for secure storage
- UserDefaults for preferences

### UI Mapping
| Android (Compose) | iOS (SwiftUI) |
|-------------------|---------------|
| Scaffold | NavigationStack |
| BottomNavigation | TabView |
| LazyColumn | List |
| Card | GroupBox / custom |
| OutlinedTextField | TextField |
| DropdownMenu | Picker |
| AlertDialog | Alert |
| BottomSheet | Sheet |

---

## Website Features Checklist

### Public Pages
- [ ] Landing page with app description
- [ ] Pricing page (Individual, Pro plans)
- [ ] Features overview
- [ ] Privacy Policy
- [ ] Terms of Service
- [ ] Contact / Support

### User Dashboard (Web App)
- [ ] Login / Register (Supabase Auth)
- [ ] Trip history view
- [ ] Export functionality (PDF, CSV, Excel)
- [ ] Subscription management (Stripe Customer Portal)
- [ ] Profile settings

### Pro Dashboard
- [ ] Employee management
- [ ] License pool management
- [ ] Multi-employee export
- [ ] Company settings (SIRET, VAT, etc.)
- [ ] Billing history

### API Endpoints Needed
- All existing Supabase Edge Functions
- Web-specific: Invoice generation, bulk export

---

*This document is the single source of truth for the Motium application. Update it whenever architectural changes are made.*
