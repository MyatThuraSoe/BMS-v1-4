# BMS-v1-4 Audit Round 2: Recheck and Remaining Fixes (Agent Brief)

Repo: MyatThuraSoe/BMS-v1-4, checked at commit `f851c7d` (merge `02c2f9b`). Scope: `bms-backend` and `frontend` only. **Do not touch `Android/`.** Base path: `bms-backend/src/main/java/com/bms`. `svc` = `service`. Method: code reading only. Nothing was compiled or run. Verify each item with a test.

## Rules for the agent

- `BigDecimal` for money, scale 2 (costs scale 6). Keep existing `@Transactional` and row locks.
- One fix = one commit + one test (Mockito style as in `src/test/java/com/bms/service`).
- Run `cd bms-backend && mvn -q test -Dtest=<TestClass>`, then full `mvn -q test`.
- Do not change production code just to make an unrelated test pass (see TASK-5).

## Status of round 1

- BUG-1 void restores stock: **fixed**
- BUG-2 store credit / CREDIT as payment method: **fixed**
- BUG-3 refund method CREDIT: **fixed**
- BUG-4 store-credit cap by creditLimit: **fixed**
- BUG-5 credit-limit check mismatch: **fixed** (via BUG-2)
- BUG-6 freight on PO close: **not a bug.** Variance posts Dr Operating Expense / Cr Expense Payable (`GlService.postExpenses`), the same as every expense. Payable moves from supplier AP to expense payable. No change needed.
- BUG-7 lock order: **fixed**
- BUG-8a split-pay change: **REGRESSION, see TASK-1**
- BUG-8b mixed refund method: **fixed**
- BUG-8c receiving location checks: **fixed**

---

## TASK-1 (High): fix the split-payment regression from BUG-8a

**File:** `svc/SaleService.java`, `createSale()`, non-credit branch, block `if (hasCash) { ... }`. **Current code (after f851c7d):**

```java
BigDecimal cashPortion = paymentLines.stream()
        .filter(line -> paymentValidationService.parseMethod(line.getMethod()) == com.bms.entity.PaymentMethod.CASH)
        .map(SalePaymentRequest::getAmount)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
if (tendered == null) { tendered = cashPortion; }
if (tendered.compareTo(cashPortion) < 0) { throw ... }
sale.setAmountPaid(tendered);
sale.setChangeGiven(tendered.subtract(cashPortion));
```

**Problem:** The POS (`frontend/src/pages/POS.jsx`, \~line 893) sends `amountPaid = saleTotal` for split payments (comment there: split lines are exact portions, change is zero). With cash 40 + card 60, total 100: `tendered=100`, `cashPortion=40`, so `changeGiven=60` and the receipt prints "Change 60" (`ReceiptLayoutBuilder` \~line 136 and `SaleDetail.jsx` \~line 359). **Fix (simple):** Treat split sales as exact. Replace the `if (hasCash) {...}` body with:

```java
if (hasCash) {
    if (paymentLines.size() > 1) {
        // Split tender: lines are exact portions of the total, no cash change.
        sale.setAmountPaid(totalAmount);
        sale.setChangeGiven(BigDecimal.ZERO);
    } else {
        // Single cash line: its amount equals totalAmount (verifyForSale enforces it).
        if (tendered == null) { tendered = totalAmount; }
        if (tendered.compareTo(totalAmount) < 0) {
            throw new BusinessException("Amount paid (" + tendered
                    + ") is less than total amount (" + totalAmount + ")");
        }
        sale.setAmountPaid(tendered);
        sale.setChangeGiven(tendered.subtract(totalAmount));
    }
} else { /* existing non-cash branch unchanged */ }
```

This restores the pre-8a behaviour for every case the POS can produce. Remove the `cashPortion` stream. **Not doing (needs a frontend change too):** real cash change on split sales. If wanted later, the POS must send the cash handed over, and the backend must store `amountPaid = cashTendered + nonCashLines` and `change = cashTendered - cashLine`, because `ReceiptDocument.jsx` computes change as `amountPaid - totalAmount`. **Tests (add to a `SaleService` create-sale test):**

- cash 40 + card 60, `amountPaid=100`, total 100 -> `changeGiven == 0`, `amountPaid == 100`.
- single cash line, total 100, `amountPaid=150` -> `changeGiven == 50`.
- single cash line, `amountPaid=90` -> throws.

---

