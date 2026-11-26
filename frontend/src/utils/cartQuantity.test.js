import test from 'node:test';
import assert from 'node:assert/strict';

import { validateCartQuantities } from './cartQuantity.js';

const item = (productId, quantity) => ({ productId, quantity, price: 10 });

test('a blank quantity is invalid and never becomes one', () => {
  const result = validateCartQuantities([item(1, '')]);

  assert.equal(result.ok, false);
  assert.deepEqual(result.invalidProductIds, [1]);
});

test('a zero or negative quantity is invalid', () => {
  assert.equal(validateCartQuantities([item(1, '0')]).ok, false);
  assert.equal(validateCartQuantities([item(1, -2)]).ok, false);
});

test('a valid quantity is normalised to an integer', () => {
  const result = validateCartQuantities([item(3, '2')]);

  assert.equal(result.ok, true);
  assert.deepEqual(result.cart, [{ productId: 3, quantity: 2, price: 10 }]);
});
