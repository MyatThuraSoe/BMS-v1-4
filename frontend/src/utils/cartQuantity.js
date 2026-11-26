/**
 * Cart quantities must be explicit, positive integers. A blank field while the
 * cashier is typing is fine, but it must never silently become "1" on checkout.
 */
export function validateCartQuantities(cart) {
  const invalidProductIds = [];
  const normalized = cart.map((item) => {
    const quantity = parseInt(item.quantity, 10);
    if (!Number.isInteger(quantity) || quantity <= 0) {
      invalidProductIds.push(item.productId);
      return item;
    }
    return { ...item, quantity };
  });

  if (invalidProductIds.length > 0) {
    return { ok: false, invalidProductIds };
  }
  return { ok: true, cart: normalized };
}
