# BMS-v1-4 Business Logic Fix Specification (Audit 2)

Scope: `bms-backend` (Spring Boot / JPA). Basis: static code review of the `main` branch. No tests were run, so every item below must first be reproduced with a failing test, then fixed. Items are numbered to match the audit summary (F1–F16 plus low-priority notes).

## 0. Working rules

- One pull request per item (or tightly related pair). Order: failing test, fix, green test.
- Money is `BigDecimal`, rounded `HALF_UP` at posting. Every stock or cost mutation runs in one transaction holding the product row lock (`findByIdForUpdate`).
- Do not change behavior outside the item. Keep existing invariants: `costPriceAtSale` immutable, `SaleDiscountUtils` allocation, `LedgerPostingService` as sole writer of `Product.costPrice`, period guard on every posting.
- Owner decisions are marked **\[Decision\]**. Implement the default, keep it in a named constant or system setting, and list it in the PR.

## 1. Priority order

| Phase | Items | Theme |
| --- | --- | --- |
| 1 | F1, F2, F3 | Credit-invoice money loss (returns, voids) |
| 2 | F4, F6, F7 | Concurrency and validation holes |
| 3 | F5, F8 | Purchasing stock/payable correctness |
| 4 | F9, F10, F11, F12 | Time, payment status, bulk delete, discount authority |
| 5 | F13–F16 and low items | Hardening |

---

## Phase 1: Credit-invoice money loss

### F1. Return of a fully paid credit invoice refunds nothing (High)

**Files:** `SaleReturnService.createSaleReturn`, `resolveRefundMethod`; frontend `SaleReturnDialog.jsx`.

**Problem.** The dialog never sends `refundMethod`. `resolveRefundMethod` falls back to the sale's payment rows. A credit sale has a single `CREDIT` payment line, so the method resolves to `CREDIT`. When the invoice is already `PAID`, `cashSettled` is true, so `reverseCreditBalance` is skipped, and since the method is not `CASH` no drawer movement is written. Result: stock is restocked and `amountReturned` increases, but the customer receives no cash and no credit.

**Required change.**

1. For a credit sale, never derive the refund method from the original payment rows (`CREDIT` is not a tender).
2. Compute the refundable portion explicitly (see F2): `refundDue = max(0, paid - (totalAmount - amountReturnedBeforeThisReturn - thisReturnAmount))`, equivalently the part of the return that exceeds the remaining unpaid balance.
3. Pay `refundDue` out by the chosen method: `CASH` (drawer `CASH_REFUND`, needs open drawer shift as today), or `STORE_CREDIT` (add to `Customer.creditBalance`). **\[Decision\]** default: `CASH` if the caller has an open cash drawer shift, otherwise `STORE_CREDIT`.
4. Accept an optional `refundMethod` in the request and add a selector to `SaleReturnDialog.jsx`; default per the decision above.
5. Record the actual tender in `SaleReturn.refundMethod` so `LedgerPostingService.refundTender` posts the right GL settlement account (cash, `REFUND_LIABILITY`, not `ACCOUNTS_RECEIVABLE`).

**Acceptance tests.**

- Credit sale 100, fully paid by AR payments, return worth 60 with no `refundMethod`: customer receives 60 (cash movement of 60, or `creditBalance` +60). Invoice stays `PAID`. `amountReturned` = 60.
- Same with `STORE_CREDIT`: `creditBalance` +60, no drawer movement.
- Cash refund with no open drawer shift is rejected and nothing is mutated.

### F2. Return on a partially paid credit invoice reduces the wrong debt (High)

**Files:** `SaleReturnService.reverseCreditBalance(Sale, BigDecimal)` and its call site.

**Problem.** For `UNPAID`/`PARTIAL` invoices, `currentBalance` is reduced by the full return amount, even when it exceeds what is still owed on this invoice. Example: total 100, paid 80, outstanding 20, return worth 60. Balance falls by 60. Only 20 was owed here, so 40 silently offsets the customer's other invoices (or goes to `creditBalance` if there is no other debt). The customer who already paid 80 for goods now worth 40 net is not refunded.

**Required change.**

```java
BigDecimal outstandingBefore = total.subtract(paid).subtract(returnedBefore).max(ZERO);
BigDecimal debtReduction     = returnAmount.min(outstandingBefore);
BigDecimal refundDue         = returnAmount.subtract(debtReduction);
```

