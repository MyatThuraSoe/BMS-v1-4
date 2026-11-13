# BMS-v1-4 Business Logic Fix Specification

For the implementing agent. Based on BMS-business-logic-audit.md (audit dated 2026-11-13). Scope: bms-backend (Spring Boot / JPA) and frontend (React). Android is excluded.

## 0. How to use this document

- The audit is the source of truth for what is wrong. This document turns each finding into an ordered, testable work item. IDs match the audit (C1 to C11, P1 to P10, O1 to O8).
- Line numbers come from a shallow-clone audit and may have drifted. Before editing anything, open the code and confirm the defect still reproduces. If a finding does not reproduce, stop and report it instead of inventing a change.
- One pull request per work item (or per tightly related pair). Order inside each PR: write the failing test, make the fix, show the test green.
- Do not change behavior outside an item's scope. Do not break anything listed in section 1.
- Schema changes go through the project's migration mechanism. Check whether Flyway or Liquibase is in use and follow it. Do not rely on ddl-auto in production. Migrations must be backward compatible.
- Money is BigDecimal only. Round only at display time or at final posting, with an explicit RoundingMode (HALF\_UP unless the code already standardizes on something else).
- Every stock or cost mutation runs in one transaction that holds the pessimistic lock on the product row (and the location rows it touches). Keep the existing lock order to avoid deadlocks.
- After each item, report: what changed, files touched, tests added, and any deviation from this spec.

## 1. Invariants

### 1.1 Already correct, must not regress

- `SaleItem.costPriceAtSale` is captured at sale time and never mutated afterwards.
- `SaleDiscountUtils` proportional header-discount allocation, including the rounding remainder on a real line.
- Server-authoritative pricing via `PromotionService.calculateBestPrice`, and `verify-cart`.
- `SequenceService` per-day row lock.
- `SaleReturnService` locking of the `sale_item` row before reading `quantityRefunded`.
- Credit-sale customer row lock and limit check before persisting.

### 1.2 New invariants the finished system must satisfy

- **I1 Inventory value:** for every product, `costPrice x total owned units` (shop plus every location bucket, including quarantine) equals the sum of valuation movements, within a documented rounding tolerance.
- **I2 Purchase capitalisation:** for a fully received purchase, total capitalised inventory value equals amount payable minus recoverable tax.
- **I3 Period immutability:** once a period is closed, its reported revenue, COGS and expense never change.
- **I4 Reports agree:** GL journal debits equal credits for every period; GL COGS equals report COGS; GL expense equals P&L expense.
- **I5 Cost traceability:** every change to a product's cost has a history row with source, reason and originating document.

## 2. Owner decisions (defaults to implement if unanswered)

These are business choices, not code choices. Implement the default, keep it in a named constant or config property, and list it in your report so the owner can flip it.

- **D1 Supplier credit on return-to-vendor:** add `returnedAmount` to Purchase. Payable equals `totalAmount - returnedAmount`.
- **D2 Purchase tax:** add `taxRecoverable` (boolean, default false) per purchase. Non-recoverable tax is capitalised into cost.
- **D3 Unabsorbed freight when a PO remainder is cancelled:** expense it to the P&L as purchase variance.
- **D4 Who may adjust cost:** ADMIN only.
- **D5 Void window:** MANAGER may void within 24 hours of the sale. ADMIN may void later, with a mandatory reason.
- **D6 Stock count shortages in the P&L:** include them as shrinkage, shown separately from spoilage and expiry.
- **D7 Per-product tax (`product.taxRate`):** remove it from the UI and mark the field deprecated. Do not implement per-product tax in this pass.

## 3. Phase 0: shared groundwork and test harness

### 3.1 Shared helper: total owned units

C1, C2 and C3 all need the product's quantity across every location. Create one place for it so the three fixes agree.

- Add `LocationService.totalOwnedUnits(Product)` (or a small `InventoryPositionService`) returning shop stock plus every non-shop bucket (warehouse, quarantine and any other location). Remember that `Product.stockQuantity` mirrors only the SHOP bucket.
- It must be called only while the product row lock is held, inside the same transaction as the cost calculation.
- Unit test: product with shop 5, warehouse 10, quarantine 4 returns 19.

