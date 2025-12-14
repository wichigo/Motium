-- =============================================================================
-- FIX: Remove duplicate columns and rename primary_color
-- =============================================================================
-- Run this if you already executed the previous migration with wrong columns
-- =============================================================================

-- Remove duplicate columns
ALTER TABLE users DROP COLUMN IF EXISTS full_name;
ALTER TABLE users DROP COLUMN IF EXISTS phone;

-- Rename primary_color to favorite_colors and change to JSONB array
ALTER TABLE users DROP COLUMN IF EXISTS primary_color;
ALTER TABLE users ADD COLUMN IF NOT EXISTS favorite_colors JSONB DEFAULT '[]'::jsonb;

-- =============================================================================
-- DONE
-- =============================================================================