- Reduce `currentBalance` by `debtReduction` only.
- Handle `refundDue` exactly as F1.
- Settlement status: PAID when `paid + amountReturned >= total` (existing rule). Keep it.

**Acceptance tests.**

- total 100, paid 80, return 60: balance down 20, `refundDue` 40, customer's other invoices untouched.
- total 100, paid 0, return 60: balance down 60, `refundDue` 0.
- Two sequential returns (30 + 30) give the same totals as one return of 60.
- Customer currentBalance never goes negative through this path; `creditBalance` only changes via the explicit refund choice.

### F3. Voiding a credit sale loses money already paid (High)

**Files:** `SaleService.voidSale`, `reverseCreditBalance(Sale)`.

**Problem.** `cashToReturn` is computed only from `SalePayment` rows with method `CASH`. A credit sale's payment rows are `CREDIT`, so AR payments (`ArPayment`) are ignored. For a `PAID` invoice nothing is reversed. For `PARTIAL`, only the outstanding part is reversed, so the paid part vanishes.

**Required change.**

1. In `voidSale`, for `CREDIT` sales load `ArPayment` rows for the invoice and compute `paidViaAr = sum(amount)` minus any refund already made through returns (`SaleReturn` rows with a real tender, see F1/F2).
2. Cash portion (`ArPayment.paymentMethod == CASH`) is reversed through the existing drawer logic (`CASH_REFUND`, or the late-void `PAY_OUT` path). Non-cash AR payments become `creditBalance` or an external refund note. **\[Decision\]** default: cash AR payments refunded from the open drawer, non-cash to `creditBalance`.
3. Customer balance: reduce `currentBalance` by the genuinely outstanding amount only (existing logic), never below zero; never turn paid money into debt relief.
4. Mark `ArPayment` rows reversed. Add a nullable `reversedAt` column through the idempotent startup migration runner (`AccountingSchemaMigration` style); do not enable Flyway.

**Acceptance tests.**

- Credit sale 100 paid in full by two cash AR payments, voided: 100 leaves the drawer (or late-void paid-out), stock restored, customer balance unchanged.
- Credit sale 100, paid 80 by card AR payment, voided: `creditBalance` +80, balance reduced by 20.
- Void of an unpaid credit sale behaves as today.
- Voiding twice is rejected (see F4).

---

## Phase 2: Concurrency and validation holes

### F4. Void can execute twice concurrently (High)

**Files:** `SaleService.voidSale`; `SaleReturnService.createSaleReturn`; `SaleRepository.findByIdForUpdate` (already exists).

**Problem.** `voidSale` loads with `findById`. Two simultaneous requests can both see `isVoided == false` and restore stock and ledger twice. Similarly `createSaleReturn` checks `isVoided` before locking item rows, so a return can slip in after a void commits.

**Required change.**

- `voidSale`: `saleRepository.findByIdForUpdate(saleId)` as the first read, then check `isVoided`.
- `createSaleReturn`: `loadActiveSale` must use `findByIdForUpdate`; keep the item row locks. Lock order everywhere: sale, then sale items (ascending id), then products (ascending id), then customer. Document it in a class comment.

**Acceptance tests.** Two threads voiding the same sale: exactly one succeeds, stock restored once, one `SALE_VOID` ledger set. A void racing a return: the second operation fails with a clear message and no double restock.

### F6. Over-shipment approval can be bypassed (High)

**Files:** `PurchaseService.receivePurchase`.

**Problem.** The validation loop tests `item.getReceivedQuantity() + received` per request entry, without summing entries for the same item. Two entries of 10 on an order of 10 each pass, then `receiveUnits` runs twice with no cap.

**Required change.**