### 3.2 Reproduction tests

- Port every scenario in `bms-cost-profit-repro.py` into JUnit under `bms-backend/src/test/java/com/bms/service/`. Extend the existing `CostCalculationUtilsTest` and `PurchaseServiceReceivingTest` where they fit. If the script is not in the repo, use the numbers given in each work item below as the expected values.
- Each scenario must fail on the current code first. That proves the bug is real and the test is meaningful.
- Name tests after the finding ID, for example `c1_weightedAverageUsesAllOwnedUnits`.

## 4. Phase 1: stop the bleeding

### 4.1 C3: deleting an unreceived PO must not touch stock (Critical)

**Files:** `PurchaseService.deletePurchase` (~~:642 to 655), `reversePurchaseItem` (~~:551), `updatePurchase` (\~:683).

**Problem:** `deletePurchase` reverses stock unconditionally, and the fallback `receivedQuantity > 0 ? receivedQuantity : quantity` treats a never-received item as fully received. Deleting an ORDERED PO removes real stock and drags WAC down.

**Required change:**

1. In `deletePurchase`, reverse stock only when the purchase has received quantity, using the same `wasReceived` logic as `updatePurchase`. An ORDERED purchase with nothing received just changes status and writes no stock or cost rows.
2. Remove the `receivedQuantity > 0 ? ... : quantity` fallback. Reverse exactly `item.getReceivedQuantity()`.
3. Legacy data: write a one-off migration that, for purchases with status RECEIVED and `receivedQuantity = 0` (pre-receiving-feature rows), sets `receivedQuantity = quantity`. After that the fallback is no longer needed.
4. Reversal must take stock back from the location where the receipt put it (use the original receipt movements for that item), not only from `product.stockQuantity`. Today goods sitting in a warehouse make the reversal throw 'already sold' when nothing was sold.
5. Reversal recomputes WAC using `calculateWeightedAverageCostAfterRemoval` with total owned units (3.1).

**Acceptance:**

- Before: shop=100, cost=5.00. Create PO 50 @ 9 with receiveNow=false. Delete it. After: shop=100, cost=5.00, no stock movement and no cost-history row written.
- Delete a RECEIVED PO whose goods went to the warehouse: warehouse stock decreases, shop stock is untouched, no spurious exception.
- Partially received PO (60 of 100): delete reverses exactly 60.

### 4.2 C2: return-to-vendor must be a complete transaction (Critical)

**Files:** `PurchaseService.returnToVendor` (~~:452 to 511), `availableIn(quarantine, product)` (~~:481), `GlService` (\~:182, :243).

**Problem:** RTV removes stock and writes a negative valuation movement but never revalues WAC, never reduces what is owed to the supplier, and can drain quarantine stock that belongs to a different PO.

**Required change:**

1. **Revalue.** Compute the new cost with `calculateWeightedAverageCostAfterRemoval(oldCost, ownedUnitsBefore, itemLandedUnitCost, qty)`. Use the purchase item's own landed unit cost as the removed value, since that is what the movement valuation uses. Update `product.costPrice` and write a COST history row with `purchaseQuantity = -qty`, source RTV and the purchase id.
2. **Credit the supplier (D1).** Add `returnedAmount` to Purchase (migration, default 0). Increase it by `qty x itemLandedUnitCost` (plus pro-rata tax if the original was taxed). Payable, AP reports and the GL purchase entry must use `totalAmount - returnedAmount`.
3. **Scope the stock source.** Determine how much of this item's receipt is still in quarantine from this purchase's own receipt movements (referenceType PURCHASE, this purchase id, this item) minus quantities already returned or released. Draw only from that. Never draw from product-level quarantine stock that arrived on another PO.
4. **Guard rails.** Reject if `qty > receivedQuantity - returnedQuantity`. Reject if the scoped stock source cannot cover `qty`.
5. Do not clamp the resulting cost silently. If the helper would produce a negative remainder, raise a reconciliation alert (see C8).

**Acceptance:**

