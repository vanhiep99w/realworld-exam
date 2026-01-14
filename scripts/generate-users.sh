#!/bin/bash

# Script to generate 10 million users in PostgreSQL
# Usage: ./generate-users.sh

set -e

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-realworld_example}"
DB_USER="${DB_USER:-demo}"
DB_PASS="${DB_PASS:-demo}"

TOTAL_RECORDS=10000000
BATCH_SIZE=100000

echo "=== Generating $TOTAL_RECORDS users ==="
echo "Database: $DB_HOST:$DB_PORT/$DB_NAME"

export PGPASSWORD=$DB_PASS

# Check connection
psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -c "SELECT 1" > /dev/null 2>&1 || {
    echo "Error: Cannot connect to database"
    exit 1
}

# Get current count
CURRENT=$(psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -t -c "SELECT COUNT(*) FROM users")
echo "Current records: $CURRENT"

if [ "$CURRENT" -ge "$TOTAL_RECORDS" ]; then
    echo "Already have $CURRENT records. Skipping."
    exit 0
fi

# Disable auto-vacuum during insert
psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -c "ALTER TABLE users SET (autovacuum_enabled = false);"

START_TIME=$(date +%s)
INSERTED=0

echo "Starting insert..."

while [ $INSERTED -lt $TOTAL_RECORDS ]; do
    psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME <<EOF
INSERT INTO users (email, name, created_at)
SELECT 
    'user' || ($INSERTED + gs) || '@example.com',
    'User ' || ($INSERTED + gs),
    TIMESTAMP '2020-01-01' + (random() * (TIMESTAMP '2025-12-31' - TIMESTAMP '2020-01-01'))
FROM generate_series(1, $BATCH_SIZE) AS gs;
EOF

    INSERTED=$((INSERTED + BATCH_SIZE))
    ELAPSED=$(($(date +%s) - START_TIME))
    RATE=$((INSERTED / (ELAPSED + 1)))
    
    echo "Inserted: $INSERTED / $TOTAL_RECORDS (${RATE} records/sec)"
done

# Re-enable auto-vacuum and analyze
psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME <<EOF
ALTER TABLE users SET (autovacuum_enabled = true);
ANALYZE users;
EOF

END_TIME=$(date +%s)
TOTAL_TIME=$((END_TIME - START_TIME))

echo "=== Completed ==="
echo "Total time: ${TOTAL_TIME}s"
echo "Final count: $(psql -h $DB_HOST -p $DB_PORT -U $DB_USER -d $DB_NAME -t -c 'SELECT COUNT(*) FROM users')"