## TASK-2 (Medium): add the missing regression tests for round 1

The fix commit only changed `SaleServiceVoidCashTrailTest`. Add one test per item (Mockito, no DB):

- **BUG-1:** void a sale where another later sale of the same product exists -> `product.stockQuantity` increases by the sold quantity (or by quantity minus already refunded) and an `ADJUSTMENT_IN` `StockMovement` is saved.
- **BUG-2:** `PaymentValidationService.verifyForSale` throws for a `STORE_CREDIT` line. `parseSettlementMethod("CREDIT")` and `("STORE_CREDIT")` throw; `("CARD")` and `("CASH")` pass. `ArService.recordPayment` with `paymentMethod="STORE_CREDIT"` throws before any balance change.
- **BUG-3:** return on a non-credit sale with `refundMethod="CREDIT"` throws. A credit-sale return that only reduces debt (refund due 0) still succeeds.
- **BUG-4:** `STORE_CREDIT` refund larger than the customer's `creditLimit` (limit 50, refund 200) succeeds and `creditBalance` rises by 200.
- **BUG-7:** convert an order whose items are given in descending product-id order; assert `findByIdForUpdate` was called in ascending id order (use `InOrder`).
- **BUG-8b:** return with no `refundMethod` on a sale with CASH + CARD payments throws "Choose a refund method"; with an explicit method it passes.
- **BUG-8c:** `receiveUnits` with `quarantine > 0` and no quarantine location throws before the ledger is posted. NOTE: today the ledger `postInventory` runs before the location checks in `PurchaseService.receiveUnits` (the `destination == null` and `quarantineLocation == null` checks come after it). The transaction rolls back, so data stays correct, but move both checks to the top of `receiveUnits` so nothing is written first.

---

## TASK-3 (Low-Medium): `LoginAttemptPolicy` client IP

**File:** `svc/LoginAttemptPolicy.java`, the `X-Forwarded-For` handling (changed in f851c7d). **Change made:** loops through hops and returns the first non-blank one. Old code returned `split(",")[0]` or `remote` if blank. **Problem:** If the first hop is blank (`", 10.0.0.5"`), it now returns a later hop, which is a proxy address. Attackers can pick which key a failed login is counted under, so the lockout can be sidestepped or aimed at another address. **Fix:** Revert to: take `split(",")[0]`, trim it; if blank return `remote`. Do not scan later hops. Check which test needed the change (`UserControllerUnlockLoginTest` / the policy test) and fix the test expectation instead.

---

## TASK-4 (Low-Medium): `GoogleDriveService.readAllPages` 500-file ceiling

**File:** `svc/GoogleDriveService.java`, `readAllPages` (changed in f851c7d). **Change made:** returns after 500 files with no warning. **Problem:** If the Drive folder holds more than 500 backups, the restore list silently omits the rest. Depending on the order requested from Drive, the newest or oldest backups may be missing. **Fix:** Check the query's `orderBy`; it must be newest first (`createdTime desc`) so the cap drops only the oldest. Log a warning when the ceiling is hit: `log.warn("Backup list truncated at {} files", ceiling)`. Make the ceiling a named constant. Add a test with 600 mocked files across pages asserting 500 returned, newest first.

---

## TASK-5 (Process): keep production changes separate from test fixes

Commit f851c7d bundled `LoginAttemptPolicy`, `GoogleDriveService` and `UserService` changes with the audit fixes, described as "pre-existing test issues". Rule for the agent: if a test fails for an unrelated reason, report it and fix in a separate commit with its own explanation. Never loosen production behaviour to satisfy a test without checking the test is right. `UserService.assertNoOpenDrawerOwnedBy` (blocks deactivating or deleting a user who owns an open drawer) looks sensible; add a test: deactivate user with an open drawer session -> throws; without -> succeeds.

---

## Not reviewed (next audit targets)

`ReportService` (revenue and profit vs discounts and returns; `SaleDiscountUtils` is the source of truth), `GlService` beyond expenses, `StockCountService`, `DataImportService`, backup/restore, `LandedCostAllocator`, most of `frontend/`.

## Done checklist

- [ ] TASK-1 fix + 3 tests
- [ ] TASK-2 tests (8 items) and the `receiveUnits` check reorder
- [ ] TASK-3 reverted and test corrected
- [ ] TASK-4 order check, warning, test
- [ ] TASK-5 `UserService` drawer test
- [ ] `mvn -q test` green