- On hand 20 @ WAC 20.00 (value 400). RTV the 10 units that cost 30 each. Remaining 10 units value 100.00 and WAC 10.00. The old behavior left 200.00 booked.
- `returnedAmount` increases by 300.00 and payable falls by 300.00.
- PO-1 and PO-2 both have quarantine stock. Returning against PO-1 cannot reduce PO-2's quarantine quantity.
- RTV is not booked as an operating expense (coordinate with P4.4 in Phase 3).

### 4.3 C1: weighted average must use all owned units (Critical)

**Files:** `PurchaseService` (\~:368 to 394), `CostCalculationUtils.calculateWeightedAverageCost`.

**Problem:** WAC is averaged over SHOP stock only, but the incoming quantity added to the numerator includes units that went to the warehouse or quarantine.

**Required change:**

```
int ownedBefore = totalOwnedUnits(product);  // from 3.1, all buckets
newCost = calculateWeightedAverageCost(oldCost, ownedBefore, unitCost, receivedUnits);
```

- The base is every unit the business owns before the receipt. The added quantity is every unit received, including units that go to quarantine, because the business paid for them.
- Only sellable units are added to shop stock. Quarantined and warehouse units go to their buckets as today.
- Apply the same base in every other caller that recomputes WAC (reversal, RTV, update-purchase).

**Acceptance:**

- PO-1: 10 @ 100 to WAREHOUSE. PO-2: 10 @ 200 to SHOP. Stored cost is 150.00 (previously 200.00).
- 10 @ 100 with 4 quarantined, then 10 @ 200: stored cost 150.00 (previously 162.50).
- Single-location, no-quarantine receipts give identical results to before (no regression).

### 4.4 P5: tax must be computed after the discount (High)

**Files:** `SaleService` (\~:287 and \~:1017 to 1021), refund calculation in `SaleReturnService`.

**Problem:** tax is computed on the pre-discount line value, and the discount is subtracted afterwards, so customers are overcharged and tax is over-declared.

**Required change:**

1. Allocate the header discount to lines first (reuse `SaleDiscountUtils`), then compute tax on `lineNet = lineGross - allocatedDiscount`.
2. Store the line tax on the sale item as computed from the discounted base. Refund logic that adds back stored line tax then becomes correct automatically. Verify this with a test.
3. `product.taxRate` is dead (D7): remove it from the product form and mark it deprecated in the DTO. Do not touch pricing paths.

**Acceptance:**

- Line 100.00, discount 20.00, tax 10%: total 88.00, tax 8.00 (previously 90.00 and 10.00).
- Multi-line sale with a header discount and rounding remainder: sum of line taxes equals header tax, total equals sum of lines.
- Refund of a discounted taxed line returns exactly what was paid for those units.
- Historical sales are not recomputed. This change applies to new sales only.

### 4.5 P4.1: GL must not double-count promotion discounts (High)

**File:** `GlService` (\~:195).

**Problem:** the SALES\_DISCOUNT debit adds `promotionDiscount` and `priceListDiscount`, but lines are already stored at the promoted price, so subtotal is already net of them.

**Required change:** the discount used in the journal is `sale.getDiscountAmount()` (header discount) only.

**Acceptance:** list 100, promo price 80, cost 60, no tax. Debits equal credits (80 revenue side balanced). Add a test that asserts balance for sales with promotion, price list, header discount and tax in every combination.

### 4.6 C4: cost edits must be explicit, reasoned and safe (High)

**Files:** `ProductService.updateProduct` (~~:237 to 256), `ProductController` (~~:132), `ProductCreateRequest`, `ProductForm.jsx` (\~:74, :253, :256).

**Problem:** cost is an ordinary editable field. Edits revalue all stock with no trace, a blank field becomes 0.00, a stale form silently overwrites a receipt's cost, and the edit form shows a stock field that does nothing.

**Required change (backend):**

1. Remove `costPrice` from the product update path. Product update can no longer change cost.
2. Add `POST /api/products/{id}/cost-adjustment` with `{ newCost, reason, expectedVersion }`. Requires the D4 authority. `reason` is mandatory (minimum length, for example 10 characters). Reject if `expectedVersion` does not match the product's current version.
3. The endpoint, in one transaction under the product lock: computes `valueDelta = (newCost - oldCost) x totalOwnedUnits`; writes a REVALUATION stock movement carrying that `valuationAmount`; writes a COST history row with source MANUAL\_ADJUSTMENT, the reason and the user; and is picked up by the GL as an inventory revaluation (Phase 3).
4. Add `version` to the product update request and reject stale writes, so a form opened before a receipt cannot roll back the receipt's data.
5. Add `@NotNull` and a minimum of zero on cost for creation. `CostCalculationUtils.calculateProfit` must throw (or flag the line) on a null cost rather than returning ZERO.
6. `updateProduct` must not accept a stock quantity. Remove the dead field from the update DTO. Stock changes go through the existing `adjustStock` flow.

