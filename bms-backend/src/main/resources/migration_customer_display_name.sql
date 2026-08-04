-- Migration: Add customer_display_name column to sales table
-- Run this against your existing database before deploying the updated backend.

ALTER TABLE sales ADD COLUMN customer_display_name VARCHAR(255) NOT NULL DEFAULT 'Walk-in';

-- Backfill existing sales that have a linked customer
UPDATE sales s
JOIN customers c ON s.customer_id = c.id
SET s.customer_display_name = CONCAT(
    c.first_name, ' ', c.last_name,
    CASE
        WHEN c.phone IS NOT NULL THEN CONCAT(' (', c.phone, ')')
        WHEN c.email IS NOT NULL THEN CONCAT(' (', c.email, ')')
        ELSE ''
    END
);
