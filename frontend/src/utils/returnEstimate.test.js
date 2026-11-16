import test from 'node:test';
import assert from 'node:assert/strict';

import { estimateSettlement } from './returnEstimate.js';

// N5 acceptance: a credit invoice with 20 still outstanding.
const creditInvoice = { outstandingBefore: 20, serverDebt: 20, serverRefund: 40 };

test('nothing selected falls back to the server snapshot', () => {
  assert.deepEqual(estimateSettlement({ ...creditInvoice, selectedTotal: 0 }),
    { debt: 20, refund: 40, showRefundMethod: true });
});

test('a selection worth less than the debt refunds nothing', () => {
  const estimate = estimateSettlement({ ...creditInvoice, selectedTotal: 15 });
  assert.equal(estimate.debt, 15);
  assert.equal(estimate.refund, 0);
  assert.equal(estimate.showRefundMethod, false, 'no refund means no refund method to pick');
});

test('a bigger selection clears the debt and refunds the rest', () => {
  const estimate = estimateSettlement({ ...creditInvoice, selectedTotal: 60 });
  assert.equal(estimate.debt, 20);
  assert.equal(estimate.refund, 40);
  assert.equal(estimate.showRefundMethod, true);
});

test('a cash sale refunds the whole selection', () => {
  const estimate = estimateSettlement({
    outstandingBefore: 0, serverDebt: 0, serverRefund: 30, selectedTotal: 30.004,
  });
  assert.equal(estimate.debt, 0);
  assert.equal(estimate.refund, 30, 'money is rounded to cents, never carried as float noise');
});

test('float noise from the line totals is rounded once', () => {
  const estimate = estimateSettlement({
    outstandingBefore: 0.3, serverDebt: 0.3, serverRefund: 0, selectedTotal: 0.1 + 0.2,
  });
  assert.equal(estimate.debt, 0.3);
  assert.equal(estimate.refund, 0);
});
