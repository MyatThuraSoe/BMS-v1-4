# BMS-v1-4 Technical Fix Specification: Second-Pass Findings (F17–F25)

Basis: fresh clone, commit `5001de0` (merge of PR #16), static review. Companion to the first specification (F1–F16). Numbering continues from F17.

## 0. Scope and rules

- **In scope:** `bms-backend` (Spring Boot / JPA) and the web `frontend` (React). **Out of scope: the Android app. It is not touched.** Every change below must therefore keep the existing REST contracts backward compatible: new request fields are optional, existing fields keep their meaning or are accepted and ignored, and new response fields are additive only. Where an endpoint is likely used by a mobile client (offline count sync, `POST /api/stock-counts/sync`), the change is server-side only.
- One pull request per item. Order inside each PR: failing test, fix, green test. Name tests after the finding ID, for example `f17_countIgnoresSalesSinceSnapshot`.
- Money is `BigDecimal`, rounded `HALF_UP` at posting. Every stock or cost mutation runs in one transaction holding the product row lock (`findByIdForUpdate`).
- Schema changes go through the existing idempotent startup runner (`AccountingSchemaMigration` style, H2 / SQLite / MySQL). Flyway stays disabled. All new columns are nullable or have a default so old backups restore.
- **\[Decision\]** marks an owner choice. Implement the stated default, hold it in a system setting or named constant, and list it in the PR.
- Lock order for the whole system (extends the first spec): sale or document row (transfer, count sheet), then sale items ascending id, then products ascending id, then location stock, then customer.
- After each item report: what changed, files touched, tests added, deviations.

## 1. Summary and order of work

| ID | Severity | Theme | Phase |
| --- | --- | --- | --- |
| F17 | High | Count variance uses a stale snapshot | 1 |
| F18 | Medium-High | Negative adjustment hides loss from P&L | 1 |
| F21 | Medium | Missing locks on transfer and count transitions | 2 |
| F22 | Medium | Stock reduced below reserved quantity | 2 |
| F19 | Medium | Count auto-approval ignores value | 3 |
| F20 | Medium | Offline sync trusts client data and time | 3 |
| F23 | Medium | GL books non-cash sales to cash | 4 |
| F24 | Low-Medium | Hard-coded drawer tolerance | 5 |
| F25 | Low | Integrity and traceability items | 5 |

F17 must land before F19 and F20 because both reuse its recomputation helper. F21 should land before F17 is released, so the corrected apply logic is not run twice concurrently.

---

## Phase 1

### F17. Stock-count variance uses a stale snapshot (High)

**Files:** `StockCountService.buildSheet`, `applyCountEntry`, `submit`, `applySheet`, `sync`; entity `CountSheetItem`; frontend `StockCountDetail.jsx`.

**Current behavior.**

```java
// buildSheet: snapshot at creation (scheduled sheets are created at 03:00)
item.setSystemQuantity(product.getStockQuantity());
// applyCountEntry (at submit)
int variance = countedQuantity - item.getSystemQuantity();
// applySheet (at approval)
int newQuantity = product.getStockQuantity() + variance;   // current stock + stale delta
```

Sales and returns between the snapshot and the physical count are counted twice: once by the physical count itself and again through the stale delta. Example: snapshot 10, sold 3, counted 7 (correct). Variance -3, new stock 4, three units booked as shrinkage at cost.

**Required change.**

1. Add to `CountSheetItem`: `liveQuantityAtApply` (Integer, nullable) and `varianceAtApply` (Integer, nullable). Keep `systemQuantity` and `variance` for history and for the blind-count screen.
2. In `applySheet`, after `productRepository.findByIdForUpdate(...)`, compute under the lock:

```java
int live = product.getStockQuantity() != null ? product.getStockQuantity() : 0;
int variance = item.getCountedQuantity() - live;       // authoritative
item.setLiveQuantityAtApply(live);
item.setVarianceAtApply(variance);
BigDecimal valuation = unitCost.multiply(BigDecimal.valueOf(variance)).setScale(COST_SCALE, HALF_UP);
item.setValuationAmount(valuation);
```

Then set `product.setStockQuantity(item.getCountedQuantity())` (equivalent to `live + variance`). The stock movement, ledger posting and loss posting use this recomputed variance and valuation. 3. **\[Decision\]** The physical count is a point-in-time fact taken at `countedAt`. Default: compare against live stock at apply time and show both numbers (snapshot and live) on the approval screen. Optional stricter mode (setting `stockCount.adjustForActivity`, default `false`): `expected = snapshot - soldSince + returnedSince` where the movements are read from `stock_movement` for the product between `startedAt` and `countedAt` (types SALE out, RETURN in, ADJUSTMENT, TRANSFER shop legs). Implement as one method `expectedQuantityAt(product, from, to)` so F19 and F20 reuse it. 4. Re-run the reason-code rule after recomputation. If a line's variance was 0 at submit but is non-zero at apply, or the reverse, the approver must supply a reason for new variances (reject approval with a `422` listing the items), and a line that is now zero posts nothing. 5. `submit` still stores the submit-time variance for the review screen, labelled "at submit". The approval response adds `varianceAtApply`. 6. In the auto-approve path inside `submit`, the same recomputation runs because it calls `applySheet`. 7. Frontend: on `StockCountDetail.jsx` show "System at start", "Live now" and "Variance now" columns for reviewers. Blind counting is unchanged. 8. Backfill: none. Existing approved sheets keep their stored values.

**Acceptance tests.**

- Snapshot 10, sell 3, count 7: variance 0, final stock 7, no movement, no loss posted.
- Snapshot 10, sell 3, count 6: variance -1, one `ADJUSTMENT_OUT` of 1, one shrinkage loss of 1 x cost.
- Snapshot 10, count 12 with no sales: variance +2, `ADJUSTMENT_IN` 2.
- Count with a sale landing between submit and approval: apply uses stock at approval.
- Approval of a line whose reason code is missing but whose recomputed variance is non-zero is rejected with the item names.
- Stock never goes below zero; `newQuantity = counted` is non-negative by validation.

### F18. Negative manual adjustment hides loss from the P&L (Medium-High)

**Files:** `ProductService.adjustStock` (both overloads), `InventoryLossService.LOSS_REASONS`, `isOperatingLoss`, `lossCategory`, `StockAdjustment.jsx`.

**Current behavior.** The four-argument-less overload calls `adjustStock(..., "COUNT_CORRECTION")`. `COUNT_CORRECTION` is allowed by `adjustStock` but is not in `LOSS_REASONS`, so `isOperatingLoss` returns false. The inventory ledger records a negative value (inventory credit) but no profit-ledger expense is posted. Net effect: stock leaves at cost with no expense.

**Required change.**

1. Rename semantics: any `ADJUSTMENT_OUT` through `STOCK_ADJUSTMENT` is an operating loss. Replace the allow-list check in `InventoryLossService.isOperatingLoss` for `STOCK_ADJUSTMENT` with: valuation negative and movement type `ADJUSTMENT_OUT`. Category from `lossCategory`: reason code upper-cased, `COUNT_CORRECTION` mapped to `SHRINKAGE`.
2. Remove the silent default. Delete the overload that omits the reason code, or make it call a new method that requires a reason code and throws `BusinessException("A reason code is required")` when null. Update every caller (search for `adjustStock(` in controllers and services). The REST body keeps `reasonCode` optional only for positive quantities.
3. Positive adjustments stay out of the loss path. Add them to the profit ledger as an inventory gain: new `ProfitLedger.EventType.INVENTORY_GAIN` row with `expense` negative (a credit) so profit and inventory reconcile. **\[Decision\]** Default: post gains only when the reason is `COUNT_CORRECTION` or `SUPPLIER_ERROR`; every other positive reason is rejected (positive adjustments for spoilage or theft make no sense).
4. The loss list in `StockCountService.REASON_CODES` and the adjustment reason list in `adjustStock` must come from one shared constant (`InventoryLossService.ADJUSTMENT_REASONS`) so they cannot drift.
5. GL: `postLedger` already routes `WRITE_OFF` rows to operating expense and skips the matching inventory event, so no GL change is needed beyond the new `INVENTORY_GAIN` event type (map to `INVENTORY_ADJUSTMENT` or an income account per the owner mapping).
6. Migration: none for data. Historical negative `COUNT_CORRECTION` movements remain unposted; provide an optional admin report `GET /api/accounting/unposted-adjustment-losses` listing them with valuation so the owner can decide on a manual journal.

**Acceptance tests.**

- Adjust -2 with `COUNT_CORRECTION`, cost 5.00: profit ledger gets a `WRITE_OFF` row with expense 10.00 and category `SHRINKAGE`; P&L loss total and GL operating expense both include it (I4).
- Adjust -2 with `THEFT_SHRINKAGE`: same, category `THEFT_SHRINKAGE`.
- Missing reason code on a negative adjustment: `BusinessException`.
- Positive adjustment with a loss reason: rejected.
- Positive `COUNT_CORRECTION` +2 posts a gain; inventory ledger and profit ledger reconcile.

---

## Phase 2

### F21. Missing row locks on transfer and count-sheet transitions (Medium)

**Files:** `LocationService` (`getTransfer`, `completeTransfer`, `startTransfer`, `receiveTransfer`, `cancelTransfer`), `StockCountService` (`getSheet`, `start`, `submit`, `approve`, `reject`, `applySheet`), `StockTransferRepository`, `CountSheetRepository`.

**Current behavior.** `getTransfer` and `getSheet` use `findById`. The status check happens on an unlocked read, so two simultaneous requests both pass it.

**Required change.**

1. Add repository methods:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT t FROM StockTransfer t WHERE t.id = :id")
Optional<StockTransfer> findByIdForUpdate(@Param("id") Long id);
// same for CountSheet
```

2. Add `getTransferForUpdate(id)` and `getSheetForUpdate(id)` and use them as the first read in every state-changing method. Read-only methods keep `findById`.
3. In `completeTransfer`, `startTransfer`, `receiveTransfer`: lock the transfer, check status, then lock products in ascending product id (sort `transfer.getItems()` by product id before the loop) to avoid deadlock with other flows.
4. In `StockCountService.submit`, `approve`, `reject`: lock the sheet first; in `applySheet`, lock products in ascending id order.
5. SQLite note: pessimistic locking is a no-op in a single-writer file database, so also add a status-guarded update as a second line of defence:

```java
int updated = repo.markStatus(id, DRAFT, COMPLETED);   // UPDATE ... WHERE id=? AND status=?
if (updated == 0) throw new BusinessException("Transfer is no longer in draft");
```

Do this before moving stock.

**Acceptance tests.** Two threads completing one transfer: exactly one succeeds, stock moves once, one set of movements. Two threads approving one sheet: one `STOCK_COUNT_APPROVE` audit row. A transfer completing while a sale runs on the same product does not deadlock (ordered locks).

### F22. Stock reduced below reserved quantity (Medium)

**Files:** `LocationService.moveStock`, `ProductService.adjustStock`, `StockCountService.applySheet`, `PurchaseService.returnToVendor` and `reversePurchaseItem`, `OrderService`.

**Current behavior.** `Product.availableQuantity = max(0, stock - reserved)`. Operations that lower `stockQuantity` only check against zero (or the moved quantity). Shop stock can fall below `reservedQuantity`, after which pending orders cannot be converted (`createSaleFromOrder` fails the availability check).

**Required change.**

1. Add one helper in `LocationService` (or a new `StockGuard`):

```java
void assertCanReduceShopStock(Product p, int newStock, boolean allowBelowReserved) {
    int reserved = p.getReservedQuantity() != null ? p.getReservedQuantity() : 0;
    if (newStock < reserved && !allowBelowReserved)
        throw new BusinessException("STOCK_RESERVED: " + p.getName() + " has " + reserved
            + " unit(s) reserved for pending orders; only " + Math.max(0, p.getStockQuantity() - reserved) + " can be moved");
}
```

2. Call it from `moveStock` (when `fromShop`), `adjustStock` (negative changes), RTV shop draw, and purchase reversal shop draw. Stock-count apply is the exception: a physical shortage is a fact. For counts, call it with `allowBelowReserved = true` and, when the result is below reserved, set a note on the affected pending orders (`order.notes` append "Stock shortage after count \<sheet>") and write an audit entry `ORDER_STOCK_SHORTAGE`.
3. **\[Decision\]** Managers may override with a reason through an optional request field `overrideReserved` (boolean) plus `reason`; default is no override. The override is logged to the audit table. Existing clients that do not send the field behave strictly.
4. Optional (recommended): the F16 expiry job from the first spec frees abandoned reservations; mention it in the error text.
5. Frontend: surface `STOCK_RESERVED` as a readable message on the transfer, adjustment and RTV screens, with the reserved quantity.

**Acceptance tests.** Stock 10, reserved 8: transfer of 5 from shop to backroom is rejected; transfer of 2 succeeds. Negative adjustment of 3 rejected, of 2 accepted. A manager override with reason allows 5 and writes an audit row. A count that finds 6 with 8 reserved applies the count and annotates the orders.

---

## Phase 3

### F19. Count auto-approval is unit-based and self-approving (Medium)

**Files:** `StockCountService.isWithinAutoApproveThreshold`, `submit`, `sync`, `approve`; `SystemSettingService`; `Settings.jsx`.

**Current behavior.** A sheet is applied automatically when every line has `abs(variance) <= stockCountAutoApproveThreshold` (units, default 2). There is no value limit, no sheet-level limit, and no separation between counter and approver.

**Required change.**

1. New settings (seeded with defaults if missing): `stockCountAutoApproveThreshold` (units per line, default 2, unchanged), `stockCountAutoApproveLineValue` (default 20.00 in shop currency), `stockCountAutoApproveSheetValue` (default 100.00), `stockCountAutoApproveMaxLines` (default 50 lines with a non-zero variance).
2. Replace the method with:

```java
boolean withinThresholds(List<CountSheetItem> items) {
    BigDecimal sheetAbs = ZERO; int nonZero = 0;
    for (var item : items) {
        int v = recomputedVariance(item);              // F17 helper, live stock
        if (v == 0) continue;
        nonZero++;
        BigDecimal value = unitCost(item).multiply(BigDecimal.valueOf(Math.abs(v)));
        if (Math.abs(v) > unitLimit || value.compareTo(lineValueLimit) > 0) return false;
        sheetAbs = sheetAbs.add(value);
    }
    return nonZero <= maxLines && sheetAbs.compareTo(sheetValueLimit) <= 0;
}
```

3. Segregation of duties **\[Decision\]**: default requires that an auto-approved sheet contains no loss line above the line-value limit; anything above goes to `PENDING_REVIEW` and must be approved by a user different from `submittedBy` (reject with `"Approver must differ from the counter"`), unless the user is ADMIN. Controllers keep `hasAnyRole('ADMIN','MANAGER')` on approve.
4. Audit: when auto-approved, log `STOCK_COUNT_AUTO_APPROVE` with the thresholds used and the totals.
5. Settings page: add the new fields with validation (non-negative decimals).

**Acceptance tests.** Two units of an item costing 500: above the line-value limit, goes to review. 60 lines of -1 each: over `maxLines`, goes to review. Sheet total 110 of small lines: over the sheet limit. A manager who submitted a sheet cannot approve it unless ADMIN. A small sheet still auto-applies.

### F20. Offline count sync trusts client data and time (Medium)

**Files:** `StockCountService.sync`, `SyncCountSheetRequest`. **Android is not changed**, so the request shape and the idempotency key behavior are preserved exactly.

**Current behavior.** The client's `submittedAt` becomes the event time and the accounting-period anchor (`periodGuard.assertOpen(eventAt)`), `systemQuantity` is taken from the client, and an in-threshold sheet is applied immediately.

**Required change (server-side only).**

1. Time bounds: compute `receivedAt = shopTimeService.now()`. Reject `submittedAt` after `receivedAt + 5 minutes` (clock skew allowance) with a clear message. **\[Decision\]** default maximum backdating `stockCount.sync.maxBackdateDays = 7`. If `submittedAt` is older than that, accept the sheet but set it to `PENDING_REVIEW` (never auto-apply) and keep both timestamps; never use `submittedAt` for the period guard beyond the cap. For the period guard use `max(eventAt, receivedAt - maxBackdateDays)`.
2. Ignore the client's `systemQuantity` when deciding stock: store it in `clientSystemQuantity` (new nullable column on `CountSheetItem`) for traceability, set `systemQuantity` to the live stock at receive time, and let F17 recompute at apply. The response still returns the item shape the client expects.
3. Idempotency stays: `findBySyncKey` first, unchanged. Add a unique index on `count_sheet.sync_key` through the migration runner if absent, so a double POST cannot create two sheets.
4. Auto-apply only when F19 thresholds pass **and** the event is within the cap **and** all products exist and are active; otherwise `PENDING_REVIEW`.
5. Validate payload size: at most 1000 items; counted quantities between 0 and a sanity limit (`stockCount.maxCountedQuantity`, default 1,000,000).

**Acceptance tests.** Payload 30 days old: sheet created in `PENDING_REVIEW`, not applied. Timestamp one hour in the future: rejected. Client quantity 100 with live stock 10 and counted 10: variance 0 after recompute. The same sync key posted twice returns the same sheet and creates one set of movements.

---

## Phase 4

### F23. GL books non-cash sales to the cash account (Medium)

**Files:** `LedgerPostingService.saleTender`, `refundTender`, `snapshotSettlement`, `GlService.postSaleOriginal`, `postVoidReversal`, `postLedger`; entity `ProfitLedger` (`settlement_account`); `SalePayment`.

**Current behavior.** `saleTender(sale)` returns `ACCOUNTS_RECEIVABLE` for credit sales and `CASH` for everything else. `refundTender` maps non-cash methods to `TENDER_CLEARING`. A card sale debits cash; its refund credits clearing. The legacy journal path (`postSaleOriginal`, `postVoidReversal`) has the same assumption.

**Required change.**

1. Settlement per payment line. In `postSaleProfit`, instead of one snapshot account per ledger row, compute the settlement split from the sale's `SalePayment` rows: cash lines to `CASH`, credit to `ACCOUNTS_RECEIVABLE`, all other methods to `TENDER_CLEARING`. Store it as a small JSON or as extra columns on `profit_ledger`: `settlement_cash`, `settlement_clearing`, `settlement_ar` (BigDecimal, default 0, plus the existing `settlement_account` kept for old rows). Each ledger row's settlement amount (`revenue + tax`) is apportioned by line amount share of the sale total; the last line takes the rounding remainder.
2. `GlService.postLedger`: for `SALE`, `RETURN`, `VOID` rows read the three amounts and emit one debit or credit entry per non-zero account. Rows without the new columns (pre-change) use the old single `settlement_account` path, so history is unchanged.
3. Returns: apportion by the refund tender chosen (`SaleReturn.refundMethod`) as today, now consistent with the sale side. Voids: reverse in the same proportions as the original sale.
4. Legacy journal path (`postSaleOriginal`, `postVoidReversal`): use the sale's payment rows the same way for sales not yet in the ledger.
5. Ledger cut-over and reconciliation: the profit reconciliation must not change (revenue, COGS and expense are untouched); add a settlement-account reconciliation to the accounting dashboard: `sum(settlement by account)` equals `sum(revenue + tax)` for the period.
6. Backfill is optional and requires the same backup confirmation as the profit backfill. Default: no rewrite of history; new column values are null for old rows and the old path applies.
7. **\[Decision\]** Gains from positive adjustments and counts post to account `INVENTORY_ADJUSTMENT` by default (F18). Owner can remap in the GL mapping screen.

**Acceptance tests.**

- Card sale 100 (revenue 95, tax 5): journal debits `TENDER_CLEARING` 100, credits revenue 95 and tax payable 5; cash account unchanged.
- Mixed sale 100 (cash 60, card 40): debit cash 60, debit clearing 40.
- Refund of that card sale (method card): credit clearing 100; clearing nets to zero.
- Void of a mixed sale reverses 60 cash and 40 clearing.
- A pre-change ledger row still renders through the old path and the journal still balances (debits equal credits) for the whole period.

---

## Phase 5

### F24. Drawer variance tolerance is a hard-coded currency amount (Low-Medium)

**Files:** `CashVarianceUtil.DEFAULT_TOLERANCE`, `CashDrawerSessionService.closeSession`, `CashShiftService.closeShift`, `CashShiftService.toResponse` (`setVarianceTolerance`), system settings, `Settings.jsx`, shift close screens.

**Required change.**

1. Setting `shop.cashVarianceTolerance` (BigDecimal, default `10.00`, validated `>= 0`). Add a service method `cashVarianceTolerance()` in `SystemSettingService` with a 1-minute cache.
2. Replace every use of `CashVarianceUtil.DEFAULT_TOLERANCE` in services with the setting; keep the constant in the util only as the fallback default.
3. `CashVarianceUtil` methods that take a tolerance already exist (`exceedsTolerance(variance, tolerance)`, `describeVariance(..., tolerance)`); pass the setting value. The audit text prints the tolerance used.
4. API: `CashShiftResponse.varianceTolerance` returns the setting (field already exists). No contract change.
5. Settings screen: one numeric field under cash drawer settings, with a note that it is in the shop currency.

**Acceptance tests.** With tolerance 5000, a variance of 4000 closes for a cashier and 6000 needs a manager; with the default 10.00 existing tests still pass.

### F25. Integrity and traceability items (Low)

1. **Expense creator overwrite.** `ExpenseService.updateExpense` sets `createdBy` to the editor. Remove that line, add nullable `updatedBy` and `updatedAt` (migration), set them on update, and include both in `ExpenseResponse`. Test: update by user B keeps `createdBy` = user A.
2. **Transfer traceability.** `LocationService.transferMovement` sets `referenceId = 0`. Add `source_transfer_id` (nullable) to `stock_movement`, set it from `moveStock` (pass the transfer id; rescue passes null and uses a `RESCUE` reference), and show the transfer number on the movement history. Keep `referenceId` for backward compatibility. This column is shared with the first spec (F11), add it once.
3. **Cancel an in-transit transfer.** Add `cancelInTransit(id, userId)`: lock the transfer (F21), require status `IN_TRANSIT`, move stock `TRANSIT -> source`, set status `CANCELLED`, audit `STOCK_TRANSFER_CANCEL_IN_TRANSIT`. Period guard applies. Test: stock returns to the source, transit bucket is zero afterwards.
4. **Transfer numbers.** `nextTransferNumber` counts rows and uses `LocalDate.now()`. Move it to `SequenceService` with a per-day row lock and the shop date, the same pattern as `nextInvoiceNumber`. Test: two concurrent creates produce distinct numbers.
5. **Encoding.** Several comments (for example in `ReportService`) contain mojibake. Re-save the affected files as UTF-8 and add `project.build.sourceEncoding=UTF-8` to `pom.xml` if missing. Add a CI check that fails on the byte sequences `\u00c3\u0082` or `\u00c2` in source comments. No behavior change.

---

## 2. Schema summary (all via the startup migration runner, all additive)

| Table | Column | Used by |
| --- | --- | --- |
| `count_sheet_item` | `live_quantity_at_apply`, `variance_at_apply`, `client_system_quantity` | F17, F20 |
| `count_sheet` | unique index on `sync_key` (if missing) | F20 |
| `profit_ledger` | `settlement_cash`, `settlement_clearing`, `settlement_ar`; new event type `INVENTORY_GAIN` | F18, F23 |
| `expense` | `updated_by`, `updated_at` | F25 |
| `stock_movement` | `source_transfer_id` | F25 (shared with F11) |

New system settings (seeded when missing): `stockCount.adjustForActivity`, `stockCountAutoApproveLineValue`, `stockCountAutoApproveSheetValue`, `stockCountAutoApproveMaxLines`, `stockCount.sync.maxBackdateDays`, `stockCount.maxCountedQuantity`, `shop.cashVarianceTolerance`.

## 3. API compatibility checklist (Android untouched)

- No endpoint is removed or renamed. No existing request field changes meaning.
- `POST /api/stock-counts/sync`: same body, same idempotency; behavior changes are server-side validation and review routing only. A rejected payload returns the existing error envelope.
- New request fields (`overrideReserved`, `refundMethod` from the first spec) are optional.
- New response fields (`varianceAtApply`, `liveQuantityAtApply`) are additive.
- Run the existing e2e specs in `frontend/e2e` after each phase.

## 4. Test plan

| Item | Tests (each must fail on current code first) |
| --- | --- |
| F17 | `f17_countIgnoresSalesSinceSnapshot`, `f17_countShortageAfterSales`, `f17_reasonRequiredAfterRecompute` |
| F18 | `f18_negativeCountCorrectionPostsLoss`, `f18_reasonRequiredForNegative`, `f18_positiveAdjustmentPostsGain` |
| F19 | `f19_autoApproveRespectsLineValue`, `f19_autoApproveRespectsMaxLines`, `f19_counterCannotApproveOwnSheet` |
| F20 | `f20_syncRejectsFutureTimestamp`, `f20_syncOldPayloadGoesToReview`, `f20_syncIgnoresClientSystemQuantity`, `f20_syncIdempotent` |
| F21 | `f21_concurrentTransferCompleteMovesOnce`, `f21_concurrentApproveAppliesOnce`, `f21_transferLockOrderNoDeadlock` |
| F22 | `f22_transferRespectsReservedStock`, `f22_managerOverrideLogged`, `f22_countShortageFlagsOrders` |
| F23 | `f23_cardSaleSettlesToClearing`, `f23_mixedSaleSplitsSettlement`, `f23_refundAndVoidNetClearing`, `f23_oldRowsStillBalance` |
| F24 | `f24_toleranceFromSetting` |
| F25 | `f25_expenseUpdateKeepsCreator`, `f25_cancelInTransitReturnsStock`, `f25_transferNumbersUnique` |

After every phase re-run `SaleServiceVoidCashTrailTest`, `StockCountServiceTest`, `GlServiceJournalBalanceTest`, `GlJournalIntegrationTest`, `ReportProfitAggregationIntegrationTest` and `InventoryLossServiceTest` to confirm invariants I1–I5 from the first specification still hold, and that GL debits equal credits for a full month after F23.

## 5. Report template per item

What changed, files touched, tests added, any deviation from this spec, owner decisions applied (with the setting key that flips each one), and confirmation that no Android-facing contract changed.
