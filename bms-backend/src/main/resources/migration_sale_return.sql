-- Migration: Sale Return schema (renames the former "refund" tables to
-- "sale_return" naming and adds sales.return_status).
-- Run this against your existing database before deploying the updated backend.
--
-- NOTE: Hibernate 6 `ddl-auto: update` creates sale_returns / sale_return_items
-- automatically on FRESH installs. On EXISTING installs it cannot rename tables,
-- so run this script to keep your return history and avoid empty duplicate tables.

-- 1) Rename tables
RENAME TABLE refunds TO sale_returns;
RENAME TABLE refund_items TO sale_return_items;

-- 2) Rename columns inside the renamed tables (CHANGE works on MySQL 5.7+)
ALTER TABLE sale_returns
    CHANGE COLUMN refunded_by returned_by BIGINT NOT NULL,
    CHANGE COLUMN refund_date return_date DATETIME NOT NULL,
    CHANGE COLUMN total_refund_amount total_return_amount DECIMAL(10,2) NOT NULL;

ALTER TABLE sale_return_items
    CHANGE COLUMN refund_id sale_return_id BIGINT NOT NULL,
    CHANGE COLUMN quantity_refunded quantity_returned INT NOT NULL,
    CHANGE COLUMN refund_amount return_amount DECIMAL(10,2) NOT NULL;

-- 3) Index names follow the old tables; rename them for consistency (safe to skip on failure)
ALTER TABLE sale_returns DROP INDEX idx_refund_sale, ADD INDEX idx_sale_return_sale (sale_id);
ALTER TABLE sale_returns DROP INDEX idx_refund_date, ADD INDEX idx_sale_return_date (return_date);
ALTER TABLE sale_returns DROP INDEX idx_refund_user, ADD INDEX idx_sale_return_user (returned_by);
ALTER TABLE sale_return_items DROP INDEX idx_refund_item_refund, ADD INDEX idx_sale_return_item_return (sale_return_id);
ALTER TABLE sale_return_items DROP INDEX idx_refund_item_sale_item, ADD INDEX idx_sale_return_item_sale_item (sale_item_id);

-- 4) Sale return status (COMPLETED / PARTIALLY_RETURNED / FULLY_RETURNED)
ALTER TABLE sales ADD COLUMN return_status VARCHAR(255) DEFAULT 'COMPLETED';
UPDATE sales SET return_status = 'COMPLETED' WHERE return_status IS NULL;

-- Backfill from historical returned quantities
UPDATE sales s
JOIN (
    SELECT si.sale_id
    FROM sale_items si
    GROUP BY si.sale_id
    HAVING SUM(si.quantity_refunded) > 0 AND SUM(si.quantity_refunded) < SUM(si.quantity)
) t ON s.id = t.sale_id
SET s.return_status = 'PARTIALLY_RETURNED';

UPDATE sales s
JOIN (
    SELECT si.sale_id
    FROM sale_items si
    GROUP BY si.sale_id
    HAVING SUM(si.quantity_refunded) >= SUM(si.quantity)
) t ON s.id = t.sale_id
SET s.return_status = 'FULLY_RETURNED';
