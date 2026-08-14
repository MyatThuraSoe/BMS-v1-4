-- Per-day purchase order number sequence (mirrors invoice_sequences)
CREATE TABLE purchase_sequences (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sequence_date DATE NOT NULL UNIQUE,
    last_number INT
);
