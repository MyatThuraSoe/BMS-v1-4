# BMS-v1-4 Technical Fix Specification: Third Pass (Regressions and Open Items)

Basis: fresh clone at commit `29fc3d8` (merge of PR #17, 2026-11-15), compared with the previously audited commit `5001de0`. Static review only; the build and tests were not run. Numbering: **R** items are regressions or defects introduced by PR #17, **O** items are findings from the first two specs that are still open or only partly fixed, **S** items are smaller new issues.

## 0. Scope and rules

- **In scope:** `bms-backend` and the web `frontend`. **Out of scope: the Android app. It is not touched.** Keep every REST contract backward compatible: new request fields optional, new response fields additive, no endpoint removed or renamed.
- One pull request per item. Order inside each PR: failing test first, then the fix, then a green run. Name tests after the item (`r1_creditReturnDebtReduction`).
- Money is `BigDecimal`, rounded `HALF_UP`. Every stock or cost mutation runs in one transaction under the product row lock.
- Schema changes use the existing idempotent startup runner (`AccountingSchemaMigration`). All new columns are nullable or defaulted.
- Lock order system-wide: document row (sale, purchase, transfer, count sheet), then item rows ascending id, then products ascending id, then location stock, then customer.
- **\[Decision\]** marks an owner choice. Implement the stated default, keep it in a system setting, and list it in the PR.

## 1. Status of the earlier findings at `29fc3d8`

| Fixed in PR #17 | Partly fixed | Still open |
| --- | --- | --- |
| F4, F7 (createSale), F8, F9, F10, F13, F17, F18, F19, F20, F21, F23, F24, F25, calendar quarter | F1/F2 (broken, see R1 and R2), F3 (cash AR only), F6 (no purchase lock), F11 (payment rows only), F22 (not order conversion) | F5, F12, F14, F15, F16 |

Phase order for this spec: **Phase 1** R1, R2 (money-moving regressions, release blockers). **Phase 2** O1–O4 (credit voids, RTV bucket, receive lock, bulk delete). **Phase 3** O5–O8 and R3. **Phase 4** S items and code quality.

---

## Phase 1: Credit-return regressions (release blockers)

### R1. Credit return reduces customer debt by the wrong amount (Critical)

**Files:** `SaleReturnService.createSaleReturn`, `reverseCreditBalance`.

**Current behavior.** In `createSaleReturn` the sequence is: line 369 `sale.setAmountReturned(returnedSoFar + totalReturnAmount)`, then line 389 `reverseCreditBalance(sale, totalReturnAmount)`. Inside `reverseCreditBalance`:

```java
BigDecimal returnedBefore = sale.getAmountReturned();   // already includes THIS return
BigDecimal outstandingBefore = total.subtract(paid).subtract(returnedBefore).max(ZERO);
BigDecimal debtReduction = returnAmount.min(outstandingBefore);
```

The variable is named "before" but holds the value after. Result on an unpaid invoice of 100: return 60 gives `outstandingBefore = 40` and a debt reduction of 40 instead of 60 (customer still owes 20 more than the invoice allows). A full return of 100 gives `outstandingBefore = 0` and **no debt reduction at all**. On an invoice of 100 with 80 paid, a return of 60 gives 0 instead of 20.

**Required change.** Compute the settlement figures once, at the top of `createSaleReturn`, from values read before any mutation, and pass them down:

```java
BigDecimal total          = nz(sale.getTotalAmount());
BigDecimal paidBefore     = nz(sale.getAmountPaid());
BigDecimal returnedBefore = nz(sale.getAmountReturned());
BigDecimal outstandingBefore = total.subtract(paidBefore).subtract(returnedBefore).max(ZERO);
// after totalReturnAmount is known:
BigDecimal debtReduction = isCredit ? totalReturnAmount.min(outstandingBefore) : ZERO;
BigDecimal refundDue     = totalReturnAmount.subtract(debtReduction);
```

`reverseCreditBalance(sale, debtReduction)` then takes the already-computed reduction and only touches the customer row (it must no longer read `amountReturned`). Update `amountReturned` and `paymentStatus` after the settlement numbers are fixed.

**Acceptance tests (add `SaleReturnServiceCreditTest`).**

- Invoice 100, unpaid, return 60: customer `currentBalance` falls by 60, `refundDue` 0, no cash movement.
- Invoice 100, unpaid, full return 100: balance falls by 100, invoice `PAID` (settled by return), no cash.
- Invoice 100, paid 80, return 60: balance falls by 20, `refundDue` 40.
- Two sequential returns (30 + 30) give the same totals as one return of 60.
- `currentBalance` never goes negative through the return path.

### R2. Credit return pays cash for an unpaid invoice (Critical)

**Files:** `SaleReturnService.resolveRefundMethod`, `createSaleReturn` (drawer posting), `LedgerPostingService.snapshotRefundSettlement`, `GlService.postSaleReturns`, `SaleReturnDialog.jsx`.

**Current behavior.** `resolveRefundMethod` now returns `CASH` for any credit sale when the user has an open shift. The line `if (CREDIT && !cashSettled && refundMethod == CREDIT) refundMethod = CREDIT;` is a no-op, so the old rule that forced `CREDIT` for unpaid invoices is gone. The drawer block runs `if (refundMethod == CASH && totalReturnAmount > 0)` and posts `CASH_REFUND` for the **full** amount, and then `reverseCreditBalance` also reduces the debt. The web dialog never sends `refundMethod`, so this is the default path: every credit return pays out cash from the drawer, including on invoices that were never paid, and the customer also gets the debt reduction.

**Required change.** Make the refund apply only to the part that exceeds the debt (the `refundDue` from R1).

1. Persist the split on `SaleReturn`: new nullable columns `debt_reduction_amount` and `refund_amount` (migration runner). For cash sales: `debt_reduction_amount = 0`, `refund_amount = total`.
2. Method resolution:
   - Credit sale and `refundDue == 0`: method is `CREDIT` (AR), no drawer movement, no shift needed.
   - `refundDue > 0`: use the request's `refundMethod` if present, else **\[Decision\]** default `CASH` when the caller has an open cash-drawer shift, otherwise `STORE_CREDIT`.
   - Cash sale: unchanged (request method, else derive from the sale's payment rows).
3. Drawer posting: `CASH_REFUND` for `refund_amount` only, and only when the method is `CASH` and `refund_amount > 0`. The drawer and shift validations (`CASH_REFUND_REQUIRES_DRAWER`) run only in that case, so pure debt-reduction returns work without a shift.
4. Customer: `currentBalance -= debtReduction`. If the method is `STORE_CREDIT`, `creditBalance += refundDue`.
5. Ledger and GL. `snapshotRefundSettlement` currently writes one account. Extend it to split the settlement by the two parts: the debt-reduction part to `settlement_ar` (negative, reverses AR), the refund part to `settlement_cash`, `settlement_clearing` (card or bank) or the refund-liability account (store credit). The profit-ledger columns for the three buckets already exist from F23, and `GlService.addSettlementSplit` already emits them. Update the legacy path `GlService.postSaleReturns` to use `debt_reduction_amount` and `refund_amount` the same way. Old return rows without the new columns keep the current single-account behavior.
6. Frontend: extend the returnable-items response with additive fields `outstandingBefore`, `estimatedDebtReduction` and `estimatedRefundDue` (computed server-side from the current selection, or returned for the full return), and show them in `SaleReturnDialog.jsx`. Add an optional refund-method selector that is shown only when the estimated refund is above zero. No change to the request when the selector is untouched.
7. Remove the dead no-op `if` after method resolution.

**Acceptance tests.**

- Credit invoice unpaid, return 60, cashier with open shift: no `CASH_REFUND` row, drawer expected cash unchanged, balance down 60.
- Credit invoice paid 80 of 100, return 60: debt reduction 20, cash refund 40 (drawer down 40), GL journal: AR credit 20, cash credit 40, revenue and tax debits for the return.
- Credit invoice fully paid by AR cash payments, return 60: cash refund 60, no balance change.
- Same with `STORE_CREDIT`: `creditBalance` +60, no drawer movement.
- Cash sale return behaves exactly as before.
- GL for the period still balances (debits equal credits) after each case.

---

## Phase 2: Open and partly fixed items from the first spec

### O1. Void of a credit sale still loses non-cash AR payments (F3, partial)

**Files:** `SaleService.voidSale`, `ArPayment`, `ArService`.

**Current behavior.** PR #17 adds cash AR payments to `cashToReturn`. Non-cash AR payments (card, bank, wallet) are ignored, and the customer balance reversal only reverses the outstanding part, so money paid by card for a voided credit invoice vanishes.

**Required change.**

1. In `voidSale` compute `arNonCashPaid` = sum of non-cash AR payments for the invoice, net of refunds already made through returns.
2. **\[Decision\]** Default: convert `arNonCashPaid` to `Customer.creditBalance` (store credit) so the money is recoverable and no external refund is faked. Option: refund to the original tender through an external reference field. The default is the safe one.
3. Mark each AR payment as reversed: add nullable `reversed_at` and `reversal_note` to `ar_payment`. Payments with `reversed_at` are ignored by AR statements and by `sumPaid` for the invoice.
4. GL: the void reversal for credit invoices must credit the same accounts the collections debited (cash for cash payments, tender clearing for others, customer credit liability for the converted part).
5. Keep the existing rule that outstanding debt is reversed only once.

**Acceptance tests.** Credit invoice 100 paid 80 by card, voided: customer `creditBalance` +80, balance down 20, stock restored. Paid 60 cash plus 20 card: cash refund 60 from the drawer (or late-void pay-out), credit +20. Void twice is rejected (already fixed by locking).

### O2. Return to vendor still draws from the wrong stock bucket (F5)

**Files:** `PurchaseService.returnToVendor`, `reversePurchaseItem`, `quarantineAvailableForItem`.

**Current behavior.** RTV takes from quarantine, then from shop stock (`removeShopStock`), ignoring warehouse or other non-shop receipts. PR #17 only added the reserved-stock override.

**Required change.** Extract one helper used by both flows:

```java
void removeUnitsFromReceiptLocations(Purchase p, PurchaseItem item, Product product,
                                     int qty, boolean overrideReserved, String reason, Long userId)
```

Per location code, available = units this purchase item received there minus units already removed from there (movement types RTV out, PURCHASE out, TRANSFER out attributed to the item). Draw order: quarantine, then non-shop locations by id, then shop (shop last, with the reserved-stock check). Shortfall throws the existing "short N unit(s)" message. `reversePurchaseItem` already follows receipt locations, so reuse its logic and delete the duplicate.

**Acceptance tests.** Received into warehouse W (10), shop holds 5 from another PO, RTV 4: W falls by 4, shop stays 5. Mixed receipt (6 shop, 4 quarantine), RTV 5: quarantine 4 then shop 1. Two POs with quarantined goods stay isolated.

### O3. Receiving is not locked (F6, partial)

**Files:** `PurchaseService.receivePurchase`, `returnToVendor`, `deletePurchase`, `updatePurchase`, `PurchaseRepository`.

Duplicate receive lines are now rejected, but `purchaseRepository.findById` is still unlocked, so two concurrent receives of the same item both read the old `receivedQuantity` and both pass the over-shipment check. Add `findByIdForUpdate` (pessimistic write) to `PurchaseRepository` and use it as the first read in `receivePurchase`, `returnToVendor`, `deletePurchase` and `updatePurchase`; lock products afterwards in ascending id. Because pessimistic locking is a no-op on a single-writer SQLite file, also add a guarded update on the receiving status (`UPDATE purchase SET receiving_status = :next WHERE id = :id AND receiving_status = :expected`) and re-check item quantities after the lock.

**Acceptance tests.** Two threads receiving 10 on an order of 10: one succeeds, the other is rejected or needs the manager override. RTV racing a delete: one wins, stock and ledger consistent.

### O4. Bulk delete of old sales is still unsafe (F11, partial)

**Files:** `SaleService.deleteSalesOlderThanYears`, `SaleController`.

PR #17 added deletion of `SalePayment` rows. Still open: closed-period sales can be deleted, `StockMovement` rows are deleted by `referenceId IN saleIds` (collides with return and adjustment ids), `ArPayment` and return rows are not handled, and ledger and drawer rows stay referencing deleted sales.

**Required change.** **\[Decision\]** Default: replace hard delete with archival (`sale.archived_at`), exclude archived rows from default queries and exports, keep all ledger and movement rows. If hard delete must remain: admin only, backup confirmation, refuse any sale in a closed accounting period or with an outstanding AR balance, delete in FK order (`SaleReturnItem`, `SaleReturn`, `SalePayment`, `ArPayment`, `SaleItem`, `Sale`), and delete movements through new columns `source_sale_id` and `source_return_id` (nullable, same migration as `source_transfer_id`) instead of `referenceId`. Never delete ledger rows.

**Acceptance tests.** Delete with payments and returns present succeeds with FK checks on. Sale in a closed period is rejected. Unrelated movements with equal `referenceId` survive.

---

## Phase 3: Remaining open items

### O5. Cashier discount has no authority limit (F12)

**Files:** `SaleService.computeDiscount`, `createSale`, `verifyCart`, `Settings.jsx`.

Add settings `shop.discount.maxPercentCashier` (default 10) and `shop.discount.maxPercentManager` (default 100). Reject a header discount above the cashier limit unless the user is a manager or admin, or manager credentials are supplied the same way `receivePurchase` accepts them. Run the below-cost policy after discount allocation, comparing each line's net unit price (`lineNet / quantity`) against `costPriceAtSale`. Log every discount above zero with user and amount in the audit table.

**Acceptance tests.** Cashier 50%: rejected. Manager override accepted. A net price below cost follows `BelowCostPolicy`.

### O6. Orders drop promotion fields on conversion (F14)

**Files:** `OrderItem`, `OrderService.createOrder`, `SaleService.createSaleFromOrder`.

Store `originalUnitPrice`, `lineDiscount`, `promotionId`, `promotionName` on order items and `promotionDiscount`, `priceListDiscount` on the order (nullable). Copy them to the sale and sale items on conversion so promotion reports match direct sales. No backfill. Also in `createSaleFromOrder`, after releasing the order's own reservation, assert `stock >= reserved by other orders` (the part of F22 still missing there).

### O7. Legacy shift close counts refunds from all shifts (F15)

**Files:** `CashShiftService.closeShift` (no-session branch), `SaleRepository.sumRefundsBetween`.

Filter refunds by `employeeShift.id` instead of the time window. Test with two simultaneous shifts.

### O8. Pending-order reservations never expire (F16)

Setting `shop.order.reservationHours` (default 0, no expiry). A scheduled job cancels `PENDING` orders older than the limit through `OrderService.cancelOrder` (system user, reason "Reservation expired"), freeing reserved stock. The STOCK\_RESERVED error text already points to this.

---

## Phase 4: Smaller new issues and code quality

### S1. Auto-approved count submit can fail with a 422 and lose the submission

**Files:** `StockCountService.submit`, `applySheet`.

Auto-approval calls `applySheet`, which recomputes variances against live stock and throws `UnprocessableEntityException` when a line has a new variance and no reason code. The whole `submit` transaction rolls back, so the counter's counts are lost. **Required change:** in `submit`, pre-check the reason-code rule (same logic, without throwing); if any line would need a reason, skip auto-approval and leave the sheet in `PENDING_REVIEW` with the counts saved. `approve` keeps the strict 422 for the reviewer. Test: submission with a variance that appears only after live recompute ends in `PENDING_REVIEW`, counts persisted.

### S2. Invalid settings crash count submit and sync

`decimalSetting` and `integerSetting` call `new BigDecimal(...)` and `Integer.parseInt(...)` without handling bad input, where the earlier code fell back to defaults. **Required change:** catch `NumberFormatException`, log a warning, use the default, and validate the value when it is saved in `SystemSettingService` (reject non-numeric or negative values with a 400). Test: a value of "abc" for `stockCountAutoApproveLineValue` leaves submit working with the default.

### S3. Count gains are not posted as inventory gains

`ProductService.adjustStock` posts positive `COUNT_CORRECTION` and `SUPPLIER_ERROR` adjustments through `postInventoryGain`, but `StockCountService.applySheet` only calls `postOperatingLoss`, so a positive count variance raises inventory with no profit-ledger entry. **Required change:** in `applySheet`, call `postInventoryGain` for positive variances using reason `COUNT_CORRECTION` (extend `postInventoryGain` to accept `STOCK_COUNT` movements). The GL already maps the gain row to inventory and the adjustment account. Test: count +2 at cost 5 creates a profit-ledger gain of 10 and a balanced journal.

### S4. Legacy stock-adjust endpoint rejects negative adjustments

`POST /api/products/{id}/stock/adjust` passes a null `reasonCode`, and `adjustStock` now throws "A reason code is required" for negative changes. The web UI uses the inventory endpoint, but older clients of the legacy route break. **Required change:** add an optional `reasonCode` request parameter to the legacy route; when it is absent, map to `COUNT_CORRECTION` for that route only (now a posted shrinkage loss, so no hidden loss). Document the behavior in the API notes. Test: negative adjustment through the legacy route succeeds and posts a loss row.

### S5. Code quality

- Many services use `@Autowired private ShopTimeService shopTimeService = new ShopTimeService(null, "UTC");` with misaligned indentation. In tests that do not wire the field, this silently falls back to UTC with a null repository. Replace with constructor or plain `@Autowired` injection without an initializer, and let tests provide a stub bean. Run the formatter.
- `SaleService.voidSale` uses `findByIdForUpdate(...).orElseGet(() -> findById(...))`; the fallback can never find a row the locked query missed. Use the locked query only.
- Add the new migrations and settings to `DatabaseSeeder` defaults in one place (tolerance and count thresholds are already there).

---

## 2. Schema and settings summary (all additive)

| Table | Column |
| --- | --- |
| `sale_return` | `debt_reduction_amount`, `refund_amount` |
| `ar_payment` | `reversed_at`, `reversal_note` |
| `stock_movement` | `source_sale_id`, `source_return_id` (with `source_transfer_id` already present) |
| `sale` | `archived_at` (if archival is chosen for O4) |
| `order_item` / `orders` | promotion fields from O6 |

New settings: `shop.discount.maxPercentCashier`, `shop.discount.maxPercentManager`, `shop.order.reservationHours`.

## 3. API compatibility checklist (Android untouched)

- No endpoint removed or renamed; no request field changes meaning.
- `refundMethod` stays optional on the return request; new response fields (`outstandingBefore`, `estimatedDebtReduction`, `estimatedRefundDue`, `debtReductionAmount`, `refundAmount`) are additive.
- Legacy stock-adjust route keeps working (S4).
- Run the existing e2e specs under `frontend/e2e` after each phase.

## 4. Test plan

| Item | Tests (each must fail on the current code first) |
| --- | --- |
| R1 | `r1_unpaidCreditReturnReducesDebtFully`, `r1_fullReturnClearsDebt`, `r1_partialPaidReturnSplitsDebtAndRefund`, `r1_sequentialReturnsEqualSingle` |
| R2 | `r2_unpaidCreditReturnMovesNoCash`, `r2_partialPaidReturnRefundsExcessOnly`, `r2_paidCreditReturnRefundsCash`, `r2_glBalancesForSplitReturn` |
| O1 | `o1_voidCreditSaleConvertsCardArPaymentsToCredit`, `o1_voidCreditSaleRefundsCashArPayments` |
| O2 | `o2_rtvDrawsFromReceiptLocation`, `o2_rtvMixedReceiptOrder` |
| O3 | `o3_concurrentReceiveOnlyOneSucceeds` |
| O4 | `o4_bulkDeleteRespectsPeriodLockAndFk`, `o4_unrelatedMovementsSurvive` |
| O5–O8 | `o5_cashierDiscountLimit`, `o6_orderKeepsPromotionFields`, `o7_closeShiftRefundsPerShift`, `o8_expiredReservationsReleased` |
| S1–S4 | `s1_autoApproveFallsBackToReview`, `s2_badSettingUsesDefault`, `s3_countGainPostsInventoryGain`, `s4_legacyAdjustRoute` |

After each phase re-run `SaleServiceVoidCashTrailTest`, `StockCountServiceTest`, `GlServiceJournalBalanceTest`, `GlJournalIntegrationTest`, `ReportProfitAggregationIntegrationTest` and `InventoryLossServiceTest`, and check that GL debits equal credits for a full month that includes credit returns, partial returns and voids.

## 5. Report template per item

What changed, files touched, tests added, any deviation from this spec, owner decisions applied (with the setting that flips each one), and confirmation that no Android-facing contract changed.
