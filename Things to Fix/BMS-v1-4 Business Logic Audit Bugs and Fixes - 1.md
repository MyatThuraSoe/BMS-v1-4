# BMS-v1-4 Business Logic Audit: Bugs and Fixes (Agent Brief)

Repo: MyatThuraSoe/BMS-v1-4 (branch main). Scope: `bms-backend` (Spring Boot) only. **Do not touch `Android/`.** Base path: `bms-backend/src/main/java/com/bms`. Abbrev: `svc` = `service`. Status: found by code reading, not by running. Reproduce with a unit test first, then fix.

## Rules for the agent

- Money is `BigDecimal`; keep scale 2 (costs scale 6). Never use `double`.
- Keep existing `@Transactional` and `findByIdForUpdate` locks.
- Change the minimum. One bug = one commit + one test (Mockito style as in `src/test/java/com/bms/service`).
- Run: `cd bms-backend && mvn -q test -Dtest=<TestClass>` then the full `mvn -q test`.
- Priority: BUG-1, 2, 3, 4 (fix now), then 5, 7, 8. BUG-6 is verify-first.

---

## BUG-1 (High): void skips stock restore

**File:** `svc/SaleService.java`, `voidSale()`, loop over `sale.getItems()` (\~line 900). **Code:**

```java
if (saleRepository.productSoldAfter(lockedItem.getProduct().getId(), sale.getSaleDate())) {
    continue;
}
```

**Problem:** Stock is one aggregate count. A later sale of the same product does not consume the voided units. The skip means the goods are back on the shelf but the system never adds them, so inventory is permanently short. `postVoidProfit` (COGS reversal) already ran above, so ledger inventory and COGS no longer match. **Fix:** Delete that `if` block. Always run the existing restore code (`postInventory` VOID, `setStockQuantity`, `StockMovement`). Optional cleanup: delete `SaleRepository.productSoldAfter` (only used here and in a test). **Test:** `SaleServiceVoidCashTrailTest` line \~282 stubs `productSoldAfter` to `true`; remove the stub. Add: void a sale whose product also has a later non-voided sale, then assert `product.stockQuantity` increases by the sold qty and a `StockMovement` ADJUSTMENT\_IN is saved.

---

## BUG-2 (High): store credit and CREDIT used as a payment method

**Facts:**

- `entity/PaymentMethod` has `CREDIT` and `STORE_CREDIT`.
- `PaymentValidationService.verifyForSale` only blocks `CREDIT` on non-credit sales and `non-CREDIT` on credit sales. `STORE_CREDIT` passes with no customer and no balance check, so a sale is marked PAID with no money.
- `ArService.recordPayment` and `PurchasePaymentService` call `parseMethod` and accept `CREDIT` / `STORE_CREDIT`: debt is cleared with no cash. For `STORE_CREDIT` the customer's `creditBalance` is not reduced either.
- Nothing in the codebase ever subtracts from `Customer.creditBalance`. It only grows (returns, overpayments, voids).
- `createSale` subtracts `creditBalance` in the credit-limit check (`effectiveBalance`) but never consumes it, so credit raises headroom and stays.

**Simple fix (phase 1):**

1. In `PaymentValidationService.verifyForSale`, inside the payment loop, add:

```java
if (method == PaymentMethod.STORE_CREDIT) {
    throw new BusinessException("PAYMENT_INVALID: Store credit cannot be used as a payment method.");
}
```

2. Add a helper in `PaymentValidationService`:

```java
public PaymentMethod parseSettlementMethod(String raw) {
    PaymentMethod m = parseMethod(raw);
    if (m == PaymentMethod.CREDIT || m == PaymentMethod.STORE_CREDIT) {
        throw new BusinessException("PAYMENT_INVALID: " + m + " is not a valid settlement method.");
    }
    return m;
}
```

3. Use it in `ArService.recordPayment` and `PurchasePaymentService` (the `paymentValidationService.parseMethod(request.getPaymentMethod())` call, non-blank branch).
4. In `SaleService.createSale` credit branch, drop the store-credit offset so it matches `createSaleFromOrder`:

```java
BigDecimal newBalance = creditCustomer.getCurrentBalance().add(totalAmount);
```

(remove `creditBalance` / `effectiveBalance`). 5. Check `frontend/src` (POS, AR, purchase payment dialogs) does not offer STORE\_CREDIT or CREDIT as an AR/purchase payment option; hide it if it does.

**Phase 2 (only if the owner wants redeemable store credit):** on STORE\_CREDIT tender require `sale.customer`, lock the customer, check `creditBalance >= amount`, subtract it, and post a GL entry. Out of scope for phase 1. **Test:** `verifyForSale` throws for STORE\_CREDIT; `recordPayment` throws for CREDIT and STORE\_CREDIT; credit sale limit check ignores `creditBalance`.

---

## BUG-3 (Medium): refund method CREDIT pays nothing

**File:** `svc/SaleReturnService.java`, `createSaleReturn()` and `resolveRefundMethod()`. **Problem:** If the request sends `refundMethod = "CREDIT"` and `refundDue > 0`, `resolveRefundMethod` returns CREDIT. Nothing then moves cash or store credit, but `refundAmount` is recorded. The refund is never paid. Note: `resolveRefundMethod` legitimately returns CREDIT when a credit sale has `refundDue <= 0` (debt-only reduction). Keep that. **Fix:** In `createSaleReturn`, right after `PaymentMethod refundMethod = resolveRefundMethod(...)`:

