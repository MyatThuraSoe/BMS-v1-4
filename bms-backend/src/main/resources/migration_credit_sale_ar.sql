-- Migration: Credit Sale + Accounts Receivable schema additions
-- Run this against your existing database before deploying the updated backend.
--
-- NOTE: Hibernate 6 `ddl-auto: update` creates the new ar_payments table and
-- adds most columns automatically, but it can skip adding enum (STRING) columns
-- to an already-existing table. Add them explicitly here when that happens.

-- sales: credit sale fields (sale_type / payment_status are Hibernate STRING enums)
ALTER TABLE sales ADD COLUMN sale_type VARCHAR(255) DEFAULT 'CASH';
ALTER TABLE sales ADD COLUMN payment_status VARCHAR(255) DEFAULT 'PAID';
ALTER TABLE sales ADD COLUMN due_date DATE;

-- Backfill existing cash sales so old rows are consistent (H2/MySQL compatible)
UPDATE sales SET sale_type = 'CASH' WHERE sale_type IS NULL;
UPDATE sales SET payment_status = 'PAID' WHERE payment_status IS NULL;

-- customers: credit limit and running outstanding balance
ALTER TABLE customers ADD COLUMN credit_limit DECIMAL(19,2) DEFAULT 0.00;
ALTER TABLE customers ADD COLUMN current_balance DECIMAL(19,2) DEFAULT 0.00;

-- ar_payments: ledger of every payment recorded against a credit invoice.
-- Hibernate `ddl-auto: update` normally creates this; kept here as a reference.
CREATE TABLE IF NOT EXISTS ar_payments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    invoice_id BIGINT NOT NULL,
    amount DECIMAL(19,2) NOT NULL,
    payment_date TIMESTAMP NOT NULL,
    notes VARCHAR(255),
    recorded_by_id BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ar_payment_invoice ON ar_payments (invoice_id);
CREATE INDEX IF NOT EXISTS idx_ar_payment_date ON ar_payments (payment_date);
CREATE INDEX IF NOT EXISTS idx_ar_payment_recorded_by ON ar_payments (recorded_by_id);

-- receipt_customizations: toggle to show/hide the credit amount on receipts
ALTER TABLE receipt_customizations ADD COLUMN show_credit_info BOOLEAN DEFAULT TRUE;
