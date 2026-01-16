-- ========================================
-- MIGRATION: Ajouter colonnes deleted_at pour soft delete
-- ========================================
-- Date: 2025-12-29
-- Description: Ajoute les colonnes deleted_at aux tables supportant le soft delete
-- Requis pour la RPC get_changes() qui retourne les entités supprimées
-- ========================================

-- Trips
ALTER TABLE trips ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ DEFAULT NULL;
CREATE INDEX IF NOT EXISTS idx_trips_deleted_at ON trips(deleted_at) WHERE deleted_at IS NOT NULL;

-- Vehicles
ALTER TABLE vehicles ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ DEFAULT NULL;
CREATE INDEX IF NOT EXISTS idx_vehicles_deleted_at ON vehicles(deleted_at) WHERE deleted_at IS NOT NULL;

-- Expenses (expenses_trips)
ALTER TABLE expenses_trips ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ DEFAULT NULL;
CREATE INDEX IF NOT EXISTS idx_expenses_deleted_at ON expenses_trips(deleted_at) WHERE deleted_at IS NOT NULL;

-- Work Schedules
ALTER TABLE work_schedules ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ DEFAULT NULL;

-- Auto Tracking Settings (pas de soft delete car 1 par user, on update plutot)

-- Pro Accounts (pas de soft delete, un utilisateur garde son compte pro)

-- Company Links (utilise unlinked_at comme equivalent de deleted_at)
-- Pas besoin d'ajouter deleted_at car unlinked_at sert deja de marqueur

-- Licenses (status = 'cancelled' equivalent de soft delete)
-- Pas besoin d'ajouter deleted_at car status sert deja de marqueur

-- User Consents (revoked_at sert de marqueur)
-- Pas besoin d'ajouter deleted_at car revoked_at sert deja de marqueur

-- ========================================
-- INDEX pour les requetes de sync
-- ========================================
-- Index sur updated_at pour optimiser get_changes()

CREATE INDEX IF NOT EXISTS idx_trips_updated_at ON trips(updated_at);
CREATE INDEX IF NOT EXISTS idx_vehicles_updated_at ON vehicles(updated_at);
CREATE INDEX IF NOT EXISTS idx_expenses_updated_at ON expenses_trips(updated_at);
CREATE INDEX IF NOT EXISTS idx_work_schedules_updated_at ON work_schedules(updated_at);
CREATE INDEX IF NOT EXISTS idx_auto_tracking_settings_updated_at ON auto_tracking_settings(updated_at);

-- ========================================
-- FIN DE LA MIGRATION
-- ========================================