**Required change (frontend):**

- Edit mode: show cost as read-only with a 'Adjust cost' button that opens a dialog (new cost, reason). Remove the editable stock quantity from edit mode or wire it to the adjust-stock dialog.
- Create mode: cost is required. Replace `Number(data.cost) || 0` with validation that blocks submit on empty or invalid input.
- Round-trip `version` on save and show a clear 'this product changed, reload' message on a stale-write error.

**Acceptance:**

- Clearing the cost field and saving is impossible (client and server both reject).
- Cost 10 to 5 with 100 units on hand writes a REVALUATION of -500.00 with the reason recorded.
- Open the form, receive a purchase in another session, press Save: the save is rejected as stale and the receipt's cost is preserved.
- A MANAGER cannot call the cost-adjustment endpoint (per D4).

## 5. Phase 2: make cost auditable

### 5.1 C5 and C6: landed cost must follow what was actually paid and received (High)

**Files:** `PurchaseService.applyTotals` (~~:153), `applyLandedCosts` (~~:200), `receiveUnits` (~~:305 to 337), `LandedCostAllocator` (~~:112).

**Required change:**

1. **Discount.** Allocate the header `discountAmount` across lines in proportion to line value (reuse the proportional pattern with a remainder on a real line). Deduct it from each line's capitalised cost.
2. **Tax (D2).** Add `taxRecoverable`. When false, allocate `taxAmount` across lines and capitalise it. When true, do not capitalise it.
3. **Expenses on received quantity.** Stop using `expense / orderedQty`. At each receipt, capitalise `expense x receivedNow / expectedTotal` where capitalised-to-date for any expense must never exceed the expense amount. On over-receipt, the final receipt takes only the remainder, so freight is never over-capitalised.
4. **Unabsorbed remainder (D3).** When a PO is closed or its remainder cancelled with freight not fully capitalised, post the remainder to the P&L as purchase variance and record it on the purchase.
5. **Payable.** When a remainder is cancelled or an over-receipt is accepted, recompute `totalAmount` from received quantities so AP matches goods actually received.

**Acceptance:**

- 100 @ 10, discount 100, non-recoverable tax 50: capitalised 950.00 and payable 950.00 (previously 1000.00 capitalised).
- PO 100 @ 10 plus freight 50, received 120: freight capitalised is exactly 50.00 (previously 60.00).
- PO 100 @ 10 plus freight 50, received 60, remainder cancelled: capitalised 30.00 plus 20.00 purchase variance expense, total 50.00 (previously 20.00 vanished). Payable reflects 60 units.
- Invariant I2 holds in every case above.

### 5.2 P8: cost and price history must answer audit questions (Medium)

**Files:** `ProductPriceHistory`, `ProductService` (~~:228 to 249, 448 to 470, 636 to 664), `PurchaseItemRepository` (~~:17).

**Required change:**

1. Migration: add `reason` (varchar), `source` (enum: PURCHASE\_RECEIPT, PURCHASE\_REVERSAL, RTV, MANUAL\_ADJUSTMENT, REVALUATION, SELLING\_PRICE\_EDIT, OPENING\_BALANCE, LEGACY), `purchase_id` (nullable FK), `stock_qty_at_change`, `inventory_value_delta`. Backfill existing rows with source LEGACY where unknown.
2. Always set `priceType`. Backfill null rows as SELLING only where the row is demonstrably a selling-price change, otherwise leave as LEGACY.
3. Make selling-price history symmetric with cost history: record `null -> value` transitions, not only value-to-value.
4. `GET /products/{id}/price-history` must filter by `priceType` (or return the type in `PriceHistoryDto`).
5. Store a supplier id on history rows rather than relying on the denormalized supplier name string.
6. Fix `mostRecentUnitCost`: `PurchaseItemRepository` currently returns `MAX(unitCost)`. Return the unit cost of the latest received item by date, with id as tiebreaker.
7. `getCostHistory` must not include never-received POs. Label which cost it shows (purchase unit cost, landed cost or WAC) and show landed cost and resulting WAC alongside it.
8. Remove or relabel `impliedMarginPercent`. Comparing today's selling price to a historical cost is misleading.
9. Order by `(changedAt, id)` for a stable tiebreaker (also covers O7).

