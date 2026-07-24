# BMS-v1-4 — Requirements Document 2 of 2
## Business Intelligence & Financial Features
Covers: purchase cost tracking / profit calculation, analytics page, refunds, business accounting page.

**Read this document in order — each section depends on the one before it.** Purchase cost tracking has to exist before profit numbers mean anything on the Analytics page; refunds need to correctly reverse both stock and revenue; the Accounting page ties everything together at the end.

---

## 1. Purchase Cost Tracking → Profit Calculation

### 1.1 The problem, concretely

Right now, `Product.costPrice` is a single manually-typed number, only ever set when someone fills out the product form. It has **zero connection to actual purchases.** If you buy 10 pens at $10 total from Factory A, then later 20 pens at $12 total from Factory B, `Product.costPrice` still shows whatever was last typed into the form — not a real reflection of what those pens actually cost you. Any profit calculation built on top of this today would be fiction.

### 1.2 Design decision — weighted average cost (recommended for v1)

There are two real approaches:
- **Weighted average cost** (recommended): every purchase blends into a single running average cost per product. Simple, one number per product, matches how most small shops actually think about cost ("on average, what do my pens cost me right now").
- **FIFO/lot tracking** (more accurate, much more work): every batch of stock remembers its own specific purchase cost, and sales consume the oldest batch first. This needs a new `stock_lots` table and changes how every sale deducts stock. Given the SME/v1 framing established earlier in this project (this is the same reasoning that led to skipping tax complexity), **I'd recommend weighted average for now** and treat true lot tracking as a possible v2 feature if you find you actually need it.

Everything below assumes weighted average.

### 1.3 Backend changes

**File: `PurchaseService.java`** — inside `createPurchase()`, in the loop that processes each item and increases stock (`processStockIncrease` or wherever `Product.stockQuantity` gets incremented), add the weighted-average recalculation **before** the stock quantity is updated (you need the *old* quantity as the weight):

```java
BigDecimal oldCostPrice = product.getCostPrice() != null ? product.getCostPrice() : BigDecimal.ZERO;
int oldStockQty = product.getStockQuantity();
int purchasedQty = item.getQuantity();
BigDecimal purchasedUnitCost = item.getUnitCost();

BigDecimal totalOldValue = oldCostPrice.multiply(BigDecimal.valueOf(oldStockQty));
BigDecimal totalNewValue = purchasedUnitCost.multiply(BigDecimal.valueOf(purchasedQty));
int totalQty = oldStockQty + purchasedQty;

BigDecimal newWeightedCost = totalQty > 0
    ? totalOldValue.add(totalNewValue).divide(BigDecimal.valueOf(totalQty), 2, RoundingMode.HALF_UP)
    : purchasedUnitCost;

product.setCostPrice(newWeightedCost);
// ... then proceed with the existing stock quantity increase, as before
```

This means `Product.costPrice` is now **always** an accurate, automatically-maintained blended cost, updated every time you record a purchase. No manual entry needed going forward (the manual cost-price field on the product form can stay as a way to set an *initial* cost for a brand-new product with no purchase history yet).

### 1.4 Historical accuracy for past sales — snapshot cost at time of sale

If `Product.costPrice` keeps changing over time, using *today's* cost price to calculate profit on a sale from three months ago would be wrong. Fix: record the cost at the moment of sale.

**File: `bms_schema.sql`** — add to `sale_items`:
```sql
ALTER TABLE sale_items ADD COLUMN cost_price_at_sale DECIMAL(10,2) NULL;
```

**File: `SaleItem.java`** — add the matching field + getter/setter: `private BigDecimal costPriceAtSale;`

**File: `SaleService.java`** — inside `createSale()`'s item-building loop, alongside the existing pricing calculation, add:
```java
item.setCostPriceAtSale(product.getCostPrice());
```
This is a pure addition — captured once, at sale time, never touched again. It's what makes historical profit reports accurate regardless of how cost prices drift afterward.

### 1.5 What "profit" means going forward

- **Per sale item:** `profit = (unitPrice - costPriceAtSale) * quantity`
- **Per sale:** sum of all its items' profit
- **Over a period:** sum of all non-voided, non-refunded sale items' profit in that range — this is your Cost of Goods Sold (COGS) calculation, needed for both the Analytics page (§2) and the Accounting page (§4).

