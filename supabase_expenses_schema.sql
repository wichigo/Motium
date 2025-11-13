-- Table: expenses_trips
-- Description: Stores expense records associated with trips

CREATE TABLE IF NOT EXISTS expenses_trips (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id UUID NOT NULL,
    type VARCHAR(50) NOT NULL CHECK (type IN ('FUEL', 'HOTEL', 'TOLL', 'PARKING', 'RESTAURANT', 'MEAL_OUT', 'OTHER')),
    amount DECIMAL(10, 2) NOT NULL CHECK (amount >= 0),
    note TEXT DEFAULT '',
    photo_uri TEXT DEFAULT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Foreign key constraint (assumes trips table exists)
    CONSTRAINT fk_trip
        FOREIGN KEY (trip_id)
        REFERENCES trips(id)
        ON DELETE CASCADE
);

-- Index on trip_id for faster queries
CREATE INDEX idx_expenses_trip_id ON expenses_trips(trip_id);

-- Index on created_at for sorting
CREATE INDEX idx_expenses_created_at ON expenses_trips(created_at DESC);

-- Enable Row Level Security (RLS)
ALTER TABLE expenses_trips ENABLE ROW LEVEL SECURITY;

-- RLS Policy: Users can only view their own trip expenses
CREATE POLICY "Users can view own trip expenses" ON expenses_trips
    FOR SELECT
    USING (
        trip_id IN (
            SELECT id FROM trips WHERE user_id = auth.uid()
        )
    );

-- RLS Policy: Users can insert expenses for their own trips
CREATE POLICY "Users can insert own trip expenses" ON expenses_trips
    FOR INSERT
    WITH CHECK (
        trip_id IN (
            SELECT id FROM trips WHERE user_id = auth.uid()
        )
    );

-- RLS Policy: Users can update their own trip expenses
CREATE POLICY "Users can update own trip expenses" ON expenses_trips
    FOR UPDATE
    USING (
        trip_id IN (
            SELECT id FROM trips WHERE user_id = auth.uid()
        )
    );

-- RLS Policy: Users can delete their own trip expenses
CREATE POLICY "Users can delete own trip expenses" ON expenses_trips
    FOR DELETE
    USING (
        trip_id IN (
            SELECT id FROM trips WHERE user_id = auth.uid()
        )
    );

-- Function to automatically update updated_at timestamp
CREATE OR REPLACE FUNCTION update_expenses_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Trigger to call the function before updates
CREATE TRIGGER trigger_update_expenses_updated_at
    BEFORE UPDATE ON expenses_trips
    FOR EACH ROW
    EXECUTE FUNCTION update_expenses_updated_at();

-- Comments for documentation
COMMENT ON TABLE expenses_trips IS 'Stores expense records (fuel, tolls, meals, etc.) associated with trips';
COMMENT ON COLUMN expenses_trips.id IS 'Unique identifier for the expense';
COMMENT ON COLUMN expenses_trips.trip_id IS 'Foreign key referencing the associated trip';
COMMENT ON COLUMN expenses_trips.type IS 'Type of expense: FUEL, HOTEL, TOLL, PARKING, RESTAURANT, MEAL_OUT, OTHER';
COMMENT ON COLUMN expenses_trips.amount IS 'Amount of the expense in euros';
COMMENT ON COLUMN expenses_trips.note IS 'Optional note or description for the expense';
COMMENT ON COLUMN expenses_trips.photo_uri IS 'URI/path to the receipt photo (if any)';
COMMENT ON COLUMN expenses_trips.created_at IS 'Timestamp when the expense was created';
COMMENT ON COLUMN expenses_trips.updated_at IS 'Timestamp when the expense was last updated';