**Acceptance:** every cost change path in sections 4.1 to 4.6 produces a row with source, reason or document reference, and `inventory_value_delta`. A supplier with two POs (10 then 20) shows 20 as most recent, regardless of which was cheaper.

### 5.3 P2: order conversion must book COGS at fulfilment cost (High)

**Files:** `OrderService` (~~:123), `SaleService` (~~:517 to 520).

**Required change:** when an order is converted to a sale and stock is deducted, set `costPriceAtSale` to the product's cost at that moment (read under the product lock). Keep `costPriceAtOrder` for margin quoting only and never copy it into COGS.

**Acceptance:** order Monday at cost 60, WAC rises to 90, fulfil Friday 10 @ 100: COGS 900.00 and profit 100.00 (previously 600.00 and 400.00).

### 5.4 P3: write-offs and shrinkage must reach the P&L (High)

**Files:** `ReportService` (~~:610, `getAccountingSummary`), `GlService` (~~:243), `ProductService` (\~:395 to 408).

**Required change:**

1. Build one shared query or service that returns inventory-loss valuations by reason code (spoilage, expiry, damage, theft, count variance per D6). Exclude RTV and revaluations, which are not operating losses.
2. `getAccountingSummary` adds this to `totalExpenses` and returns a breakdown by reason.
3. The GL journal reads from the same shared source so that I4 holds.

**Acceptance:** write off 12 expired units @ 10: P&L expense includes 120.00 under spoilage or expiry, and GL expense equals P&L expense for the period.

### 5.5 C7, C8, C9, C10, C11: smaller cost-integrity fixes (Medium and Low)

- **C7 precision:** migrate `Product.costPrice` and every other stored cost column (cost-history old and new cost, `costPriceAtSale`, valuation amounts) to scale 6, or at minimum match `PurchaseItem.baseUnitCost` at 4. Remove the 2-dp rounding inside `CostCalculationUtils` (\~:24). Round only in DTOs and report output. Acceptance: 26 units for 8.7837 gives a stored average of 0.337835, and `qty x cost` reconciles with spend.
- **C8 reversal helper:** when quantity reaches zero return the last known cost, not 0. Do not clamp a negative remaining value to zero silently. Log a reconciliation alert (a warning log plus a row in a new `cost_reconciliation_alert` table, or whatever alerting the app has) and keep processing safely. Acceptance: removing all units leaves cost at the last known value, and the next receipt averages from it.
- **C9 opening stock:** `createProduct` (\~:179) must not set stock directly. Create an OPENING\_BALANCE stock movement with a valuation amount, and a history row with source OPENING\_BALANCE. Existing products are not backfilled here (see section 9).
- **C10 product deletion:** `deleteProduct` (\~:285 to 300) must reject deletion when total owned units is not 0 (tell the user to write off first), and when the product has sale or purchase history, offer deactivation instead.
- **C11 live-cost valuations:** snapshot the cost onto the stock movement at creation for transfers (`LocationService.moveStock` \~:445) and for stock-count variances (`StockCountService.applySheet` \~:365). Valuation must use the cost in force when the event was created or counted, as documented in the movement.

## 6. Phase 3: reports, GL, voids and returns

### 6.1 P4.2 to P4.5: GL journal must balance and agree with the P&L (High)

**File:** `GlService` (\~:164 to 255), `StockMovementRepository.findForJournal`. P4.1 is already done in 4.5.

