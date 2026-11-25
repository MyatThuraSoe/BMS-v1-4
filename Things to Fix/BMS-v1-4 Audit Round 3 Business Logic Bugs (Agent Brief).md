# BMS-v1-4 Audit Round 3: Business Logic Bugs (Agent Brief)

Repo: MyatThuraSoe/BMS-v1-4, checked at `00960a4` (merge of PR #33). Scope: `bms-backend` and `frontend`. **Do not touch `Android/`.** Base path: `bms-backend/src/main/java/com/bms`. `svc` = `service`. Method: code reading only. Nothing was compiled or run. Confirm each item with a test before you fix it.

Round 2 TASK-1 (split-payment change) is fixed. Every item below is new.

## Rules for the agent

- Same rules as round 2: `BigDecimal` money at scale 2 (costs at scale 6). Keep the existing `@Transactional` boundaries and row locks. One fix per commit, with one test per fix.
- Run `cd bms-backend && mvn -q test -Dtest=<TestClass>`, then the full `mvn -q test`.
- The ledger read path (`LedgerReadService.isActive()`) is now the default after auto-cutover. Fix that path first. Only touch the legacy path where it says so.

## Summary

| # | Sev | Area | One line |
|---|-----|------|----------|
| 1 | High | Stock count | The system quantity is captured when the sheet is created, not when it is counted. Units sold in between are booked as shrinkage and deducted a second time. |
| 2 | High | GL (ledger) | Voiding a credit invoice that was partly or fully paid leaves AR negative and Cash overstated. |
| 3 | High | GL (ledger) | Recoverable purchase input tax is never journaled. AP goes negative by the tax amount once the supplier is paid. |
| 4 | High | Store credit | Store credit can be issued but never redeemed, paid out or netted. The liability only grows, and the customer can never be deleted. |
| 5 | Med-High | Cash drawer | Blind close can be probed: the tolerance error tells the cashier how close their count is. |
| 6 | Medium | Void | A late cash void takes the money out of whichever drawer is open first, not the voiding user's drawer. |
| 7 | Medium | Returns | BOGO and volume-tier lines refund the average unit price, so a customer can return part of a deal and keep the discount. |
| 8 | Medium | Supplier | A return to vendor after full payment loses the supplier credit. Nothing records that the supplier owes money back. |
| 9 | Medium | Customer stats | Spend and LTV subtract `amountReturned`, which is only set on credit sales. Cash-sale returns are never deducted. |
| 10 | Medium | Reports | Sale-date reports change after the fact when a refund lands. They disagree with the ledger-dated profit and accounting figures. |
| 11 | Medium | Reports | Profit by supplier mixes currencies, compares purchased cost against sold revenue, and drops multi-sourced revenue. |
| 12 | Medium | GL | Expense Payable is credited for every expense and never settled. Cash pay-outs post to Cash Clearing with no link to it. |
| 13 | Low-Med | Orders | Converting an order skips the active/deleted check on the credit customer and rejects zero-total orders. |
| 14 | Low-Med | Frontend POS | A blank or zero quantity is silently changed to 1 and sold. |
| 15 | Low | Discounts | A manager override lifts the cashier discount cap with no upper limit at all (the manager cap is ignored). |
| 16 | Low | Reports | WEEK and MONTH ranges include one extra day. |
| 17 | Low | Reports | SQL discount allocation does not trim over-allocated cents the way `SaleDiscountUtils` does. |
| 18 | Low | Shift report | "Refunds" means cash refunds on cash shifts but full return value (including debt reduction) on non-cash shifts. They are then summed together. |
| 19 | Low | Purchases | Payments can be dated before the purchase. `createPurchase` does not check whether the supplier is active. |

---

## 1 (High): Stock count snapshot is taken at sheet creation

**Files:** `svc/StockCountService.java` (`buildSheet`, `start`, `applySheet`).

**Code:** `buildSheet` sets `item.setSystemQuantity(product.getStockQuantity())` when the sheet is created. That happens at 03:00 for scheduled sheets (`scheduledCountSheetGeneration`), or whenever a manager creates a manual sheet. `start()` does not refresh it. `applySheet` then computes `variance = counted - systemQuantity` and sets stock to `live + variance`.

**Problem:** Take 10 units at 03:00. Three are sold at 10:00, and the count at 11:00 finds the correct 7.
- Variance: `7 - 10 = -3`, booked as shrinkage loss.
- New stock: `live 7 + (-3) = 4`. It should be 7.

Every unit sold between creating the sheet and counting it becomes a fake loss and is deducted a second time. If the variance is small, auto-approve applies it with no review. Only the mobile `sync` path uses a count-time quantity (`countTimeSystemQty`).

**Fix:** Capture `systemQuantity` when the item is counted, not when the sheet is built. Either refresh every item in `start()`, or (better) snapshot per item in `submit` under the product lock, as `sync` does. Use `live + (counted - snapshotAtCount)` only when the snapshot matches the moment of counting. Keep the creation-time value in a separate column if the UI needs it.

**Test:** Create a sheet with stock 10, sell 3, then submit a count of 7. Expect variance 0, stock 7, and no loss movement.

## 2 (High): Ledger GL for a void of a paid credit invoice

**Files:** `svc/LedgerPostingService.postVoidProfit` / `snapshotSaleSettlement`, `svc/GlService.postArPayments`, `postDrawerMovements`.

**Problem:** Take a credit invoice of 100, with 60 already paid in cash through AR, and void it.
- `postVoidProfit` settles the whole remaining line value against AR, because the sale's only payment line is `CREDIT`. The result is Cr AR 100.
- `postArPayments` skips the reversal of AR payments on voided invoices. Its comment says `postVoidReversal` handles them, but that method is **legacy-only**.
- The cash given back (`CASH_REFUND` movement, `referenceType = "SALE"`) is skipped by `postDrawerMovements`.
- Net effect: AR ends at -60 and Cash stays +60, even though the cash went back to the customer. A non-cash AR payment that became store credit never reaches Refund Liability.

**Fix:** In the ledger path, split the void settlement. AR gets only the unpaid part, which is `total - amountPaid - amountReturned` share. The paid part goes to Cash for cash AR payments that were refunded, or to Refund Liability for non-cash payments that became store credit. Alternatively, journal the `ARP-REV` rows for voided invoices when the ledger is active.

**Test:** Run the journal for the scenario above. Expect AR = 0 and Cash = 0 overall, with Refund Liability equal to the non-cash paid part.

## 3 (High): Recoverable input tax is missing from the ledger journal

**Files:** `svc/GlService.journal` / `postLedger`. `INPUT_TAX` is only posted in the legacy `postPurchases` (~line 397).

**Problem:** With `taxRecoverable = true`, the receipt values inventory without the tax. `InventoryLedger RECEIPT` therefore credits AP for goods only. The purchase `totalAmount`, and so the supplier payment, does include the tax. `postPurchasePayments` debits AP for the full amount, so AP drifts negative by the tax and Input Tax Recoverable never appears. Return to vendor (`rtvCreditAmount`) has the same gap.

**Fix:** In the ledger path, post Dr Input Tax / Cr AP for each received share of recoverable tax (use `scaledToReceived`, dated at receipt). Post the reverse for the RTV tax share.

**Test:** Recoverable PO of 100 + 10 tax, received and paid in full. Expect AP = 0 and Input Tax = 10.

## 4 (High): Store credit can never be used

**Files:** `svc/SaleReturnService` (issues it), `SaleService.voidSale` / `reverseCreditBalance`, `ArService.recordPayment` (overpayment becomes credit), `PaymentValidationService.verifyForSale` and `parseSettlementMethod` (both reject `STORE_CREDIT`), `CustomerService.deleteCustomer` (blocks deletion when credit > 0).

**Problem:** The only writes to `creditBalance` are additions. No code redeems it as tender, pays it out in cash, or nets it against the same customer's `currentBalance`. The Refund Liability account and `creditBalance` therefore grow forever. A customer with any credit can never be deleted. A customer can also owe 500 on one credit invoice while holding 200 of store credit, and the two are never offset.

**Fix (choose one, then implement it fully):**
- (a) Allow `STORE_CREDIT` as a sale tender and an AR settlement method, capped at `creditBalance`, with the customer row locked. Post Dr Refund Liability / Cr AR or Revenue settlement.
- (b) Add an admin "pay out store credit" action (cash from the drawer) and an "apply credit to AR" action.

Option (a) covers both and matches what the user sees.

## 5 (Med-High): Blind close can be probed

**File:** `svc/CashDrawerSessionService.closeSession`.

**Problem:** A non-manager who enters a count outside tolerance gets "Drawer variance exceeds the allowed tolerance". By retrying, they can search for the expected cash total (count up or down until the close succeeds), which defeats blind close.

**Fix:** For blind sessions, never reject on variance. Accept the count, mark the session `PENDING_REVIEW` (or close it and flag it for a manager), and do not show the result to the cashier. Alternatively, allow only one count attempt and require a manager after that. Log every attempt.

## 6 (Medium): Late cash void uses an arbitrary drawer

**File:** `svc/SaleService.voidSale` (late-void branch).

**Problem:** When the original session is closed, the code loops over `getActiveDrawers()` and uses the **first** open session, whoever owns it. The cash PAY_OUT then reduces another cashier's expected cash, and that cashier shows a shortage.

**Fix:** Use the open drawer session of the voiding user's own `CASH_DRAWER` shift. If there is none, reject the void with "Open your cash drawer shift to void this cash sale".

## 7 (Medium): Promotion abuse through partial returns

**Files:** `svc/PromotionService.priceForPromotion` (BOGO and TIERED return one averaged unit price), `SaleReturnService` / `SaleDiscountUtils.unitRefundAmount`.

**Problem:** With buy 2 get 1 at 10 each, three units cost 20, which is 6.67 per unit. Returning one unit refunds 6.67, and the customer keeps two units for 13.33 when two units cost 20 without the deal. Volume tiers behave the same way: buy 10 at 20% off, return 9, and keep the discount on 1.

**Fix:** When pricing a return, re-price the units the customer keeps (`sold - alreadyReturned - returning`) with the same promotion and quantity rule as at sale time. Refund the difference between what was paid and what the kept units would cost. Never refund more than the units' paid share. Add a setting if the shop wants the old behaviour.

## 8 (Medium): Supplier credit lost after full payment

**Files:** `svc/PurchaseService.returnToVendor`, `PurchasePaymentService.deriveStatus` / `getPayablesAging`.

**Problem:** If a purchase is already fully paid, `returnedAmount` pushes `payable - paid` below zero. The status shows PAID, aging skips the purchase (`outstanding <= 0`), and nothing records that the supplier owes the shop money.

**Fix:** Show a negative outstanding as "supplier credit" in aging and on the supplier. Add a supplier refund (cash in) or "apply credit to another PO" action. At minimum, show the credit on the PO response.

## 9 (Medium): Customer spend ignores cash-sale returns

**Files:** `repository/SaleRepository` (`sumTotalAmountByCustomerIdAndDateAfter`, `findDailySpendingByCustomerIdAndYear`, `findCustomerLtvData`). `SaleReturnService` only sets `amountReturned` on CREDIT sales (~line 445).

**Problem:** A returned cash sale still counts at its full value in customer stats, daily spending and LTV.

**Fix:** Subtract `SUM(sale_returns.total_return_amount)` per sale, using a join or a correlated subquery, instead of `s.amountReturned`. You can also set `amountReturned` for every sale type, but check first that AR queries filter on `saleType = CREDIT` (they do today).

## 10 (Medium): Two revenue clocks

**File:** `svc/ReportService`.

**Problem:** These reports use `lineRevenueSql()`, which nets refunded units against the **sale date**: daily, monthly, product performance, top products and categories, cashier performance, and sales trend. A refund today therefore changes last month's daily report. Profit summary, profit trend (ledger) and accounting summary post the refund on the **return date**. For the same month, "Daily sales total" and "Accounting income" differ.

**Fix:** When the ledger is active, feed the sales reports from `profit_ledger`, grouped on `occurred_at` by product, cashier or category, so every report uses the event date. Keep `lineRevenueSql` for the legacy path only. If sale-date reporting is wanted, label it "net of later returns".

## 11 (Medium): Profit by supplier is not a profit figure

**File:** `svc/ReportService.getProfitBySupplier`.

**Problem:**
- `total_supplied_cost` uses `pi.unit_cost`, which is in the invoice currency, before discount and landed cost, with no exchange rate. The revenue side is in the base currency.
- The cost is for goods **purchased** in the window, but the revenue is for goods **sold** in the window.
- Revenue is limited to products supplied only by this supplier, but cost includes every product. Multi-sourced products therefore add cost and no revenue.

**Fix:** Base it on COGS. For each sale line, find the supplier through the receipt layer or the last-receipt supplier. Revenue is `netItemRevenue`, cost is `costPriceAtSale * qtySold`, and both are in the base currency. Otherwise, rename the report to "Purchases vs sales by supplier" and fix the currency at least.

## 12 (Medium): Expense Payable is never settled

**File:** `svc/GlService` (`postLedger` and `postExpenses` post Dr Opex / Cr Expense Payable, while `postDrawerMovements` posts PAY_OUT as Dr Cash Clearing / Cr Cash).

**Problem:** Expense Payable grows forever and Cash Clearing builds up a matching debit. The two are never matched, so the balance sheet is wrong.

**Fix:** Add a payment method and a paid flag to `Expense`. A cash expense creates a linked drawer PAY_OUT and journals Dr Expense Payable / Cr Cash. Skip that movement in `postDrawerMovements`, as is already done for `PURCHASE_PAYMENT`. As a simpler alternative, expenses marked "paid in cash" post Dr Opex / Cr Cash directly.

## 13 (Low-Med): Order conversion checks

**File:** `svc/SaleService.createSaleFromOrder`.

- The credit customer is not checked for `isActive` / `deletedAt`, unlike `createSale`. Add the same guard.
- `!isCredit && amountPaid <= 0` throws before the zero-total branch, so a fully discounted order cannot be converted without sending a fake amount. Move the zero-total check first.

## 14 (Low-Med): POS turns a blank or zero quantity into 1

**File:** `frontend/src/pages/POS.jsx` (~lines 791-797 and 867/884: `parseInt(...) || 1`).

**Problem:** If a cashier clears the quantity or types 0 to remove a line, 1 unit is sold and charged.

**Fix:** Remove lines with a quantity of 0 or less, or block checkout with "Enter a quantity", instead of falling back to 1.

## 15 (Low): Manager override has no cap

**File:** `svc/SaleService.assertDiscountWithinAuthority`.

**Problem:** `if (!manager && managerOverride) return;` lets a cashier with manager approval give any discount up to 100%, ignoring `shop.discount.maxPercentManager`.

**Fix:** With an override, check the amount against the manager cap instead of returning early.

## 16 (Low): WEEK and MONTH range off by one

**File:** `svc/ReportService.resolveStartDate`.

**Problem:** `endDate.minusWeeks(1)` with both ends included gives 8 days, and `minusMonths(1)` gives 31-32 days.

**Fix:** Use `minusDays(6)` for WEEK, and `minusMonths(1).plusDays(1)` or the first of the month for MONTH.

## 17 (Low): SQL vs Java discount allocation

**File:** `svc/ReportService.lineRevenueSql`.

**Problem:** `SaleDiscountUtils.allocateDiscounts` trims extra cents when the rounded shares add up to more than the discount. The SQL only clamps the remainder line to 0. Legacy reports can therefore show slightly less revenue than the ledger.

**Fix:** Mirror the trim in SQL, or switch to the ledger (#10).

## 18 (Low): Mixed "refunds" in the shift report

**Files:** `svc/CashShiftService.populateStats`, `ReportService.getEmployeeShiftReport`.

**Problem:** On a cash shift, `returnsTotal` is cash refunds. On a non-cash shift it is `sumTotalByEmployeeShiftId`, which is total return value including the debt-reduction part. The report adds both together as "refunds".

**Fix:** Use `SUM(refundAmount)` per shift for both. Report debt reduction separately.

## 19 (Low): Purchase input checks

**Files:** `svc/PurchaseService.createPurchase`, `PurchasePaymentService.recordPayment`.

- The supplier is not checked for active / not deleted. Products are checked.
- A payment date before `purchase.purchaseDate` is accepted. Reject it.

---

## Not reviewed this round

`LocationService` transfers, `DataImportService`, backup/restore, `PriceListService`, `CounterPrintService`, and most frontend pages other than `POS.jsx`.

## Done checklist

- [ ] 1 stock count snapshot at count time + test
- [ ] 2 ledger void of paid credit invoice + journal test
- [ ] 3 input tax in ledger journal + test
- [ ] 4 store credit redemption (decide a or b) + tests
- [ ] 5 blind close probing
- [ ] 6 late void uses own drawer
- [ ] 7 promo-aware return pricing
- [ ] 8 supplier credit after RTV
- [ ] 9 customer stats net of all returns
- [ ] 10 single revenue clock
- [ ] 11 supplier profit rewrite
- [ ] 12 expense payment settlement
- [ ] 13-19 small fixes
- [ ] `mvn -q test` green
