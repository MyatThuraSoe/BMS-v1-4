import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const posSource = readFileSync(new URL('../pages/POS.jsx', import.meta.url), 'utf8');

const handlerSource = () => {
  const start = posSource.indexOf('const handleOrderClick = () => {');
  assert.notEqual(start, -1, 'handleOrderClick must exist');
  const end = posSource.indexOf('const confirmOrder = ', start);
  return posSource.slice(start, end);
};

test('the Order dialog uses the same quantity validator as checkout', () => {
  const body = handlerSource();

  assert.match(body, /validateCartQuantities\(cart\)/,
    'the order path must reject blank/zero quantities instead of silently making them one');
  assert.doesNotMatch(body, /quantity:\s*1\s*}/,
    'an invalid quantity must never be rewritten to one unit');
});