### 1.6 Acceptance criteria
- Creating a purchase updates `Product.costPrice` to the correct weighted average, verifiable by hand: buy 10 @ $10 (cost → $10), then buy 20 @ $12 (cost → $(10×10 + 20×12)/30 = $11.33).
- A sale made today captures today's cost; if you later record a new purchase changing the cost, that old sale's recorded profit doesn't change.
- Voided sales are excluded from profit totals (they already correctly restore stock — make sure profit reporting also excludes them, not just inventory).

---

## 2. Analytics Page (for Admin)

### 2.1 Goal
One page giving the shop owner a real picture of the business: what's selling, what's making money, trends over time.

### 2.2 Backend — new endpoints (extend `ReportController.java`)

| Endpoint | Returns |
|---|---|
| `GET /api/reports/top-products?period=WEEK\|MONTH\|YEAR&limit=10` | Ranked list: product name, quantity sold, revenue, profit, for the given period |
| `GET /api/reports/top-categories?period=WEEK\|MONTH\|YEAR` | Same, grouped by category instead of product |
| `GET /api/reports/profit-summary?startDate=&endDate=` | `{ revenue, cogs, grossProfit, grossMarginPercent }` for an arbitrary date range |
| `GET /api/reports/profit-trend?period=WEEK\|MONTH\|YEAR&points=12` | Time series of `{ periodLabel, revenue, cogs, grossProfit }` for charting — e.g. last 12 weeks, last 12 months, or last 5 years, depending on `period` |

All of these: `@PreAuthorize("hasRole('ADMIN')")` only — this is ownership-level financial visibility, not something Managers automatically need (your call if you want to extend it to Managers too, but default to the tighter restriction and loosen later if needed, not the other way around).

Implementation note: `top-products`/`top-categories` can extend the logic already partially built for the existing `top-selling-products` report — the new pieces are the `period` parameter (translate WEEK/MONTH/YEAR into a `startDate`/`endDate` range server-side) and adding profit (using `costPriceAtSale` from §1.4) alongside the existing revenue/quantity numbers.

### 2.3 Frontend — new page `frontend/src/pages/Analytics.jsx`

Route it Admin-only (`allowedRoles={['ADMIN']}`, matching the router-level RBAC pattern established for Users/Settings). Layout suggestion:
- Period toggle at the top (Week / Month / Year) driving all the charts below via a shared `period` state.
- Top Products: horizontal bar chart (`recharts` `BarChart`) — product name vs. revenue, top 10.
- Top Categories: pie or donut chart — revenue share by category.
- Profit Trend: line chart with two lines (Revenue, Gross Profit) over the trend endpoint's time series.
- Summary cards at the top: Total Revenue, Total COGS, Gross Profit, Gross Margin % — pulled from `profit-summary` for the currently selected period.

Add `reportService.getTopProducts(period)`, `getTopCategories(period)`, `getProfitSummary(startDate, endDate)`, `getProfitTrend(period)` to `services.js`, matching the existing `reportService` function shapes.

Add "Analytics" to the sidebar (Admin-only, same pattern as other role-gated nav items in `DashboardLayout.jsx`).

### 2.4 Acceptance criteria
- Switching the period toggle updates every chart on the page.
- Numbers reconcile: Total Revenue on the summary card should match the sum of the Top Products chart's revenue values for the same period.
- A period with zero sales shows empty-state charts, not a crash (apply the same "return zero-filled data, not gaps or nulls" principle from Document 1's dashboard chart).

---

## 3. Refund From Receipt (Admin/Manager only)

### 3.1 Design decision — this is not the same thing as Void

Your system already has `SaleService.voidSale()`, which cancels an entire sale and restores all stock — but that's meant for immediate mistakes (wrong sale, cashier error, caught right away). A **refund** is a different, later action: a customer returns some or all items after the fact, possibly days later, possibly only some of what they bought. Keep both — don't try to merge them. A voided sale shouldn't also be refundable (it's already fully reversed); a refund should only apply to a completed, non-voided sale.

### 3.2 Data model — new entity