- **COGS net of returns (P4.2).** `GlService` (~~:215) uses `item.getQuantity()`. Use the effective sold quantity (quantity minus refunded) exactly as `ReportService` does (~~:519). Once the Phase 4 ledger exists, both read the ledger instead.
- **Purchase reversals (P4.3).** `findForJournal` filters referenceType to STOCK\_ADJUSTMENT, STOCK\_COUNT and RTV, so reversals written with referenceType PURCHASE (\~:594) are dropped and the original inventory debit stays forever. Include the reversal movements, but only reversals. Do not double count the original receipts, which the purchase entry already covers.
- **RTV classification (P4.4).** Stop booking RTV as OPERATING\_EXPENSE (\~:243). RTV debits ACCOUNTS\_PAYABLE and credits inventory, using the amount credited by the supplier (`returnedAmount` from 4.2).
- **Missing event classes (P4.5).** Add journal entries for: Expense records, AR payments, sale returns, cash drawer movements, inventory revaluations (from 4.6) and purchase variance (from 5.1). Date each entry by the event date. A purchase that is received later than its `purchaseDate` must post its inventory at receipt time. Keep `purchaseDate` as the supplier invoice date only.

**Acceptance:** an integration test builds one month containing a sale with promotion and tax, a partial return, a void, a purchase with freight, a partial receipt, an RTV, a reversal, a write-off, an expense and an AR payment. It then asserts I4: debits equal credits, GL COGS equals report COGS, GL expense equals P&L expense.

### 6.2 P6: voids must always leave a cash trail (Medium)

**File:** `SaleService` (\~:620 to 651).

**Required change:**

1. Always write the cash reversal. If the original drawer session is OPEN, reverse inside it as today. If it is closed, post a paid-out movement on the current open drawer, linked to the original sale id and marked as a late void. If no drawer is open, reject with a clear message.
2. Require a reason on every void and store it with the user and timestamp.
3. Enforce the void window (D5). Manager voids older than the window are rejected. Admin voids older than the window require the reason.
4. A void must be a reversing event dated on the void date, not a silent flip of `isVoided` that rewrites history. Until Phase 4 exists, record `voidedAt` and `voidedBy` so the ledger backfill can date it.

**Acceptance:** voiding yesterday's cash sale produces a paid-out on today's drawer referencing the original sale, and the closed drawer's totals are unchanged.

### 6.3 P7: reports must not truncate silently (Medium)

**File:** `ReportService` (`REPORT_MAX_ROWS` at \~:58, used in 14 places including `getProfitSummary`, `getProfitTrend`, `getAccountingSummary`).

**Required change:** replace the in-memory graph loading with SQL aggregation (`SUM` and `GROUP BY`) for profit summary, trend (one grouped query, not one query per bucket) and accounting summary. If any capped path must remain, return `truncated: true` and the row count in the response, and show a warning in the UI. Note that Phase 4 makes this simpler because reports then sum ledger rows.

**Acceptance:** with 60,000 sales in the range the reported revenue, COGS and profit equal the exact totals. A year of data does not exhaust memory.

### 6.4 P9: returns must value stock correctly and allow a condition (Medium)

**File:** `SaleReturnService` (\~:203 to 226).

**Required change:**

1. Value the returned units at `costPriceAtSale` and write that as `valuationAmount` on the stock movement (use a dedicated SALE\_RETURN type or fill the amount on `ADJUSTMENT_IN`).
2. When units re-enter sellable stock, treat them as a receipt at `costPriceAtSale` for the WAC calculation over all owned units, so inventory value stays consistent if cost moved since the sale.
3. Add a per-line disposition: RESTOCK (sellable shop stock), QUARANTINE, or WRITE\_OFF. WRITE\_OFF expenses the value at `costPriceAtSale` through the same loss path as 5.4. Default stays RESTOCK so the cashier flow is not slowed down.
4. Frontend: add a disposition selector to the return dialog.

**Acceptance:** sell at cost 60, WAC moves to 90, return 1 unit as RESTOCK: inventory value rises by 60.00 and WAC is recomputed accordingly. Return as WRITE\_OFF: no stock added and a 60.00 loss recorded.

### 6.5 P10: below-cost guard and cost visibility (Low)

