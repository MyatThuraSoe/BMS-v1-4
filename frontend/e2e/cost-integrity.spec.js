import { test as base, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';

const BASE = 'http://127.0.0.1:3000';
const STALE = 'This product was changed by someone else. Please refresh and try again.';
const dictionary = (language, namespace) => JSON.parse(readFileSync(
  new URL(`../public/locales/${language}/${namespace}.json`, import.meta.url), 'utf8',
));
const inventory = dictionary('en', 'inventory');

const test = base.extend({
  mock: async ({ context, page }, use, testInfo) => {
    const state = {
      language: 'en',
      product: {
        id: 1, name: 'Fixture product', sku: 'FIXTURE-001', unit: 'PC',
        unitPrice: 25, costPrice: 10, stockQuantity: 20, minStockLevel: 5,
        categoryId: 1, version: 7, hasExpiry: false, hasImage: false,
      },
      summary: {
        totalIncome: 1000, totalRefunds: 0, totalCogs: 600, grossProfit: 400,
        totalInventoryLoss: 120, totalExpenses: 120, netProfit: 280,
        outstandingAr: 0, expensesByCategory: [],
        inventoryLossByReason: [{ category: 'SPOILAGE_EXPIRY', amount: 120 }],
      },
      staleUpdate: false,
      staleAdjustment: false,
      requests: [], unhandled: [], external: [], pageErrors: [], console: [], failed: [],
      expectedConflicts: [],
    };
    page.on('pageerror', (error) => state.pageErrors.push(error.message));
    page.on('console', (message) => {
      if (['error', 'warning'].includes(message.type())) {
        state.console.push({ type: message.type(), text: message.text(), url: message.location().url });
      }
    });
    page.on('requestfailed', (request) => state.failed.push({ url: request.url(), error: request.failure()?.errorText }));

    // One fail-closed handler owns ALL browser HTTP requests. API calls never
    // use continue/fetch/fallback, including unrecognized reads and mutations.
    await context.route('**/*', async (route) => {
      const request = route.request();
      const url = new URL(request.url());
      if (url.origin !== BASE) {
        state.external.push(request.url());
        return route.abort('blockedbyclient');
      }
      if (url.pathname !== '/api' && !url.pathname.startsWith('/api/')) {
        return route.continue(); // Only Vite UI/modules/local translation assets.
      }
      const key = `${request.method()} ${url.pathname}`;
      state.requests.push({ key, query: url.search, body: request.postDataJSON() });
      const reply = (data, status = 200) => route.fulfill({
        status, contentType: 'application/json', body: JSON.stringify({ success: status < 400, data }),
      });
      const conflict = () => {
        state.expectedConflicts.push(request.url());
        return route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ success: false, message: STALE }) });
      };
      switch (key) {
        case 'GET /api/health': return reply({ status: 'UP' });
        case 'GET /api/shop-info': return reply({ shopName: 'Isolated fixture shop', currency: 'USD', hasLogo: false });
        case 'GET /api/shifts/current': return reply(null);
        case 'GET /api/license/status': return reply({ licensed: true, plan: 'lifetime' });
        case 'GET /api/categories': return reply({ content: [{ id: 1, name: 'Fixture category' }], totalElements: 1, totalPages: 1 });
        case 'GET /api/products/1': return reply(state.product);
        case 'GET /api/products': return reply({ content: [state.product], totalElements: 1, totalPages: 1 });
        case 'POST /api/products': return reply({ ...request.postDataJSON(), id: 2, version: 0 }, 201);
        case 'PUT /api/products/1': {
          if (state.staleUpdate) return conflict();
          state.product = { ...state.product, ...request.postDataJSON(), version: state.product.version + 1 };
          return reply(state.product);
        }
        case 'POST /api/products/1/cost-adjustment': {
          if (state.staleAdjustment) return conflict();
          state.product = { ...state.product, costPrice: request.postDataJSON().newCost, version: state.product.version + 1 };
          return reply(state.product);
        }
        case 'GET /api/reports/accounting-summary': return reply(state.summary);
        case 'GET /api/expenses': return reply([]);
        default:
          state.unhandled.push(key);
          return reply({ message: `Unmocked request blocked: ${key}` }, 501);
      }
    });
    state.open = async (path, language = 'en') => {
      state.language = language;
      await context.addInitScript(({ origin, lang }) => {
        if (window.location.origin !== origin) return;
        localStorage.setItem('token', 'isolated-fixture-token-not-a-real-credential');
        localStorage.setItem('user', JSON.stringify({ id: 900001, username: 'fixture-admin', roles: [{ name: 'ADMIN' }] }));
        localStorage.setItem('bms_language', lang);
      }, { origin: BASE, lang: language });
      await page.goto(`${BASE}${path}`);
    };
    state.calls = (key) => state.requests.filter((request) => request.key === key);
    try {
      await use(state);
    } finally {
      const unexpectedConsoleErrors = state.console.filter((entry) => entry.type === 'error'
        && !(entry.text.includes('409') && state.expectedConflicts.includes(entry.url)));
      console.log(JSON.stringify({
        test: testInfo.title,
        fetches: [...new Set(state.requests.map((request) => request.key + request.query))],
        unhandled: state.unhandled, blockedExternal: state.external,
        pageErrors: state.pageErrors, console: state.console, failedRequests: state.failed,
      }));
      expect(state.unhandled, 'No API call may fall through to the real backend').toEqual([]);
      expect(state.external, 'Only the task origin may be requested').toEqual([]);
      expect(state.pageErrors).toEqual([]);
      expect(unexpectedConsoleErrors).toEqual([]);
    }
  },
});

