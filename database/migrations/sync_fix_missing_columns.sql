-- ========================================
-- MIGRATION: Fix Sync Issues (Missing Columns)
-- ========================================
-- Adds missing columns to Supabase tables to match local Room DB schema.
-- Fixes issues where trips.notes and vehicle mileages were not syncing.
-- ========================================

-- 1. Fix TRIPS table
ALTER TABLE trips ADD COLUMN IF NOT EXISTS notes TEXT;
ALTER TABLE trips ADD COLUMN IF NOT EXISTS start_address TEXT;
ALTER TABLE trips ADD COLUMN IF NOT EXISTS end_address TEXT;
-- Ensure matched_route_coordinates is available if needed for caching
ALTER TABLE trips ADD COLUMN IF NOT EXISTS matched_route_coordinates TEXT;

-- 2. Fix VEHICLES table
-- Add missing mileage counters used in VehicleRemoteDataSource.kt
ALTER TABLE vehicles ADD COLUMN IF NOT EXISTS total_mileage_perso DECIMAL(10,2) DEFAULT 0.0;
ALTER TABLE vehicles ADD COLUMN IF NOT EXISTS total_mileage_pro DECIMAL(10,2) DEFAULT 0.0;
ALTER TABLE vehicles ADD COLUMN IF NOT EXISTS total_mileage_work_home DECIMAL(10,2) DEFAULT 0.0;

-- 3. Fix EXPENSES table (expenses_trips)
-- Ensure note column exists
ALTER TABLE expenses_trips ADD COLUMN IF NOT EXISTS note TEXT;
-- Ensure other optional fields exist
ALTER TABLE expenses_trips ADD COLUMN IF NOT EXISTS amount_ht DECIMAL(10,2);
ALTER TABLE expenses_trips ADD COLUMN IF NOT EXISTS photo_uri TEXT;

-- ========================================
-- Verify columns were added
-- ========================================
/*
SELECT 
    column_name, 
    data_type 
FROM 
    information_schema.columns 
WHERE 
    table_name IN ('trips', 'vehicles', 'expenses_trips')
    AND column_name IN ('notes', 'total_mileage_work_home', 'note', 'amount_ht', 'photo_uri');
*/