- Add a config `shop.belowCostPolicy` with values ALLOW\_WARN (default), REQUIRE\_MANAGER and BLOCK. Evaluate it in cart pricing after `calculateBestPrice`. Return the below-cost flag per line. Also warn when a promotion or price list is saved that sets a price below cost.
- `ProductService.convertToResponse` (\~:702) must not include `costPrice` for `ROLE_CASHIER` on `GET /api/products`. Prefer separate response DTOs for cashier and privileged users over nulling fields ad hoc. Check the POS screens for any dependency on cost before removing it.

**Acceptance:** a cashier token never receives `costPrice` from any product endpoint. A sale line priced below cost follows the configured policy.

## 7. Phase 4: append-only ledgers and period close (P1, O8)

This is the architectural fix. Do it after Phases 1 to 3 are merged and stable, in the stages below. Each stage is its own pull request and must leave the app working.

**Root cause:** `Product.costPrice` is a mutable scalar and profit is a query over mutable rows. `quantityRefunded` and `isVoided` are changed in place, so last month's profit changes when a return is processed today.

### 7.1 Stage 1: schema

Create three tables with the project's migration tool. Confirm the database engine first and adapt types (the definitions below assume PostgreSQL).

- `inventory_ledger`: id, product\_id, location\_id (nullable), event\_type (RECEIPT, RTV, SALE, RETURN, VOID, ADJUSTMENT, COUNT, TRANSFER, REVALUATION, OPENING), source\_type, source\_id, qty\_delta (signed), unit\_cost numeric(19,6), value\_delta numeric(19,6), qty\_after, value\_after, unit\_cost\_after numeric(19,6), reason, created\_by, occurred\_at, posted\_at.
- `profit_ledger`: id, period (char 7, for example 2026-11), event\_type (SALE, RETURN, VOID, WRITE\_OFF, EXPENSE, REVALUATION, PURCHASE\_VARIANCE), source\_type, source\_id, product\_id, quantity, net\_revenue, cogs, expense (numeric 19,4, default 0), occurred\_at, posted\_at.
- `period_close`: period (primary key), closed\_at, closed\_by, frozen revenue, cogs and expense.

Add a uniqueness guard on (source\_type, source\_id, event\_type, product\_id, line key) so a posting cannot be written twice.

### 7.2 Stage 2: one posting service, dual write

- Create a single `LedgerPostingService`. It is the only code allowed to change `Product.costPrice`, and it derives it as `value_after / qty_after` over all locations. `Product.costPrice` becomes a read-only cache for the POS screen.
- Post inside the same transaction as the originating operation, under the product lock.
- Events to post: sale (revenue, COGS, inventory relief at `costPriceAtSale`), return (its own negative revenue and negative COGS dated on the return, at `costPriceAtSale`), void (a reversing entry dated on the void), receipt, reversal, RTV, adjustment, write-off, count variance, transfer, revaluation, opening balance, expense, purchase variance.
- Keep the existing tables and logic working during this stage. Ledger writing is additive.

### 7.3 Stage 3: backfill and reconcile

- Backfill history: sales at the sale date, returns at the return timestamp, voids at `voidedAt` (flag voids without a date), purchases at receipt date.
- Write a reconciliation job that compares ledger totals against the old report logic per month. Differences must be explainable (for example a January sale returned in February moves from January to February). List unexplained differences and do not proceed to stage 4 until there are none.

### 7.4 Stage 4: switch reads

- `getProfitSummary`, `getProfitTrend`, `getAccountingSummary` and the GL journal read the ledger. They never re-derive from `SaleItem.quantityRefunded` or `isVoided`.
- The operational fields stay for UI and refund limits only.

### 7.5 Stage 5: period close (O8)

- ADMIN endpoint to close a period. It stores the frozen totals in `period_close`.
- Add a `PeriodGuard.assertOpen(date)` and call it from every back-datable write: `purchaseDate`, expense date, stock count date, offline-synced sales, and any manual adjustment date. A closed period rejects the write. The correction is made as an adjusting entry in the current open period instead.
- Reopening a period is ADMIN only, needs a reason, and is itself logged.

**Acceptance (the January scenario):** January is reported on Jan 31 as revenue 10000.00, COGS 6000.00, gross profit 4000.00. Close January. Process a full return in February. January re-run still shows 10000.00 / 6000.00 / 4000.00 and equals the frozen snapshot. February shows revenue -10000.00, COGS -6000.00. Posting a purchase dated in January is rejected.

