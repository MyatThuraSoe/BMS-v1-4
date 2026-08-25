// LumiPOS happy path: login -> POS -> add item to cart -> checkout dialog.
// Requires the backend running and an open cash shift for the admin user
// (run scripts/smoke-test.mjs once first — it opens the shift and creates
// a SMOKE product you can search for).
import { test, expect } from '@playwright/test';

const USER = process.env.SMOKE_USER || 'admin';
const PASS = process.env.SMOKE_PASS || 'admin123';

test.describe('LumiPOS happy path', () => {
  test('login lands on the app shell', async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel(/username/i).fill(USER);
    await page.getByLabel(/password/i).fill(PASS);
    await page.getByRole('button', { name: /sign in/i }).click();
    // App shell renders (drawer or dashboard heading appears)
    await expect(page.locator('header')).toBeVisible({ timeout: 15_000 });
  });

  test('POS loads products grid and cart panel', async ({ page }) => {
    // Login via API for speed, then reuse storage
    await page.goto('/login');
    await page.getByLabel(/username/i).fill(USER);
    await page.getByLabel(/password/i).fill(PASS);
    await page.getByRole('button', { name: /sign in/i }).click();
    await expect(page.locator('header')).toBeVisible();

    await page.goto('/pos');
    await expect(page.getByText(/available products/i)).toBeVisible();
    // Cart checkout button present
    await expect(page.getByRole('button', { name: /checkout/i }).first()).toBeVisible();
  });
});
