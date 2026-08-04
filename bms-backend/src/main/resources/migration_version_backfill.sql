-- Migration: Backfill version columns for optimistic locking (@Version)
-- Run this against your existing database before deploying the updated backend.
-- New columns are added by ddl-auto=update but existing rows get NULL, which
-- makes the FIRST write after the upgrade fail with an optimistic-lock error
-- ("Could not commit JPA transaction"). Set them to 0 to match a freshly
-- inserted row.

UPDATE products   SET version = 0 WHERE version IS NULL;
UPDATE categories SET version = 0 WHERE version IS NULL;
UPDATE suppliers  SET version = 0 WHERE version IS NULL;
UPDATE customers  SET version = 0 WHERE version IS NULL;
UPDATE shop_info  SET version = 0 WHERE version IS NULL;
