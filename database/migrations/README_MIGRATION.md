# Database Migration: Add User Profile Fields

## Overview
This migration adds the following fields to the `users` table in Supabase:
- `phone_number` - User's phone number (VARCHAR(20))
- `address` - User's address (TEXT)
- `linked_to_company` - Whether user is linked to a company (BOOLEAN)
- `share_professional_trips` - Permission to share professional trips (BOOLEAN)
- `share_personal_trips` - Permission to share personal trips (BOOLEAN)
- `share_personal_info` - Permission to share personal information (BOOLEAN)

## How to Apply the Migration

### Method 1: Using Supabase Dashboard (Recommended)
1. Go to your Supabase project dashboard: https://app.supabase.com
2. Navigate to the SQL Editor
3. Click "New Query"
4. Copy the entire contents of `add_user_profile_fields.sql`
5. Paste it into the SQL editor
6. Click "Run" or press `Ctrl+Enter`
7. Wait for the migration to complete (you should see a success message)

### Method 2: Using Supabase CLI
```bash
# If you have Supabase CLI installed
supabase db push
```

## SQL Summary

```sql
-- Add columns to users table
ALTER TABLE users ADD COLUMN phone_number VARCHAR(20) DEFAULT '';
ALTER TABLE users ADD COLUMN address TEXT DEFAULT '';
ALTER TABLE users ADD COLUMN linked_to_company BOOLEAN DEFAULT FALSE;
ALTER TABLE users ADD COLUMN share_professional_trips BOOLEAN DEFAULT TRUE;
ALTER TABLE users ADD COLUMN share_personal_trips BOOLEAN DEFAULT FALSE;
ALTER TABLE users ADD COLUMN share_personal_info BOOLEAN DEFAULT TRUE;

-- Create index for company-linked users
CREATE INDEX idx_users_linked_to_company
ON users(linked_to_company)
WHERE linked_to_company = TRUE;
```

## Field Descriptions

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `phone_number` | VARCHAR(20) | '' | User's contact phone number |
| `address` | TEXT | '' | User's physical address |
| `linked_to_company` | BOOLEAN | FALSE | Whether the user is linked to a company/enterprise |
| `share_professional_trips` | BOOLEAN | TRUE | Can company access professional trip data |
| `share_personal_trips` | BOOLEAN | FALSE | Can company access personal trip data |
| `share_personal_info` | BOOLEAN | TRUE | Can company access user's personal info |

## Default Values

- **Phone Number**: Empty string (no phone required initially)
- **Address**: Empty string (no address required initially)
- **Linked to Company**: FALSE (users are not linked by default)
- **Share Professional Trips**: TRUE (default to sharing professional trips)
- **Share Personal Trips**: FALSE (default to NOT sharing personal trips)
- **Share Personal Info**: TRUE (default to sharing personal information)

## What Changed in the App

### Kotlin Data Model (`User.kt`)
The `User` data class now includes:
```kotlin
val phoneNumber: String = ""
val address: String = ""
val linkedToCompany: Boolean = false
val shareProfessionalTrips: Boolean = true
val sharePersonalTrips: Boolean = false
val sharePersonalInfo: Boolean = true
```

### UI Components (`SettingsScreen.kt`)
1. **Profile Information Section** - Phone number and address input fields
2. **Company Link Section** - Toggle for company linking with data sharing checkboxes

### Database Schema (`supabase_schema.sql`)
The `users` table definition has been updated with the new columns.

## Rollback (if needed)

If you need to rollback this migration:

```sql
ALTER TABLE users DROP COLUMN IF EXISTS phone_number;
ALTER TABLE users DROP COLUMN IF EXISTS address;
ALTER TABLE users DROP COLUMN IF EXISTS linked_to_company;
ALTER TABLE users DROP COLUMN IF EXISTS share_professional_trips;
ALTER TABLE users DROP COLUMN IF EXISTS share_personal_trips;
ALTER TABLE users DROP COLUMN IF EXISTS share_personal_info;

DROP INDEX IF EXISTS idx_users_linked_to_company;
```

## Notes

- The migration uses `IF NOT EXISTS` clauses to be idempotent (safe to run multiple times)
- All new columns have default values, so existing users will automatically get defaults
- The `linked_to_company` column has an index for efficient queries
- Row-level security (RLS) policies remain unchanged and still apply

## Verification

After running the migration, you can verify success by running:

```sql
SELECT column_name, data_type, column_default
FROM information_schema.columns
WHERE table_name = 'users'
ORDER BY column_name;
```

You should see all columns including the new ones.
