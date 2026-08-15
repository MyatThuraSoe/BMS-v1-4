-- Add receipt paper size column to shop_info (set on the Shop Info page)
ALTER TABLE shop_info ADD COLUMN receipt_paper_size VARCHAR(10) NULL;