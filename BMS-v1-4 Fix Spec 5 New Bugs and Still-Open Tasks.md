# BMS-v1-4 Fix Spec 5: New Bugs and Still-Open Tasks

Based on commit `d59d28d` (PR #18). Read the code only; nothing was run. Written in simple words for an AI agent.

## How to work

- Do NOT touch the Android app. Do not change old API shapes. New request fields must be optional. New response fields are OK.
- One item = one pull request. Write the failing test first. Then fix. Then run all tests.
- Money is `BigDecimal`, rounded `HALF_UP`, 2 decimals.
- Schema changes go in `AccountingSchemaMigration` (safe to run many times). New columns must be nullable or have a default. Never add a NOT NULL column without a default.
- Tag **\[Decision\]** = owner choice. Use the default given and put it in a setting.

## Order of work

1. N2 (upgrade can break the app)
2. N1 (wrong accounting for returns)
3. N3 (customer gets money twice on void)
4. N4, N5, N6, N7
5. Then the "Still-open tasks" list at the end

---

## N2. Upgrade can break existing databases (High)

**Files:** `entity/User.java`, `config/AccountingSchemaMigration.java`, `service/UserService.java`, `config/DatabaseSeeder.java`.

**Problems:**

1. `users.token_version` is `NOT NULL` with no default. The app uses `ddl-auto: update`. On H2 and SQLite, adding a NOT NULL column to a table that already has rows fails. Then every user query can fail.
2. `username_normalized` and `email_normalized` are never filled for old users. They stay null.
3. `UserService.updateUser` does `user.getUsernameNormalized().equals(...)`. For an old user this is a NullPointerException (HTTP 500 when you edit any old user).
4. Duplicate check uses the normalized column only. Old users have null there, so `Admin` can still be created next to old `admin`.
5. The duplicate-account cleanup from spec 4 (T2) was not built.

**Fix steps:**

1. In `User.java` make `tokenVersion` nullable in the column (remove `nullable = false`). Keep Java default `0`.
2. In `AccountingSchemaMigration` add `ensureNullableColumn` for `users.token_version` (INTEGER), `users.username_normalized` (VARCHAR 255), `users.email_normalized` (VARCHAR 255).
3. Add a startup step, safe to repeat, that runs BEFORE the seeder creates the default admin:
   - `UPDATE users SET token_version = 0 WHERE token_version IS NULL`
   - For every user with null `username_normalized`: set it to `lower(trim(username))`. Same for email (skip null emails).
   - Find duplicates by normalized name. Keep the lowest id as is. For each later duplicate: set normalized to `<name>#dup_<id>`, set `is_active = false`, write audit `USER_DUPLICATE_DETECTED`. Never delete a user.
4. After the backfill, create unique indexes on `username_normalized` and `email_normalized` (skip if exists; null emails are allowed).
5. In `UserService.updateUser` replace the `.equals` calls with `Objects.equals(normalizeKey(user.getUsername()), normalizedUsername)` and the same for email. Never call a method on a possibly-null normalized field.
6. In `createUser` and `updateUser` also check `existsByUsernameIgnoreCase` as a safety net for rows not yet backfilled.

**Tests:**

- Start the app on an old-style database (no new columns, 3 users, two named `admin` and `Admin`). App starts. No exception.
- After start: all users have normalized values; the second duplicate is inactive; one audit row per duplicate.
- Editing an old user works (no 500).
- Creating `ADMIN` is rejected. Login with any case reaches one account.

---

## N1. Ledger and GL for returns are wrong (High)

**Files:** `service/LedgerPostingService.java` (`postReturnProfit`, `snapshotRefundSettlement`), `service/SaleReturnService.java` (call site), `service/GlService.java` (`postLedger`, `addSettlementSplit`), `entity/ProfitLedger.java`, `config/AccountingSchemaMigration.java`.

**Problems:**

1. **Wrong sign.** GL treats a positive settlement as a DEBIT and a negative one as a CREDIT. For a return row the money goes OUT, so the amount must be negative. The new code stores a cash refund as positive (`setSettlementCash(refund)`) and card refund as positive (`setSettlementClearing(refund)`). So a cash refund debits cash. The journal does not balance.
2. **Same total on every line.** `postReturnProfit` runs once per returned item. Each call gets the WHOLE return's `debtReductionAmount` and `refundAmount`. A return with 2 items posts the settlement twice.
3. **Refund method CREDIT with refund above 0** sets only the account, no amount. The entry is missing.
4. **Store credit** needs an explicit amount. Today GL guesses it from `abs(revenue + tax) - debt` using the whole-return debt.

**Fix steps:**

1. In `SaleReturnService.createSaleReturn`, before posting lines, split the debt and refund over the lines:
   - `lineTotal` = the return amount of that line (the same number used for `returnAmount`).
   - `lineDebt = round(debtReduction * lineTotal / totalReturnAmount)`. The LAST line gets `debtReduction` minus the sum of earlier lines (so the total is exact).
   - `lineRefund = lineTotal - lineDebt`.
2. Change `postReturnProfit(saleReturn, item, qty, alreadyReturned, lineDebt, lineRefund)`. Pass these into `snapshotRefundSettlement`.
3. In `snapshotRefundSettlement` set all parts NEGATIVE (credits):
   - debt part: `settlementAr = -lineDebt`
   - refund part by method: CASH -> `settlementCash = -lineRefund`; CARD, BANK or other non-cash -> `settlementClearing = -lineRefund`; CREDIT (AR) -> add to `settlementAr`; STORE\_CREDIT -> new column `settlement_refund_liability = -lineRefund`.
4. Add column `profit_ledger.settlement_refund_liability` (DECIMAL, nullable) in the migration. In `GlService.addSettlementSplit` post it as an entry on the `REFUND_LIABILITY` account (same signed rule). Delete the special `REFUND_LIABILITY` block in `postLedger` that recomputes the amount.
5. Add a guard at the end of `snapshotRefundSettlement`: `abs(sum of settlement parts)` must equal `abs(revenue + tax)` of the row (allow 0.01). If not, throw `BusinessException`. This stops silent wrong books.
6. Legacy path `GlService.postSaleReturns` already uses debt and refund correctly. Do not change it.
7. Repair old rows: write an admin-only, repeat-safe repair that recomputes the settlement of RETURN rows whose `SaleReturn.refund_amount` is not null (these were posted by PR #18 code). Needs the backup confirmation used by the other backfill endpoints. Skip rows in closed periods and list them in the result.

**Tests:**

- 1 item, cash refund 60: journal credits cash 60; debits revenue and tax = 60; balanced.
- 2 items, one return: settlement total equals return total (not double).
- Card refund: credits tender clearing.
- Store credit: credits refund liability with the right amount.
- Credit invoice, paid 80 of 100, return 60: AR credit 20, cash credit 40.
- For a full month with sales, returns, voids: total debits = total credits.

---

## N3. Void of a credit sale counts non-cash payments twice (Medium-High)

**File:** `service/SaleService.java` (`voidSale`, the block that converts non-cash AR payments).

**Problem:** For non-cash AR payments the code adds the amount to `creditBalance` (correct) AND subtracts the same amount from `currentBalance` (wrong). The paid part was already taken off the balance when the customer paid. The outstanding part is already handled by `reverseCreditBalance(sale)`. Example: balance 120 (100 other debt + 20 left on this invoice), card paid 80, void. After the void the customer should owe 100 and have 80 credit. The code gives owes 20 and credit 80.

**Fix:** Remove the `setCurrentBalance(... subtract(convertedNonCash) ...)` line. Only add `convertedNonCash` to `creditBalance`.

**Tests:** Use the example above: final balance 100, credit 80. Cash AR payments still refund from the drawer. Void twice is rejected.

---

## N4. STORE\_CREDIT refund on a non-credit sale does nothing (Medium)

**Files:** `service/SaleReturnService.java`, `frontend/src/components/SaleReturnDialog.jsx`, `dto/response/ReturnableItemsResponse.java`.

**Problem:** The store credit block only runs for CREDIT sales. For a cash or card sale with `refundMethod = STORE_CREDIT`: no cash is paid, no credit is added, but the GL books a refund liability. The dialog offers STORE\_CREDIT for every sale.

**Fix:**

1. Move the store-credit block out of the `if CREDIT` branch. Run it for any sale when `refundMethod == STORE_CREDIT` and `refundDue > 0`.
2. If the sale has no customer, throw `BusinessException("Store credit needs a customer on the sale")` before anything is saved.
3. Add `hasCustomer` (boolean) to `ReturnableItemsResponse`. In the dialog show the STORE\_CREDIT option only when `hasCustomer` is true.

**Tests:** Cash sale with customer, return 30 as store credit: `creditBalance` +30, no drawer movement. Same with no customer: rejected, nothing saved.

---

## N5. Return dialog estimates ignore the selection (Low-Medium)

**Files:** `frontend/src/components/SaleReturnDialog.jsx`, `service/SaleReturnService.getReturnableItems`.

**Problem:** `estimatedDebtReduction` and `estimatedRefundDue` are computed for returning EVERYTHING. The dialog shows them even when the user picked only some items. The method selector shows or hides using the wrong number.

**Fix (no new endpoint):** In the dialog compute from the selection: `selected = sum(qty * unitRefundAmount)` for the ticked items; `debt = min(selected, outstandingBefore)` for credit sales (0 otherwise); `refund = selected - debt`. Show the selector only if `refund > 0`. Keep the server numbers only as a fallback. The server still does the real split.

**Tests:** Component test: credit invoice outstanding 20, select an item worth 15 -> refund 0, no selector; select worth 60 -> debt 20, refund 40, selector shown.

---

## N6. Force-close of a user's shift (Low)

**File:** `service/UserService.java` (`closeUserOpenShift`, `updateUser`, `deleteUser`), `controller/UserController.java`.

**Problems:** (a) The shift is closed with the deactivated user's own id as the actor, so the audit shows the cashier closed it. (b) `closeDrawer = false` can leave a drawer session open with no shift.

**Fix:**

1. Pass the acting manager's id (from the logged-in user) into `closeUserOpenShift` and use it as the actor in `cashShiftService.closeShift`.
2. If the user's shift is the only open shift on its drawer session, do not leave the drawer open: reject with "Close the drawer first, then retry" (unless the existing close-drawer flow can run with a counted amount). If other shifts are on the drawer, close only this shift.

**Tests:** Audit row shows the manager as actor. Sole-shift drawer case is rejected with the message.

---

## N7. Test hooks in production code (Low)

**Files:** `service/LocationService.java`, and every service with `private ShopTimeService shopTimeService = new ShopTimeService(null, "UTC")`.

**Fix:** In `startTransfer` remove the `identityHashCode` fallback; call `sequenceService.nextTransferNumber()` only. Remove field initializers like `new ShopTimeService(null, "UTC")`; use normal `@Autowired` (or constructor injection) and give tests a mock bean. Delete dead code in `AccountingPeriodService.close` (the unused `cursor` and `firstOpen` loops). Run the formatter.

**Tests:** Existing tests still pass with injected mocks.

---

## Still-open tasks

These came from earlier specs. For each one: first check the current code. If PR #18 did not change it, do it now. Spec numbers: 1 = first spec, 2 = second, 3 = third, 4 = fourth.

| ID | Task (short) | What I saw at `d59d28d` |
| --- | --- | --- |
| O3 (spec 3) | Lock the purchase row in `receivePurchase`, `returnToVendor`, `deletePurchase`, `updatePurchase` | `PurchaseRepository` has `findByIdForUpdate` and `updateReceivingStatus` but `PurchaseService` does not call them |
| S1 (spec 3) | Auto-approved count submit must fall back to PENDING\_REVIEW instead of failing with 422 | `StockCountService` changed by 2 lines only; not done |
| S2 (spec 3) | Bad setting value must use the default, and settings save must reject non-numbers | not seen |
| S3 (spec 3) | Post positive stock-count variance as an inventory gain | not seen |
| S4 (spec 3) | Legacy `POST /api/products/{id}/stock/adjust` needs optional `reasonCode` (default COUNT\_CORRECTION) | not seen |
| O4 (spec 3) | Bulk delete of old sales: archive or full FK-safe delete; refuse closed periods; use `source_sale_id` and `source_return_id` | columns added in migration, delete logic not changed |
| O5 (spec 3) | Enforce cashier discount limit and below-cost check after discount | settings added, no enforcement seen in `SaleService.computeDiscount` |
| O7 (spec 3) | Legacy shift close: sum refunds by shift, not by time window | not seen |
| O8 (spec 3) | Expire pending order reservations with a scheduled job | `Scheduled` import added in `OrderService`; check the job exists, runs, and respects `shop.order.reservationHours` (0 = off) |
| O6 (spec 3) | Order promotion fields | done in PR #18; only add a test that an order sale equals a direct sale in the promotion report |
| T1 (spec 4) | Period close: enforce `requireNoOpenDrawers` and `minDaysAfterMonthEnd` | settings added; the close method only checks completed month and sequence |
| T3 (spec 4) | Customer delete must also block on unpaid credit invoices and pending orders, and pass the acting user to the audit | only balance and store-credit checks added |
| T6 (spec 4) | Honour `auth.token.requireVersionClaim`; log out other sessions after password change; send a fresh token to the current session | setting added; `JwtUtil.validateToken` ignores it |
| T2 (spec 4) | Duplicate account cleanup | see N2 above |
| F22 (spec 2) | `createSaleFromOrder`: after releasing the order's own reservation, stock must stay at or above other orders' reserved units | not seen |

## Final checks (run after all items)

- Re-run all existing tests plus the new ones.
- For a test month with sales, credit sales, partial returns, multi-item returns, card refunds, store credit and voids: GL total debits = total credits, and cash account = drawer expected cash.
- Start the app on a copy of an old database and confirm login, editing a user and making a sale still work.
- Confirm no Android-facing contract changed.

## Report format per item

One short block: what changed, files, tests added, any difference from this spec, setting keys used.