test.use({
  baseURL: BASE,
  ...(process.env.COST_TEST_CHANNEL ? { channel: process.env.COST_TEST_CHANNEL } : {}),
  storageState: { cookies: [], origins: [] },
  serviceWorkers: 'block',
  locale: 'en-US',
  viewport: { width: 1440, height: 1100 },
  screenshot: 'off',
  trace: 'off',
});

async function fillNewProduct(page) {
  await page.locator('input[name="name"]').fill('Fixture new product');
  await page.locator('input[name="sku"]').fill('FIXTURE-NEW');
  await page.locator('input[name="price"]').fill('25');
}

async function openEdit(page, mock) {
  await mock.open('/products/1/edit');
  await expect(page.locator('input[name="name"]')).toHaveValue('Fixture product');
  await expect(page.locator('input[name="cost"]')).toHaveValue('10');
}

function lossPanel(page, labels) {
  return page.getByRole('heading', { name: labels.inventory_loss_breakdown, exact: true }).locator('..');
}

async function expectCard(page, label, amount) {
  await expect(page.getByText(label, { exact: true }).locator('..').locator('h5')).toHaveText(amount);
}

test('new product blocks empty, negative and non-finite costs before any POST', async ({ page, mock }) => {
  await mock.open('/products/new');
  await fillNewProduct(page);
  const cost = page.locator('input[name="cost"]');
  await expect(cost).toHaveAttribute('required', '');
  await expect(cost).toBeEnabled();
  await expect(page.locator('input[name="stockQuantity"]')).toBeEditable();
  for (const value of ['', '-1', '1e999']) {
    await cost.fill('');
    if (value) await cost.pressSequentially(value);
    await page.getByRole('button', { name: inventory.create, exact: true }).click();
    expect(await cost.evaluate((input) => input.validity.valid), `Native validation rejects ${value || 'blank'}`).toBe(false);
    expect(mock.calls('POST /api/products')).toHaveLength(0);
    // Also exercise the React guard without the browser's native form gate.
    await page.locator('form').dispatchEvent('submit');
    await expect(page.getByRole('alert')).toHaveText(inventory.cost_required);
    expect(mock.calls('POST /api/products')).toHaveLength(0);
  }
});

for (const value of ['0', '12.34']) {
  test(`new product sends explicit numeric cost ${value}`, async ({ page, mock }) => {
    await mock.open('/products/new');
    await fillNewProduct(page);
    await page.locator('input[name="cost"]').fill(value);
    await page.locator('input[name="stockQuantity"]').fill('3');
    await page.getByRole('button', { name: inventory.create, exact: true }).click();
    await expect(page.getByRole('alert')).toHaveText(inventory.product_created);
    expect(mock.calls('POST /api/products')).toHaveLength(1);
    expect(mock.calls('POST /api/products')[0].body).toMatchObject({ costPrice: Number(value), stockQuantity: 3, unitPrice: 25 });
    expect(mock.calls('POST /api/products')[0].body).not.toHaveProperty('cost');
  });
}

test('edit protects cost and stock, sends version, and shows server stale message', async ({ page, mock }) => {
  mock.staleUpdate = true;
  await openEdit(page, mock);
  await expect(page.locator('input[name="cost"]')).toBeDisabled();
  await expect(page.getByText(inventory.cost_readonly_help, { exact: true })).toBeVisible();
  await expect(page.locator('input[name="stockQuantity"]')).toHaveCount(0);
  await page.locator('input[name="name"]').fill('Changed fixture name');
  await page.getByRole('button', { name: inventory.update, exact: true }).click();
  await expect(page.getByRole('alert')).toHaveText(STALE);
  expect(mock.calls('PUT /api/products/1')).toHaveLength(1);
  const payload = mock.calls('PUT /api/products/1')[0].body;
  expect(payload).toMatchObject({ name: 'Changed fixture name', version: 7 });
  for (const key of ['cost', 'costPrice', 'stockQuantity']) expect(payload).not.toHaveProperty(key);
  await expect(page).toHaveURL(`${BASE}/products/1/edit`);
  await expect(page.locator('input[name="name"]')).toHaveValue('Changed fixture name');
});

test('successful edit round-trips the returned version on a second save', async ({ page, mock }) => {
  await openEdit(page, mock);
  await page.getByRole('button', { name: inventory.update, exact: true }).click();
  await expect(page.getByRole('alert')).toHaveText(inventory.product_updated);
  mock.staleUpdate = true; // A second save is rejected without changing fixture data.
  await page.getByRole('button', { name: inventory.update, exact: true }).click();
  await expect(page.getByRole('alert')).toHaveText(STALE);
  expect(mock.calls('PUT /api/products/1').map((request) => request.body.version)).toEqual([7, 8]);
});

