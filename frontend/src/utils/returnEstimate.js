const cents = (value) => Math.round((Number(value) || 0) * 100) / 100;

/**
 * What the return dialog shows while the cashier is still picking lines.
 *
 * The server snapshot prices the WHOLE invoice, so it is only a fallback for the
 * moment nothing is selected. Once there is a selection the numbers must follow
 * it: the debt reduction is capped by what the invoice still owes, and whatever
 * is left over is the refund the cashier has to hand back.
 *
 * A sale whose server debt is zero has no debt to reduce (cash sale, or a credit
 * invoice already paid in full), so the whole selection becomes the refund.
 */
export function estimateSettlement({ selectedTotal, outstandingBefore, serverDebt, serverRefund }) {
  const selected = cents(selectedTotal);
  const debt = cents(serverDebt);
  if (selected <= 0) {
    const fallbackRefund = cents(serverRefund);
    return { debt, refund: fallbackRefund, showRefundMethod: fallbackRefund > 0 };
  }
  const reduced = debt > 0 ? Math.min(selected, cents(outstandingBefore)) : 0;
  const refund = cents(selected - reduced);
  return { debt: cents(reduced), refund, showRefundMethod: refund > 0 };
}
