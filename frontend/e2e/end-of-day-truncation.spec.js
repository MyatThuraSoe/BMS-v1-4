// P7 acceptance (UI): the end-of-day report must warn when the backend caps the
// day's sales (truncated/rowCount) instead of silently dropping rows. Fully
// isolated — one fail-closed handler owns every browser request, so no call can
// reach a real backend or production data.
import { test as base, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';

const BASE = 'http://127.0.0.1:3000';
const dictionary = (language, namespace) => JSON.parse(readFileSync(
  new URL(`../public/locales/${language}/${namespace}.json`, import.meta.url), 'utf8',
));
const reports = dictionary('en', 'reports');

const test = base.extend({
  mock: async ({ context, page }, use, testInfo) => {
    const state = {
      language: 'en',
      endOfDay: {
        truncated: true,
        rowCount: 50000,
        generatedAt: '2026-11-13T23:59:59',
        businessSummary: {
          transactionCount: 50000, grossSales: 1250000, refunds: 2500,
          netSales: 1247500, cashSales: 900000, digitalSales: 347500,
        },
        paymentMethods: [
          { method: 'CASH', transactionCount: 40000, amount: 900000 },
          { method: 'CARD', transactionCount: 10000, amount: 347500 },
        ],
        cashDrawer: {
          openingFloat: 500, cashSales: 900000, cashRefunds: 2500,
          expectedCash: 898000, countedCash: 898000, variance: 0,
        },
        exceptions: [],
      },
      requests: [], unhandled: [], external: [], pageErrors: [], console: [], failed: [],
    };
    page.on('pageerror', (error) => state.pageErrors.push(error.message));
    page.on('console', (message) => {
      if (['error', 'warning'].includes(message.type())) {
        state.console.push({ type: message.type(), text: message.text(), url: message.location().url });
      }
    });
    page.on('requestfailed', (request) => state.failed.push({ url: request.url(), error: request.failure()?.errorText }));

    await context.route('**/*', async (route) => {
      const request = route.request();
      const url = new URL(request.url());
      if (url.origin !== BASE) {
        state.external.push(request.url());
        return route.abort('blockedbyclient');
      }
      if (url.pathname !== '/api' && !url.pathname.startsWith('/api/')) {
        return route.continue(); // Vite UI/modules/local translation assets only.
      }
      const key = `${request.method()} ${url.pathname}`;
      state.requests.push({ key, query: url.search });
      const reply = (data, status = 200) => route.fulfill({
        status, contentType: 'application/json', body: JSON.stringify({ success: status < 400, data }),
      });
      switch (key) {
        case 'GET /api/health': return reply({ status: 'UP' });
        case 'GET /api/shop-info': return reply({ shopName: 'Isolated fixture shop', currency: 'USD', hasLogo: false });
        case 'GET /api/shifts/current': return reply(null);
        case 'GET /api/license/status': return reply({ licensed: true, plan: 'lifetime' });
        case 'GET /api/reports/daily-sales': return reply({});
        case 'GET /api/reports/end-of-day': return reply(state.endOfDay);
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
      console.log(JSON.stringify({
        test: testInfo.title,
        fetches: [...new Set(state.requests.map((request) => request.key + request.query))],
        unhandled: state.unhandled, blockedExternal: state.external,
        pageErrors: state.pageErrors, console: state.console, failedRequests: state.failed,
      }));
      expect(state.unhandled, 'No API call may fall through to the real backend').toEqual([]);
      expect(state.external, 'Only the task origin may be requested').toEqual([]);
      expect(state.pageErrors).toEqual([]);
      expect(state.console.filter((entry) => entry.type === 'error')).toEqual([]);
    }
  },
});

test.use({
  baseURL: BASE,
  ...(process.env.COST_TEST_CHANNEL ? { channel: process.env.COST_TEST_CHANNEL } : {}),
  launchOptions: { executablePath: process.env.E2E_CHROMIUM_PATH || undefined },
  storageState: { cookies: [], origins: [] },
  serviceWorkers: 'block',
  locale: 'en-US',
  viewport: { width: 1440, height: 1100 },
  screenshot: 'off',
  trace: 'off',
});

const truncationAlert = (page) => page.getByRole('alert').filter({ hasText: 'more sales than the report can load' });

async function openEndOfDay(page, mock) {
  await mock.open('/reports');
  await page.locator('select').first().selectOption('endOfDay');
  await expect(page.getByRole('heading', { name: reports.end_of_day_title, exact: true })).toBeVisible();
}

test('capped end-of-day report shows a truncation warning with the row count', async ({ page, mock }) => {
  await openEndOfDay(page, mock);
  const alert = truncationAlert(page);
  await expect(alert).toBeVisible();
  await expect(alert).toContainText('50000');
  await expect(alert).toContainText(reports.end_of_day_truncated.replace('{{rowCount}}', '50000'));
  // The rest of the report still renders beneath the warning.
  await expect(page.getByRole('heading', { name: reports.payments_by_method, exact: true })).toBeVisible();
  expect(mock.calls('GET /api/reports/end-of-day')).toHaveLength(1);
});

test('uncapped end-of-day report shows no truncation warning', async ({ page, mock }) => {
  mock.endOfDay = { ...mock.endOfDay, truncated: false, rowCount: 12 };
  await openEndOfDay(page, mock);
  await expect(page.getByRole('heading', { name: reports.payments_by_method, exact: true })).toBeVisible();
  await expect(truncationAlert(page)).toHaveCount(0);
  expect(mock.calls('GET /api/reports/end-of-day')).toHaveLength(1);
});