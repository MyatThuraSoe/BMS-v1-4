#!/usr/bin/env node
/**
 * LumiPOS sale-flow smoke test.
 *
 * Exercises the core money path against a RUNNING backend:
 *   login -> ensure category -> ensure product -> ensure open shift
 *   -> verify cart -> create cash sale -> fetch receipt
 *
 * Usage (backend must be running, e.g. via Electron or mvn spring-boot:run):
 *   node scripts/smoke-test.mjs
 *   SMOKE_BASE_URL=http://127.0.0.1:17234 SMOKE_USER=admin SMOKE_PASS=yourpass node scripts/smoke-test.mjs
 *
 * Exits 0 on success, 1 on any failure. Safe to run repeatedly — it tags
 * everything with "SMOKE" so test artifacts are easy to spot.
 */

const BASE = process.env.SMOKE_BASE_URL || 'http://127.0.0.1:17234';
const USER = process.env.SMOKE_USER || 'admin';
const PASS = process.env.SMOKE_PASS || 'admin123';

let token = null;
let passed = 0;
let failed = 0;

function ok(name, cond, detail = '') {
  if (cond) {
    passed++;
    console.log(`  \x1b[32mPASS\x1b[0m ${name}`);
  } else {
    failed++;
    console.log(`  \x1b[31mFAIL\x1b[0m ${name}${detail ? ` — ${detail}` : ''}`);
  }
}

