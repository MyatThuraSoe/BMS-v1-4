-- Add currency column to shop_info (currency set on the Shop Info page)
ALTER TABLE shop_info ADD COLUMN currency VARCHAR(10) NULL;
