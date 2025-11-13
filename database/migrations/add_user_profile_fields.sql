-- Motium Migration: Add User Profile Fields
-- Date: 2025-10-28
-- Description: Add phone number, address, and company link/sharing permissions fields to users table

-- ========================================
-- ADD NEW COLUMNS TO USERS TABLE
-- ========================================

-- Add phone_number column
ALTER TABLE users
ADD COLUMN IF NOT EXISTS phone_number VARCHAR(20) DEFAULT '';

-- Add address column
ALTER TABLE users
ADD COLUMN IF NOT EXISTS address TEXT DEFAULT '';

-- Add company linking column
ALTER TABLE users
ADD COLUMN IF NOT EXISTS linked_to_company BOOLEAN DEFAULT FALSE;

-- Add data sharing permission columns
ALTER TABLE users
ADD COLUMN IF NOT EXISTS share_professional_trips BOOLEAN DEFAULT TRUE;

ALTER TABLE users
ADD COLUMN IF NOT EXISTS share_personal_trips BOOLEAN DEFAULT FALSE;

ALTER TABLE users
ADD COLUMN IF NOT EXISTS share_personal_info BOOLEAN DEFAULT TRUE;

-- ========================================
-- CREATE INDEX FOR COMPANY LINKED USERS
-- ========================================

-- Index to quickly find users linked to companies
CREATE INDEX IF NOT EXISTS idx_users_linked_to_company
ON users(linked_to_company)
WHERE linked_to_company = TRUE;

-- ========================================
-- VERIFY THE MIGRATION
-- ========================================

-- Check that all columns were added successfully
SELECT column_name, data_type, column_default
FROM information_schema.columns
WHERE table_name = 'users'
AND column_name IN (
    'phone_number',
    'address',
    'linked_to_company',
    'share_professional_trips',
    'share_personal_trips',
    'share_personal_info'
)
ORDER BY column_name;