**New table `refunds`:**
```sql
CREATE TABLE refunds (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sale_id BIGINT NOT NULL,
    refunded_by BIGINT NOT NULL,
    refund_date DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reason TEXT NOT NULL,
    total_refund_amount DECIMAL(10,2) NOT NULL,
    FOREIGN KEY (sale_id) REFERENCES sales(id),
    FOREIGN KEY (refunded_by) REFERENCES users(id)
);

CREATE TABLE refund_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    refund_id BIGINT NOT NULL,
    sale_item_id BIGINT NOT NULL,
    quantity_refunded INT NOT NULL,
    refund_amount DECIMAL(10,2) NOT NULL,
    FOREIGN KEY (refund_id) REFERENCES refunds(id) ON DELETE CASCADE,
    FOREIGN KEY (sale_item_id) REFERENCES sale_items(id)
);
```
Itemized, not just a lump sum — this lets a customer return only some of what they bought, and gives you a real record of *what* came back, which you'll want for both inventory accuracy and the Accounting page later. Add matching `Refund.java`/`RefundItem.java` entities and repositories, following the same patterns as `Purchase`/`PurchaseItem`.

**On `SaleItem`**, add a running tally so you can't refund more than was actually bought:
```sql
ALTER TABLE sale_items ADD COLUMN quantity_refunded INT NOT NULL DEFAULT 0;
```

### 3.3 Backend — new endpoint

```java
@PostMapping("/{saleId}/refund")
@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
public ResponseEntity<ApiResponse<RefundResponse>> refundSale(
        @PathVariable Long saleId,
        @Valid @RequestBody RefundRequest request) {
    return ResponseEntity.ok(new ApiResponse<>(true, "Refund processed", saleService.processRefund(saleId, request, currentUserId)));
}
```
`RefundRequest`: `{ reason: String, items: [{ saleItemId: Long, quantity: Integer }] }`

**`SaleService.processRefund()` business rules — get these right, they matter:**
1. Sale must exist, must not be voided (`if (sale.getIsVoided()) throw new BusinessException("Cannot refund a voided sale")`).
2. For each requested item: look up the `SaleItem`, confirm `quantity <= (saleItem.getQuantity() - saleItem.getQuantityRefunded())` — you can't refund more than was bought minus what's already been refunded before. Reject with a clear message if exceeded.
3. Compute `refundAmount` per item using the item's **original sale price** (`saleItem.getUnitPrice() * quantity`), not current pricing — a refund should return what the customer actually paid, unaffected by any price changes since.
4. Restore stock — same pattern as `voidSale()`: increment `Product.stockQuantity`, write a `StockMovement` (type `ADJUSTMENT_IN` or a new dedicated `RETURN` type if you want it distinguishable in stock history — check what `voidSale` currently uses and stay consistent).
5. Update `SaleItem.quantityRefunded += quantity` for each affected item.
6. Save the `Refund` + `RefundItem` rows as the audit record.
7. Log via `auditLogService`, same as every other sensitive action in this codebase.

### 3.4 Frontend

On the receipt view (`ReceiptPreview.jsx` or wherever the printed/downloadable receipt is shown from), add a **"Refund"** button, visible only to Admin/Manager (`isManager()` check, matching the pattern already used elsewhere), that opens a dialog:
- List each line item from the sale with a quantity selector (max = original quantity minus already-refunded quantity — disable/hide fully-refunded items).
- A required reason text field.
- Running total of the refund amount as quantities are selected.
- Submit → calls the new endpoint → show a notification (success/error, using the notification system) → refresh the receipt view to show updated refunded-quantity indicators.

On the Sales list/history page, show a visual indicator (a small "Partially Refunded" / "Refunded" chip) for sales that have any `quantityRefunded > 0` on their items — useful for anyone scanning sale history later.