test('adjust cost requires valid cost and a trimmed ten-character reason', async ({ page, mock }) => {
  await openEdit(page, mock);
  await page.getByRole('button', { name: inventory.adjust_cost, exact: true }).click();
  const dialog = page.getByRole('dialog');
  const cost = dialog.getByRole('spinbutton');
  const reason = dialog.getByRole('textbox');
  const save = dialog.getByRole('button', { name: inventory.update, exact: true });
  await expect(cost).toHaveValue('10');
  for (const value of ['', '-1']) {
    await cost.fill(value);
    await save.click();
    await expect(dialog.getByRole('alert')).toHaveText(inventory.cost_required);
    expect(mock.calls('POST /api/products/1/cost-adjustment')).toHaveLength(0);
  }
  await cost.fill('12.5');
  for (const value of ['', 'short', '   123456789   ']) {
    await reason.fill(value);
    await save.click();
    await expect(dialog.getByRole('alert')).toHaveText(inventory.adjust_reason_min);
    expect(mock.calls('POST /api/products/1/cost-adjustment')).toHaveLength(0);
  }
  await reason.fill('  Correct invoice cost  ');
  await save.click();
  await expect(dialog).not.toBeVisible();
  await expect(page.getByRole('alert')).toHaveText(inventory.cost_adjusted);
  await expect(page.locator('input[name="cost"]')).toHaveValue('12.5');
  await expect(page.locator('input[name="cost"]')).toBeDisabled();
  expect(mock.calls('POST /api/products/1/cost-adjustment').map((request) => request.body)).toEqual([
    { newCost: 12.5, reason: 'Correct invoice cost', expectedVersion: 7 },
  ]);
  await expect.poll(() => mock.calls('GET /api/products/1').length).toBeGreaterThanOrEqual(2);
  mock.staleUpdate = true;
  await page.getByRole('button', { name: inventory.update, exact: true }).click();
  await expect(page.getByRole('alert').filter({ hasText: STALE })).toBeVisible();
  expect(mock.calls('PUT /api/products/1')[0].body.version).toBe(8);
});

test('stale cost adjustment stays open with a clear error and unchanged cost', async ({ page, mock }) => {
  mock.staleAdjustment = true;
  await openEdit(page, mock);
  await page.getByRole('button', { name: inventory.adjust_cost, exact: true }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('spinbutton').fill('15');
  await dialog.getByRole('textbox').fill('Correct invoice cost');
  await dialog.getByRole('button', { name: inventory.update, exact: true }).click();
  await expect(dialog.getByRole('alert')).toHaveText(STALE);
  await expect(dialog).toBeVisible();
  await expect(page.locator('input[name="cost"]')).toHaveValue('10');
  expect(mock.calls('POST /api/products/1/cost-adjustment')[0].body.expectedVersion).toBe(7);
});

for (const language of ['en', 'my', 'ja', 'th', 'fr']) {
  test(`accounting golden and empty loss breakdown are localized (${language})`, async ({ page, mock }) => {
    const labels = dictionary(language, 'accounting');
    await mock.open('/accounting', language);
    const panel = lossPanel(page, labels);
    await expect(panel.getByRole('row')).toHaveCount(3);
    await expect(panel.getByRole('row').nth(1)).toHaveText(`${labels.loss_spoilage_expiry}$120.00`);
    await expect(panel.getByRole('row').nth(2)).toHaveText(`${labels.inventory_loss}$120.00`);
    await expect(panel).not.toContainText('SPOILAGE_EXPIRY');
    await expectCard(page, labels.revenue, '$1,000.00');
    await expectCard(page, labels.cogs, '$600.00');
    await expectCard(page, labels.gross_profit, '$400.00');
    await expectCard(page, labels.total_expenses, '$120.00');
    await expectCard(page, labels.net_profit, '$280.00');
    const request = mock.calls('GET /api/reports/accounting-summary')[0];
    const params = new URLSearchParams(request.query);
    expect(Number(params.get('month'))).toBeGreaterThanOrEqual(1);
    expect(Number(params.get('month'))).toBeLessThanOrEqual(12);
    expect(params.get('year')).toMatch(/^\d{4}$/);
    expect(mock.calls('GET /api/expenses')).toHaveLength(1);
    mock.summary = { ...mock.summary, inventoryLossByReason: [], totalInventoryLoss: 0, totalExpenses: 0, netProfit: 400 };
    await page.getByRole('button', { name: labels.refresh, exact: true }).click();
    await expect(panel.getByText(labels.no_inventory_loss_month, { exact: true })).toBeVisible();
    await expect(panel.getByRole('table')).toHaveCount(0);
    await expectCard(page, labels.total_expenses, '$0.00');
    await expectCard(page, labels.net_profit, '$400.00');
    expect(mock.calls('GET /api/reports/accounting-summary')).toHaveLength(2);
    expect(mock.requests.filter((request) => !request.key.startsWith('GET '))).toHaveLength(0);
  });
}