```java
if (refundDue.signum() > 0 && refundMethod == PaymentMethod.CREDIT) {
    throw new BusinessException("Refund method CREDIT is not valid when money is due back.");
}
```

**Test:** return with `refundMethod="CREDIT"` and refund due > 0 throws; credit-sale debt-only return still works.

---

## BUG-4 (Medium): store-credit cap on returns uses creditLimit

**File:** `svc/SaleReturnService.java`, STORE\_CREDIT block near the end of `createSaleReturn` (\~line 459). **Code:**

```java
if (customer.getCreditLimit() != null && customer.getCreditLimit().compareTo(BigDecimal.ZERO) > 0
        && newCreditBalance.compareTo(customer.getCreditLimit()) > 0) {
    throw new BusinessException("Store credit would exceed credit limit ...");
}
```

**Problem:** `creditLimit` is the debt the shop lets the customer run up. It has nothing to do with store credit the shop owes. Customers with limit 0 get unlimited store credit; customers with a limit get legitimate returns rejected. **Fix:** Delete this `if` block. Keep `customer.setCreditBalance(newCreditBalance)` and the save. **Test:** customer with `creditLimit=0` and one with a small limit both accept a STORE\_CREDIT refund larger than the limit.

---

## BUG-5 (Low-Medium): credit-limit check differs between the two sale paths

**Files:** `SaleService.createSale` (subtracts `creditBalance`) vs `createSaleFromOrder` (does not). **Fix:** Resolved by BUG-2 step 4 (both use `currentBalance + totalAmount > creditLimit`). Verify both paths now match. Optional: move the check into one private method `assertWithinCreditLimit(Customer, BigDecimal total)` and call it from both.

---

## BUG-6 (Medium, VERIFY FIRST): freight possibly counted twice on PO close

**File:** `svc/PurchaseService.java`, `recomputeTotalsOnClose()` (\~line 323). **What it does:** On close it sets `purchase.totalAmount` = received goods - discount + tax + **capitalised** expenses (payable to supplier goes down by the unabsorbed freight). It also creates an `Expense` of type `PURCHASE_VARIANCE` for that unabsorbed amount and calls `ledgerPostingService.postExpense(...)`. **Risk:** the same money is removed from payables and added to P&L expense. **Verify:** read `LedgerPostingService.postExpense`. If it credits cash or accounts payable, the books are wrong by the variance. If it credits an accrual/clearing account that was set up when the PO was created, it may be correct. **Fix if wrong:** pick one: (a) keep payable at the full ordered expense and drop the variance expense, or (b) keep the reduced payable and post the variance against the clearing account, not cash/AP. Ask the owner which accounting model applies before changing.

---

## BUG-7 (Low-Medium): deadlock from inconsistent product lock order

**Files:** `SaleService.createSale` (`requestedByProduct = new HashMap<>()`, first pass `findByIdForUpdate`) and `createSaleFromOrder` (loop over `order.getItems()` calling `findByIdForUpdate`). **Problem:** Two concurrent carts with overlapping products can lock rows in different orders. **Fix:**

- `createSale`: `Map<Long, Integer> requestedByProduct = new TreeMap<>();` (sorted by id; first pass then locks in id order).
- `createSaleFromOrder`: iterate `order.getItems()` sorted by product id:

```java
List<OrderItem> orderedItems = new ArrayList<>(order.getItems());
orderedItems.sort(Comparator.comparing(oi -> oi.getProduct().getId()));
```

and loop over `orderedItems` in the first (locking) loop. Leave the rest as is. **Test:** none needed beyond existing tests still passing.

---

## BUG-8 (Low): smaller items

**8a. Split-pay change.** `createSale` non-credit branch: when payments contain CASH, `tendered >= totalAmount` and `change = tendered - total` use the full total, not the cash line. With cash + card the change is wrong. POS dodges it by sending `saleTotal` in split mode. Fix: compute `cashPortion` = sum of CASH payment lines; require `tendered >= cashPortion`; `change = tendered - cashPortion`. Note the drawer movement stores the line amount (net), so keep that.

**8b. Default refund method on mixed payments.** `SaleReturnService.resolveRefundMethod`: if a sale used cash + card, the default is CASH, so the card part is refunded in cash. Fix: if more than one method and no explicit method, throw `BusinessException("Choose a refund method")`.

**8c. Receiving with missing locations.** `PurchaseService.receiveUnits`:

- `quarantine > 0 && quarantineLocation == null` leaves units valued in the ledger but stored nowhere. Fix: throw `BusinessException("No quarantine location configured")` before posting.
- `destination.getType()` can NPE when `destination` is null. Fix: null-check and throw a clear `BusinessException`.

---

## Not reviewed (next targets)

`ReportService` (revenue/profit vs discount and returns), `GlService`, `StockCountService`, `DataImportService`, backup/restore, `LandedCostAllocator`, all of `frontend/`. Use `SaleDiscountUtils` as the source of truth for discount-aware revenue.

## Done checklist

- [ ] BUG-1 fix + test
- [ ] BUG-2 phase 1 + tests (also check frontend payment options)
- [ ] BUG-3 fix + test
- [ ] BUG-4 fix + test
- [ ] BUG-5 confirmed same as BUG-2
- [ ] BUG-6 verified, owner decision recorded
- [ ] BUG-7 fix
- [ ] BUG-8a/b/c fixes
- [ ] `mvn -q test` green
