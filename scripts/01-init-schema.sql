-- Create users table
CREATE TABLE IF NOT EXISTS users (
    id SERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Create export_jobs table
CREATE TABLE IF NOT EXISTS export_jobs (
    id UUID PRIMARY KEY,
    status VARCHAR(20) NOT NULL,
    total_records BIGINT,
    processed_records BIGINT DEFAULT 0,
    s3_key VARCHAR(255),
    error_message VARCHAR(1000),
    created_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP,
    finished_at TIMESTAMP
);

-- Create index for faster queries
CREATE INDEX IF NOT EXISTS idx_users_created_at ON users(created_at);
CREATE INDEX IF NOT EXISTS idx_export_jobs_status ON export_jobs(status);