## 8. Phase 5: other improvements

- **O1 AR balance drift.** `Customer.currentBalance` is clamped to 0 in `ArService`, `SaleService` (~~:729) and `SaleReturnService` (~~:352) while `sumOutstandingAr()` is an independent source. Stop clamping silently: record an overpayment as customer credit. Add a nightly reconciliation that compares `currentBalance` with the sum of open invoices and reports mismatches.
- **O2 Order pricing.** `OrderService` (~~:122 to 138) uses `product.getUnitPrice()`, bypassing promotions, price lists and shop discount. Route order pricing through `calculateBestPrice`. Decide whether an order locks its price at creation. Keep that price at conversion (~~:517) if so, and state it in the UI.
- **O3 Accounts payable.** `Purchase.paymentStatus` is a hand-set enum. Add a `PurchasePayment` entity mirroring `ArPayment`, derive status and outstanding amount from payments and `returnedAmount`, and add a payables aging report.
- **O4 Double-counted freight.** If a freight bill is capitalised through `PurchaseExpense` and also entered in the Expenses module, it is counted twice. Add a link or flag on Expense for purchase-capitalised costs, and warn on likely duplicates (same supplier or amount within a few days).
- **O5 Performance.** `calculateBestPrice` calls `promotionRepository.findActiveAt()` per cart line (`SaleService` \~:253). Load active promotions once per request and pass them in. Report aggregation is covered in 6.3.
- **O6 Invoice numbering.** `String.format("%03d", next)` in `SequenceService` (\~:44) overflows after 999 per day. Widen to 5 digits and make `findLastInvoicesByPrefix` order by the numeric sequence (parse or store it as a number), so mixed widths on the deployment day sort correctly.
- **O7 Time handling.** Store instants in UTC and add a `shop.timezone` setting used for day and period boundaries. For offline sync, store both the client timestamp and the server-received timestamp, and decide period assignment with the period guard. Add `id` as the tiebreaker wherever history is ordered by time.
- **O8 Period control.** Implemented in 7.5.

## 9. Data remediation for records already affected

C1, C2 and C3 have already written wrong costs into live data. The code fixes do not repair history. Handle it as follows.

1. Take a database backup before running any migration. Test every migration on a copy of production data first.
2. Build a **read-only** cost reconciliation report (admin endpoint or CLI). For each product it replays purchase receipts, reversals, RTVs and adjustments in order with the corrected formulas (all owned units as the base), then compares expected WAC and inventory value with the stored values.
3. Flag specifically: products with receipts into non-shop locations (C1), purchases deleted or edited while ORDERED or with `receivedQuantity = 0` (C3), and any RTV records (C2).
4. Do **not** auto-correct. The owner reviews the report and applies fixes through the new cost-adjustment endpoint from 4.6, with the reason `RECONCILIATION-<date>`. This leaves a clean audit trail.
5. Historical `costPriceAtSale` values are not rewritten. Past gross profit stays as recorded. Document the cut-over date in the changelog so reports before and after it are understood.

## 10. Order of work

1. Phase 0: shared helper and failing reproduction tests.
2. Phase 1 in this order: C3, C2, C1, P5, P4.1, C4 (backend then frontend).
3. Phase 2: C5 and C6, P8, P2, P3, then C7 to C11. Do C7 (precision) before building on the ledger.
4. Phase 3: GL fixes, P6, P7, P9, P10.
5. Section 9 reconciliation report, so the owner can start correcting data after Phase 2.
6. Phase 4 ledger stages, then Phase 5 items in any order.

## 11. Definition of done

- Every item's acceptance numbers are covered by an automated test that failed before the fix and passes after.
- Invariants I1 to I5 are asserted by at least one integration test each.
- All migrations run on a copy of production data, and are idempotent on re-run where the tool allows it.
- No `double` or `float` appears in any money path. No new silent clamping or `|| 0` fallbacks on cost.
- Frontend builds and the product, purchase, return and report screens work against the changed API. Cashier screens do not receive cost data.
- Section 1.1 behaviors are covered by regression tests that still pass.
- The final report lists each item as fixed, deferred or not reproducible, with files changed and the owner decisions (D1 to D7) that were applied as defaults.