1. Before any validation, merge request entries by `purchaseItemId`: sum `receivedQuantity` and `quarantineQuantity`, or reject duplicates with `BusinessException`. **\[Decision\]** default: reject duplicates (simplest, matches `SaleReturnService`'s duplicate-item rule).
2. Load the purchase with a pessimistic lock (add `findByIdForUpdate` to `PurchaseRepository`) so concurrent receives cannot read the same `receivedQuantity`.
3. Validate over-shipment against the merged totals.

**Acceptance tests.** Order 10, request two entries of 10 without manager credentials: rejected. Two concurrent receives of 10 on an order of 10: one succeeds, the other fails or requires override.

### F7. Sale stock check ignores repeated lines and reservations (High)

**Files:** `SaleService.createSale` (first pass), `processStockDeduction`.

**Problem.** Availability is checked per line with an unlocked `findById`, not summed per product (`createOrder` already aggregates, with a comment explaining why). After the lock, only `stock < 0` is checked, not `stock - reserved`. Lines for the same product can consume stock reserved for pending orders.

**Required change.**

1. Build `Map<Long,Integer> requestedByProduct` from the request (sum duplicates).
2. Lock products in ascending id order (`findByIdForUpdate`) once, then check `product.getAvailableQuantity() >= requestedTotal` under the lock. Reuse the locked entities in the second pass.
3. In `processStockDeduction`, keep the `< 0` guard, and additionally assert the post-deduction `stock >= reserved` for non-order-conversion sales.
4. `createSaleFromOrder` already adds back its own reservation; keep that.
5. Optional: also merge duplicate lines in `verifyCart` so the preview matches.

**Acceptance tests.** stock 10, reserved 8: cart with two lines of qty 2 each is rejected. Two concurrent sales of the last unit: one succeeds. Order conversion still works when the order holds the reservation.

---

## Phase 3: Purchasing correctness

### F5. Return to vendor takes stock from the wrong bucket (High)

**Files:** `PurchaseService.returnToVendor`, `quarantineAvailableForItem`; reference implementation `reversePurchaseItem`.

**Problem.** RTV takes from quarantine first, then straight from shop stock. If the receipt went to a warehouse (or another non-shop location), shop stock is deducted and the warehouse stays inflated.

**Required change.**

1. Extract the location-aware draw logic of `reversePurchaseItem` into one helper, for example `removeUnitsFromReceiptLocations(purchase, item, product, qty, reasonCode)`, used by both flows.
2. Scoping rule: compute, per location code, `receivedHere - alreadyRemovedHere` from this purchase item's own movements (`RECEIVING` IN, `RTV` OUT, `PURCHASE` OUT, `TRANSFER_OUT`). Draw only from that scoped amount per location, quarantine first (suspect goods), then non-shop locations, then shop.
3. If goods were transferred to the shop since receipt, the TRANSFER movements must move the attribution, so the shop becomes a valid source. Otherwise fail with the "short N unit(s)" message already used in `reversePurchaseItem`.
4. Keep the ledger posting before the removal, and keep RTV credit logic (`rtvCreditAmount`) unchanged.

**Acceptance tests.** PO received into warehouse W (10 units), shop holds 5 from another PO: RTV 4 reduces W by 4 and leaves shop at 5. Mixed receipt (6 shop, 4 quarantine) RTV 5: quarantine 4 then shop 1. PO-1 and PO-2 quarantine isolation test from the earlier spec still passes.

### F8. Editing a received purchase keeps a stale supplier credit (Medium-High)

**Files:** `PurchaseService.updatePurchase`, `applyTotals`.

**Problem.** Items are reversed, deleted and recreated, but `Purchase.returnedAmount` is never reset (the only setter is in `returnToVendor`). Payable stays reduced by credit for goods that no longer exist as returned on the new lines.

**Required change.**

- **\[Decision\]** Default: block `updatePurchase` when any item has `returnedQuantity > 0`, with message "Cannot edit a purchase that has returned goods. Create a new purchase or adjustment." This is the safe rule because credits, ledger rows and payments are tied to old item ids.
- Alternative (only if the owner insists on editing): reset `returnedAmount` to zero in the same transaction after reversing, and write a visible audit line stating the credit was voided.
- In both cases, also reverse the unabsorbed-landed-cost `PURCHASE_VARIANCE` expense created by `recomputeTotalsOnClose` for this purchase (post a reversing expense through `LedgerPostingService.reverseExpense`), because recreated items recompute it. Apply the same variance reversal in `deletePurchase`.

**Acceptance tests.** Purchase received, 3 units returned, then edit: rejected (default). Edit of a purchase with variance expense: variance ledger nets to the new value. Delete: variance ledger nets to zero.

---

## Phase 4: Time, status, bulk delete, discount authority

### F9. Shop timezone is only partly applied (Medium)

**Files:** `SaleService` (14 raw `now()` calls), `CashShiftService` (4), `PurchaseService`, `LedgerPostingService.postInventory/postProfit` defaults, `SaleReturnService`, `ArService`.

**Problem.** `ShopTimeService` is documented as the only source of "now". `ReportService` and `PeriodGuard` use it, but write paths use the JVM default zone. If the JVM zone differs from `shop.timezone` (UTC by default), sales land on the wrong day or period.

**Required change.**

1. Inject `ShopTimeService` and replace every `LocalDateTime.now()` / `LocalDate.now()` in service write paths with `shopTimeService.now()` / `today()`. This includes `getFilteredSales` range anchors, `saleDate`, `voidedAt`, return and movement dates, and the purchase `PURCHASE_VARIANCE` expense date.
2. Add an ArchUnit or grep-based unit test that fails if `LocalDateTime.now()` or `LocalDate.now()` appears under `com.bms.service` (allow-list `ShopTimeService`).
3. Fix `getFilteredSales` "QUARTER" to the calendar quarter (start = first day of the quarter containing today).
4. Document in the changelog that existing rows are interpreted as shop-zone wall clock; no data rewrite.

**Acceptance tests.** With JVM zone UTC and shop zone `Asia/Yangon`, a sale created at 01:00 Yangon on the 1st is assigned to the new month's period and appears in that day's report.

### F10. Voided non-cash sales keep payments `COMPLETED` (Medium)

**Files:** `SaleService.voidSale`.

**Problem.** `payment.setPaymentStatus(REFUNDED)` runs only when a cash drawer reversal happens.

**Required change.** After the void, mark all non-`CREDIT` payment rows `REFUNDED` regardless of tender, and `CREDIT` rows `CANCELLED` (add the enum value if missing). Only create a drawer movement for the cash portion. Add a report check that payment-method totals exclude voided sales.

**Acceptance test.** A card-only sale voided: payment status `REFUNDED`, no drawer movement, payment-method report excludes it.

### F11. `deleteSalesOlderThanYears` corrupts or fails (Medium-High)

**Files:** `SaleService.deleteSalesOlderThanYears`; `SaleController` endpoint.

**Problems.**

- Does not delete `sale_payment`, which has a non-null FK to `sale`, so the delete fails on databases that enforce it.
- Leaves `inventory_ledger`, `profit_ledger`, drawer movements and closed period totals untouched, and ignores the period lock (violates I3).
- Deletes `StockMovement` by `referenceId IN saleIds` for types `SALE` and `RETURN`. `RETURN` movements reference sale ids or return ids depending on the writer, and `STOCK_ADJUSTMENT` write-offs use sale ids, so unrelated rows can be deleted.

**Required change.** **\[Decision\]** Default: replace hard delete with archival. Add `archivedAt` on `Sale` (migration runner), exclude archived rows from default queries, and keep all ledger rows. If hard delete must stay:

1. Admin-only, requires backup confirmation (same as profit backfill endpoints).
2. Refuse if any affected sale falls in a closed accounting period or is referenced by an open AR balance.
3. Delete in FK order: `SaleReturnItem`, `SaleReturn`, `SalePayment`, `ArPayment`, `SaleItem`, `Sale`. Scope `StockMovement` deletion with an explicit `referenceType` plus a new `sourceSaleId` column rather than overloaded `referenceId`.
4. Never delete ledger rows (append-only); the result is documented as "detail removed, totals preserved in period close".

**Acceptance tests.** Deleting with payments present succeeds in H2 with FK checks on. Sales in a closed period are rejected. Unrelated stock movements with equal ids survive.

### F12. Cashier-entered discount has no authority limit (Medium)

**Files:** `SaleService.computeDiscount`, `createSale` below-cost guard.

**Problem.** In `AMOUNT` mode any amount up to the subtotal is accepted from any cashier, and the below-cost check runs on line prices before the header discount.

**Required change.**

1. New settings `shop.discount.maxPercentCashier` (default 10) and `shop.discount.maxPercentManager` (default 100). If `discount / subtotal * 100` exceeds the cashier limit and the user is not manager/admin, reject (or accept manager credentials as `receivePurchase` does for over-shipment).
2. Run the below-cost policy after discount allocation using each line's net price (`lineNet / qty`) against `costPriceAtSale`.
3. Audit-log every discount above 0 with user and amount.

**Acceptance tests.** Cashier with 50% discount rejected. Manager override accepted. Net-price below cost follows the configured `BelowCostPolicy`.

---

## Phase 5: Hardening

### F13. Purchase discount larger than the subtotal (Medium)

**Files:** `PurchaseService.applyTotals`.

Reject `discountAmount > subtotal` up front (before adding tax/expenses). Add a validation test with discount 150, subtotal 100, tax 60: must fail instead of producing negative landed unit costs.

### F14. Order conversion drops promotion fields (Medium)

**Files:** `OrderItem`, `OrderService.createOrder`, `SaleService.createSaleFromOrder`.

Add `originalUnitPrice`, `lineDiscount`, `promotionId`, `promotionName` and the sale-level `promotionDiscount` / `priceListDiscount` to the order (migration runner), populate from the `PricingQuote` at order creation, and copy them into the sale on conversion. Backfill is not required; null means "no promotion".

**Acceptance test.** An order with a PERCENT promotion converted to a sale shows the same `promotionDiscount` as a direct sale of the same cart in the promotion report.

### F15. Legacy shift close sums refunds for all shifts (Low-Medium)

**Files:** `CashShiftService.closeShift` (legacy no-session branch), `SaleRepository.sumRefundsBetween`.

Filter refunds by `employeeShift.id` (the `SaleReturn.employeeShift` link exists) instead of the time window. Add a test with two simultaneous shifts.

### F16. Pending-order reservations never expire (Low-Medium)

**\[Decision\]** Add `shop.order.reservationHours` (default 0 = never). A scheduled job cancels `PENDING` orders older than the limit through `OrderService.cancelOrder` (system user, reason "Reservation expired"). Default 0 keeps current behavior.

### Low-priority items

- `createPurchase` / `updatePurchase`: validate `purchaseDate` is present (`@NotNull` exists on the DTO, so confirm `@Valid` is on the controller parameters) and return a 400 for unparsable dates.
- `SaleDiscountUtils.allocateDiscounts`: the remainder `.max(ZERO)` can leave allocations summing above the discount by a cent. Clamp earlier shares so the last line receives the exact remainder, or assert the sum in a unit test.
- Overloaded `StockMovement.referenceId`: add `sourceSaleId`/`sourceReturnId` (nullable) before relying on `referenceId` in queries (also required by F11).
- Add `@Valid` checks on every request body that sets quantities, and a global test that all `@PostMapping` bodies are validated.

---

## 2. Schema and migration notes

All changes go through the existing idempotent startup runner (H2, SQLite and MySQL), never Flyway. Columns introduced: `ar_payment.reversed_at`, `sale.archived_at` (if archival is chosen), order promotion fields (F14), `stock_movement.source_sale_id` and `source_return_id`, plus the new system settings above (defaults seeded if absent). Every new column is nullable or has a default so older backups restore cleanly.

## 3. Test plan summary

| Area | Tests to add (name after the item) |
| --- | --- |
| Returns | `f1_returnOnPaidCreditInvoiceRefundsCustomer`, `f2_partialCreditReturnCapsDebtReduction` |
| Void | `f3_voidCreditSaleRefundsArPayments`, `f4_concurrentVoidRestoresOnce`, `f10_voidMarksNonCashPaymentsRefunded` |
| Sales | `f7_repeatedLinesRespectReservation`, `f12_discountAuthorityLimit` |
| Purchasing | `f5_rtvDrawsFromReceiptLocation`, `f6_duplicateReceiveEntriesRejected`, `f8_editBlockedAfterRtv`, `f13_discountExceedsSubtotalRejected` |
| Time | `f9_noRawNowInServices`, `f9_periodUsesShopZone` |
| Data | `f11_bulkDeleteRespectsFkAndPeriodLock` |

Each test must fail on the current code first, then pass after the fix. Re-run the existing suites (`SaleServiceVoidCashTrailTest`, `PurchaseServiceReceivingTest`, `GlJournalIntegrationTest`, `ReportProfitAggregationIntegrationTest`) after every phase to confirm the invariants in section 0 still hold.

## 4. Report template per item

What changed, files touched, tests added, any deviation from this spec, and the owner decisions applied (with the config key that flips each one).
