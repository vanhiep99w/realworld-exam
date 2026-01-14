-- Generate 10 million users using generate_series
-- This uses batch insert for optimal performance

-- Disable triggers and indexes temporarily for faster insert
ALTER TABLE users DISABLE TRIGGER ALL;

-- Insert 10 million records in batches of 100,000
DO $$
DECLARE
    batch_size INTEGER := 100000;
    total_records INTEGER := 10000000;
    i INTEGER := 0;
BEGIN
    RAISE NOTICE 'Starting insert of % records...', total_records;
    
    WHILE i < total_records LOOP
        INSERT INTO users (email, name, created_at)
        SELECT 
            'user' || (i + gs) || '@example.com',
            'User ' || (i + gs),
            TIMESTAMP '2020-01-01' + (random() * (TIMESTAMP '2025-12-31' - TIMESTAMP '2020-01-01'))
        FROM generate_series(1, batch_size) AS gs;
        
        i := i + batch_size;
        RAISE NOTICE 'Inserted % / % records', i, total_records;
        
        -- Commit each batch
        COMMIT;
    END LOOP;
    
    RAISE NOTICE 'Insert completed!';
END $$;

-- Re-enable triggers
ALTER TABLE users ENABLE TRIGGER ALL;

-- Analyze table for query optimizer
ANALYZE users;

-- Show final count
SELECT COUNT(*) as total_users FROM users;