### 3.5 Acceptance criteria
- Refunding 2 of 5 units of an item restores exactly 2 units of stock, not 5.
- Attempting to refund more than was purchased (accounting for prior refunds) is rejected with a clear error, not silently clamped or allowed to go negative.
- A voided sale cannot be refunded.
- Refund amount is based on the original sale price, not current product pricing.
- Every refund is attributed to the real logged-in user (reuse the pattern already fixed for sales/purchases/settings — don't reintroduce a hardcoded user ID here).

---

## 4. Business Accounting Page (Admin)

### 4.1 Goal
One page answering "how did this business do this month" — income, expenses, and what's left over.

### 4.2 Data model — new entity for expenses

**New table `expenses`:**
```sql
CREATE TABLE expenses (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    category VARCHAR(50) NOT NULL,
    description VARCHAR(255),
    amount DECIMAL(10,2) NOT NULL,
    expense_date DATE NOT NULL,
    created_by BIGINT NOT NULL,
    receipt_image LONGBLOB NULL,
    receipt_image_type VARCHAR(10) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at DATETIME NULL,
    FOREIGN KEY (created_by) REFERENCES users(id)
);
```
`category` as a Java enum: `RENT, UTILITIES, TRAVEL, TAXES, SALARY, SUPPLIES, MAINTENANCE, MARKETING, OTHER` — again, a starting point, trivially extensible since it's just an enum plus a `VARCHAR` column. `receipt_image` lets you attach a photo of a physical receipt for a travel cost, utility bill, etc. — optional, reuse the same authenticated-image-blob pattern already established twice now (product images, shop logo).

New `Expense.java` entity, `ExpenseRepository`, `ExpenseService`, `ExpenseController` — standard CRUD (`GET` list with filtering by category/date range, `GET /{id}`, `POST`, `PUT /{id}`, `DELETE /{id}`), all `ADMIN`/`MANAGER` (your call on whether Cashiers should ever log an expense like a delivery/travel cost — if yes, restrict them to only the `TRAVEL` category and only creating, not editing/deleting others' entries; if no, keep it Admin/Manager only like everything else financial in this document).

### 4.3 Backend — accounting summary endpoint

```java
@GetMapping("/accounting-summary")
@PreAuthorize("hasRole('ADMIN')")
public ResponseEntity<ApiResponse<AccountingSummaryResponse>> getAccountingSummary(
        @RequestParam int year, @RequestParam int month) {
    return ResponseEntity.ok(new ApiResponse<>(true, "...", reportService.getAccountingSummary(year, month)));
}
```
`AccountingSummaryResponse`:
```java
{
  totalIncome: BigDecimal,       // sum of non-voided sale totals in the month
  totalCogs: BigDecimal,         // from §1.5's profit calculation
  grossProfit: BigDecimal,       // totalIncome - totalCogs
  totalExpenses: BigDecimal,     // sum of expenses in the month
  netProfit: BigDecimal,         // grossProfit - totalExpenses
  expensesByCategory: [ { category: String, amount: BigDecimal } ],
  totalRefunds: BigDecimal       // sum of refund amounts in the month, shown separately so it's visible, not just netted silently into income
}
```
Implementation: reuses the profit-calculation logic from §1.5/§2.2 for the income/COGS side, plus a straightforward `SUM(amount) GROUP BY category WHERE expense_date BETWEEN ...` for the expense side.

### 4.4 Frontend — new page `frontend/src/pages/Accounting.jsx`

Admin-only route. Layout:
- Month/year picker at the top (default to current month).
- Summary cards: Income, COGS, Gross Profit, Total Expenses, **Net Profit** (the headline number — make it visually prominent, larger/bolder than the rest).
- Expense breakdown: pie chart or simple bar list by category.
- An expense log table below — list of this month's expenses with add/edit/delete (Admin/Manager per §4.2's decision), each row showing category, description, amount, date, and a receipt-photo thumbnail if attached (reuse the same image-thumbnail component pattern from Document 1's category-image work).
- An "Add Expense" button opening a simple form dialog: category dropdown, description, amount, date, optional receipt photo upload.

Add `expenseService` to `services.js` (standard CRUD shape) and `reportService.getAccountingSummary(year, month)`.

### 4.5 Acceptance criteria
- Net Profit for a month correctly equals Income − COGS − Expenses, verifiable by hand against a small set of test sales/purchases/expenses.
- Adding, editing, and deleting an expense immediately updates the summary numbers for the affected month.
- Refunds in a given month are visible on this page (as a separate line, per §4.3) rather than silently disappearing into a netted income number — you want to be able to see "we did $X in sales but $Y of that came back."
- A month with no data (before the shop started using the app, or a slow month) shows zeros cleanly, not an error.

---

## Suggested order for this document

1. §1 (Purchase Cost Tracking) — do this **first**, everything else in this document depends on `costPriceAtSale` existing and being accurate.
2. §3 (Refunds) — can be built in parallel with §2, doesn't depend on it.
3. §2 (Analytics Page) — depends on §1.
4. §4 (Accounting Page) — depends on §1 and benefits from §3 (the refund-visibility line item) — do this last.

This is a genuinely large chunk of work — new entities, new financial logic, three new pages. Worth testing §1's weighted-average math by hand against a couple of real purchase scenarios before building anything on top of it, since every profit number in §2 and §4 inherits whatever `costPriceAtSale` actually contains.
