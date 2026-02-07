-- Migration: Synchronisation schema Supabase <-> Kotlin
-- Date: 2025-12-21
-- Description: Ajoute les colonnes manquantes dans Supabase pour correspondre aux modeles Kotlin

-- ============================================
-- TABLE: users
-- ============================================
ALTER TABLE users
  ADD COLUMN IF NOT EXISTS trial_started_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS trial_ends_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS phone_verified BOOLEAN DEFAULT false,
  ADD COLUMN IF NOT EXISTS verified_phone VARCHAR(20),
  ADD COLUMN IF NOT EXISTS device_fingerprint_id TEXT,
  ADD COLUMN IF NOT EXISTS consider_full_distance BOOLEAN DEFAULT false,
  ADD COLUMN IF NOT EXISTS profile_photo_url TEXT;

-- ============================================
-- TABLE: vehicles
-- ============================================
ALTER TABLE vehicles
  ADD COLUMN IF NOT EXISTS total_mileage_work_home DOUBLE PRECISION DEFAULT 0.0;

-- ============================================
-- TABLE: licenses
-- ============================================
ALTER TABLE licenses
  ADD COLUMN IF NOT EXISTS linked_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS is_lifetime BOOLEAN DEFAULT false,
  ADD COLUMN IF NOT EXISTS unlink_requested_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS unlink_effective_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS billing_starts_at TIMESTAMPTZ;

-- ============================================
-- TABLE: pro_accounts
-- ============================================
ALTER TABLE pro_accounts
  ADD COLUMN IF NOT EXISTS billing_day INTEGER DEFAULT 5 CHECK (billing_day >= 1 AND billing_day <= 28);

-- ============================================
-- TABLE: work_schedules
-- ============================================
ALTER TABLE work_schedules
  ADD COLUMN IF NOT EXISTS is_overnight BOOLEAN DEFAULT false;

-- ============================================
-- TABLE: trips
-- ============================================
ALTER TABLE trips
  ADD COLUMN IF NOT EXISTS notes TEXT;