async function api(method, path, body) {
  const res = await fetch(`${BASE}/api${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  let json = null;
  try { json = await res.json(); } catch { /* non-JSON */ }
  return { status: res.status, json };
}

async function main() {
  console.log(`\nLumiPOS smoke test → ${BASE}\n`);

  // 1) Login
  const login = await api('POST', '/auth/login', { username: USER, password: PASS });
  token = login.json?.data?.accessToken || login.json?.data?.token || null;
  ok('login returns a JWT', Boolean(token), JSON.stringify(login.json?.message || ''));

  // 2) Ensure a category exists
  let categories = await api('GET', '/categories');
  const catList = categories.json?.data?.content || categories.json?.data || [];
  let category = Array.isArray(catList) ? catList.find((c) => c.name === 'SMOKE') : null;
  if (!category) {
    const created = await api('POST', '/categories', { name: 'SMOKE' });
    category = created.json?.data;
  }
  ok('category available', Boolean(category?.id));

  // 3) Create the smoke product
  const sku = `SMOKE-${Date.now()}`;
  const product = await api('POST', '/products', {
    sku,
    name: 'SMOKE Test Item',
    description: 'Created by smoke-test.mjs',
    categoryId: category?.id ?? null,
    unitPrice: 2.5,
    costPrice: 1.0,
    taxRate: 0,
    stockQuantity: 10,
    minStockLevel: 2,
    unit: 'pcs',
  });
  const productId = product.json?.data?.id;
  ok('product created with stock', Boolean(productId), JSON.stringify(product.json?.message || ''));

  // 4) Ensure an open cash shift (cash sales require one)
  let shift = await api('GET', '/shifts/current');
  if (!shift.json?.data) {
    const opened = await api('POST', '/shifts/open', { openingAmount: 1 });
    shift = opened;
  }
  ok('cash shift is open', Boolean(shift.json?.data), JSON.stringify(shift.json?.message || ''));

  // 5) Verify cart (authoritative pricing)
  const verify = await api('POST', '/sales/verify-cart', {
    items: [{ productId, quantity: 2, expectedUnitPrice: 2.5 }],
  });
  ok('verify-cart valid', verify.json?.data?.valid === true, JSON.stringify(verify.json?.data?.messages || []));
  ok('verify-cart totals present', Number(verify.json?.data?.totalAmount) > 0);

  // Expected totals are computed from the LIVE shop config (tax %, discount
  // mode/value) — never hardcoded, since admins change these freely.
  const shopInfo = await api('GET', '/shop-info');
  const cfg = shopInfo.json?.data || {};
  const SUBTOTAL = 5; // 2 x 2.50
  const AMOUNT_PAID = 100000;
  // Match the backend's BigDecimal HALF_UP 2dp rounding (plain toFixed
  // mis-rounds binary floats like 0.015).
  const round2 = (n) => Math.round((n + Number.EPSILON) * 100) / 100;
  const taxRate = Number(cfg.taxPercentage) || 0;
  const taxAmt = round2(SUBTOTAL * taxRate / 100);
  let discountAmt = 0;
  if (cfg.discountEnabled) {
    if (cfg.discountType === 'FIXED' || cfg.discountType === 'AMOUNT') {
      discountAmt = Math.min(Number(cfg.discountValue) || 0, SUBTOTAL);
    } else {
      discountAmt = round2(SUBTOTAL * (Number(cfg.discountValue) || 0) / 100);
      discountAmt = Math.min(discountAmt, SUBTOTAL);
    }
  }
  const EXPECTED_TOTAL = round2(SUBTOTAL + taxAmt - discountAmt);

  // 6) Create the cash sale
  const sale = await api('POST', '/sales', {
    items: [{ productId, quantity: 2 }],
    customerName: 'SMOKE Walk-in',
    paymentMethod: 'CASH',
    saleType: 'CASH',
    amountPaid: AMOUNT_PAID,
  });
  const invoice = sale.json?.data?.invoiceNumber;
  const total = sale.json?.data?.totalAmount;
  const change = sale.json?.data?.changeGiven;
  ok('sale created', Boolean(invoice), JSON.stringify(sale.json?.message || ''));
  ok(
    `total matches shop config (${EXPECTED_TOTAL.toFixed(2)}; tax ${taxRate}%, discount ${cfg.discountEnabled ? `${cfg.discountType} ${cfg.discountValue}` : 'off'})`,
    Math.abs(Number(total) - EXPECTED_TOTAL) < 0.005,
    `got ${total}`,
  );
  ok(
    `change = paid - total (${(AMOUNT_PAID - EXPECTED_TOTAL).toFixed(2)})`,
    Math.abs(Number(change) - (AMOUNT_PAID - EXPECTED_TOTAL)) < 0.005,
    `got ${change}`,
  );

  // 7) Receipt retrievable
  const receipt = await api('GET', `/receipts/invoice/${encodeURIComponent(invoice)}`);
  ok('receipt retrievable by invoice number', receipt.status === 200 && Boolean(receipt.json?.data?.invoiceNumber));

  // 8) Credit sale -> creates an AR invoice with the due date
  const customer = await api('POST', '/customers', {
    firstName: 'SMOKE',
    lastName: `Credit${Date.now() % 100000}`,
    phone: `099${Date.now() % 1000000}`,
    creditLimit: 100000,
  });
  const customerId = customer.json?.data?.id;
  ok('credit customer created', Boolean(customerId), JSON.stringify(customer.json?.message || ''));

  const creditSale = await api('POST', '/sales', {
    items: [{ productId, quantity: 1 }],
    customerId,
    paymentMethod: 'CREDIT',
    saleType: 'CREDIT',
    dueDate: new Date(Date.now() + 7 * 864e5).toISOString().slice(0, 10),
    amountPaid: 0,
  });
  const creditInvoice = creditSale.json?.data;
  const creditSaleId = creditSale.json?.data?.id;
  ok(
    'credit sale created (CR- invoice, UNPAID)',
    String(creditInvoice?.invoiceNumber || '').startsWith('CR-') && creditInvoice?.paymentStatus === 'UNPAID',
    JSON.stringify(creditSale.json?.message || ''),
  );

  // 9) Void the cash sale — stock must be restored
  const stockBefore = (await api('GET', `/products/${productId}`)).json?.data;
  const voidRes = await api('POST', `/sales/${sale.json.data.id}/void?reason=${encodeURIComponent('SMOKE test void')}`);
  ok('cash sale voided', voidRes.status === 200 && voidRes.json?.data?.isVoided === true, JSON.stringify(voidRes.json?.message || ''));
  const stockAfter = (await api('GET', `/products/${productId}`)).json?.data;
  const beforeQty = stockBefore?.stockQuantity ?? stockBefore?.availableQuantity;
  const afterQty = stockAfter?.stockQuantity ?? stockAfter?.availableQuantity;
  ok('stock restored after void (+2)', afterQty === (beforeQty ?? -999) + 2, `${beforeQty} -> ${afterQty}`);

  // Summary
  console.log(`\n${passed} passed, ${failed} failed\n`);
  if (failed > 0) process.exit(1);
}

main().catch((e) => {
  console.error(`\nSmoke test crashed: ${e.message}\n(Is the backend running at ${BASE}?)`);
  process.exit(1);
});
