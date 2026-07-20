# BMS-v1-4 — Requirements Document 4 of 4
## Back-Office Operations
Covers: cash drawer / shift reconciliation, CSV import/export, low-stock reorder suggestions.

---

## 1. Cash Drawer / Shift Reconciliation

### 1.1 Goal
At the start of a shift, a cashier declares how much cash is in the drawer. At the end, they count it again — the system tells them what it *should* contain based on sales made during that shift, and flags any variance. This is standard in real commercial POS and is a genuine trust-and-accountability feature for an owner who isn't standing at the register all day.

### 1.2 Data model — new entity

**New table `cash_shifts`:**
```sql
CREATE TABLE cash_shifts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    cashier_id BIGINT NOT NULL,
    opening_amount DECIMAL(10,2) NOT NULL,
    opening_time DATETIME NOT NULL,
    closing_amount DECIMAL(10,2) NULL,
    closing_time DATETIME NULL,
    expected_amount DECIMAL(10,2) NULL,
    variance DECIMAL(10,2) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',   -- OPEN, CLOSED
    notes TEXT,
    FOREIGN KEY (cashier_id) REFERENCES users(id)
);
```
`expected_amount` and `variance` are computed and stored at close time (not just calculated on the fly), so the historical record is fixed even if something about how sales are queried changes later — you want an immutable snapshot of "here's what happened on this shift," not a number that could shift retroactively.

**Link sales to a shift** — add to `sales`:
```sql
ALTER TABLE sales ADD COLUMN cash_shift_id BIGINT NULL;
ALTER TABLE sales ADD FOREIGN KEY (cash_shift_id) REFERENCES cash_shifts(id);
```
Every cash sale created while a cashier has an open shift gets tagged with that shift's ID — this is what makes the "expected amount" calculation possible at close time.

### 1.3 Backend

**`SaleService.createSale()`** — when creating a sale with `paymentMethod == CASH`, look up whether the current cashier has an `OPEN` shift and attach `sale.setCashShiftId(...)` if so. (Credit sales from Document 3 don't affect the cash drawer at all — only actual cash in hand matters here, so only tag cash-method sales.)

**New `CashShiftController` / `CashShiftService`:**

| Endpoint | Role | Behavior |
|---|---|---|
| `POST /api/shifts/open` | ADMIN, MANAGER, CASHIER | Body: `{ openingAmount }`. Reject if the cashier already has an open shift (`if (shiftRepository.existsByCashierIdAndStatus(userId, "OPEN")) throw ...`) — one open shift per cashier at a time. |
| `GET /api/shifts/current` | ADMIN, MANAGER, CASHIER | Returns the calling user's currently open shift, or `null` if none — the frontend needs this to know whether to show "Open Shift" or "Close Shift" |
| `POST /api/shifts/{id}/close` | ADMIN, MANAGER, CASHIER (only the shift's own cashier, or a Manager/Admin closing on someone's behalf) | Body: `{ closingAmount, notes }` |
| `GET /api/shifts` | ADMIN, MANAGER | Paginated list, filterable by cashier and date range — for reviewing shift history |
| `GET /api/shifts/{id}` | ADMIN, MANAGER, or the shift's own cashier | Full detail including the list of sales made during it |

**Close-shift calculation** — this is the core logic, get it right:
```java
public CashShiftResponse closeShift(Long shiftId, CloseShiftRequest request, Long userId) {
    CashShift shift = cashShiftRepository.findById(shiftId)
            .orElseThrow(() -> new ResourceNotFoundException("Shift not found"));
    if (!"OPEN".equals(shift.getStatus())) {
        throw new BusinessException("Shift is already closed");
    }

    BigDecimal cashSalesTotal = saleRepository.sumCashSalesByShiftId(shiftId); // SUM(totalAmount) WHERE cash_shift_id = :shiftId AND payment_method = 'CASH' AND is_voided = false
    BigDecimal expectedAmount = shift.getOpeningAmount().add(cashSalesTotal);
    BigDecimal variance = request.getClosingAmount().subtract(expectedAmount);

    shift.setClosingAmount(request.getClosingAmount());
    shift.setClosingTime(LocalDateTime.now());
    shift.setExpectedAmount(expectedAmount);
    shift.setVariance(variance);
    shift.setStatus("CLOSED");
    shift.setNotes(request.getNotes());

    cashShiftRepository.save(shift);
    auditLogService.logAction(userId, "SHIFT_CLOSE",
        "Shift closed with variance: " + variance, "CashShift", shiftId, null, null);
    return convertToResponse(shift);
}
```
**Important edge case:** if a voided sale happens *during* an open shift, make sure `sumCashSalesByShiftId` excludes voided sales (it should sum only real completed cash revenue) — otherwise a voided sale would incorrectly inflate the "expected" cash amount and create a false variance.

**RBAC consideration:** should a Cashier be blocked from creating sales if they don't have an open shift? For v1, I'd recommend **not** enforcing this strictly (don't want a technical restriction blocking a real sale if someone forgets to open a shift) — instead, just don't tag untagged sales to any shift, and let the reconciliation report show "N sales made outside of a tracked shift" as a visible warning to the owner, rather than a hard block.

### 1.4 Frontend

**New page `frontend/src/pages/CashShift.jsx`** — accessible to all roles (a Cashier needs this daily), likely linked prominently from the POS page or the top nav bar rather than buried in a settings menu, since it's a start/end-of-day action:
- If no shift is open: a simple "Start Shift" card with an Opening Amount input.
- If a shift is open: shows opening amount, opening time, running count of cash sales so far this shift (nice-to-have — a live "expected so far" number), and a "Close Shift" button opening a dialog for the actual cash count, which then shows the calculated variance immediately (color-coded: green if $0, small variance in a neutral color, larger variance flagged in red/orange with a prompt to add notes explaining it).

**`Products.jsx` sidebar / `DashboardLayout.jsx`** — add a small persistent indicator (e.g., in the top app bar) showing "Shift: Open since 9:00 AM" for cashiers, so it's always visible they're mid-shift.

**New page or tab for Admin/Manager: shift history** — a list of past shifts (all cashiers), filterable by date/cashier, each row showing opening/closing amounts and variance, so an owner can spot patterns (a specific cashier consistently short, for instance) — this is really the point of building the feature at all.

### 1.5 Acceptance criteria
- Opening a shift with $100, then making $250 in cash sales, then counting $350 at close produces a variance of exactly $0.
- A voided sale during the shift doesn't affect the expected amount.
- A cashier can't open a second shift while one is already open.
- Shift history is visible to Admin/Manager for every cashier, not just self-service for the cashier who ran it.

---

## 2. CSV Import/Export

### 2.1 Goal
Bulk-import products from a spreadsheet (critical for onboarding a shop that already has an existing product list elsewhere), and export data for backup or handing to an accountant.

### 2.2 Backend — add a CSV library

Add `com.opencsv:opencsv` to `pom.xml` (well-established, simple API, handles quoting/escaping correctly — don't hand-roll CSV parsing with `String.split(",")`, it breaks the moment a product name or description contains a comma).

### 2.3 Product import

**New endpoint:**
```java
@PostMapping("/import")
@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
public ResponseEntity<ApiResponse<ImportResultDto>> importProducts(@RequestParam("file") MultipartFile file) {
    return ResponseEntity.ok(new ApiResponse<>(true, "Import completed", productService.importProductsFromCsv(file)));
}
```

**Expected CSV columns** (document this clearly for the shop owner — also provide a downloadable template, see §2.5): `sku, name, description, categoryName, unitPrice, costPrice, stockQuantity, minStockLevel, barcode`.

**`ProductService.importProductsFromCsv()` — the business rules that matter:**
- Process **row by row, not all-or-nothing** — one bad row (missing required field, invalid number, unknown category) shouldn't block every other valid row in the file. Collect per-row errors and continue.
- Match `categoryName` to an existing `Category` by name (case-insensitive) — if it doesn't exist, either auto-create it or reject that row with a clear message (**recommendation: auto-create**, it's friendlier for a first-time bulk import and categories are low-risk to create automatically, unlike products themselves).
- If a row's `sku` matches an existing product, **update** it rather than erroring or creating a duplicate — this makes the same import endpoint usable both for the very first bulk import and for later bulk price updates.
- Return a summary: `{ totalRows, successCount, updatedCount, createdCount, errors: [{ row: 5, message: "Unit price is required" }, ...] }` — the shop owner needs to know exactly which rows failed and why, not just "3 failed."

### 2.4 Export endpoints

Add to relevant controllers:
```java
@GetMapping("/export")
@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
public void exportProducts(HttpServletResponse response) throws IOException {
    response.setContentType("text/csv");
    response.setHeader("Content-Disposition", "attachment; filename=\"products.csv\"");
    productService.exportProductsToCsv(response.getWriter());
}
```
Do the same for **Sales** (date-range filterable — `GET /api/sales/export?startDate=&endDate=`, useful for handing a period's transactions to an accountant) and **Customers** (useful for backup, or importing into other tools). Reuse the same `opencsv` writer pattern for all three rather than three different implementations.

### 2.5 Frontend

**`Products.jsx`** — add an "Import" button (Admin/Manager) opening a dialog: file picker + a "Download Template" link (a static CSV file in `public/` with just the header row and one example row) + an "Upload" button. After upload, show the result summary clearly — a table of failed rows with their error messages is far more useful than a single toast saying "3 rows failed."

Add an "Export" button next to it, which for a `GET` returning a raw file is simplest as a plain link/anchor tag pointing at the endpoint (with the auth token handled the same way as the receipt PDF download — fetch as blob, then trigger a download, matching the pattern already established for receipts) rather than a JSON-returning axios call.

**`Sales.jsx`** (or wherever sale history lives) and **`Customers.jsx`** — same "Export" button pattern, no import needed for these (importing historical sales retroactively is a much stranger scenario with real risk of corrupting stock/audit history — don't build that).

### 2.6 Acceptance criteria
- Importing a CSV with 100 valid rows and 3 invalid rows creates/updates the 97 good ones and reports exactly which 3 failed and why.
- Re-importing the same file a second time updates existing products (matched by SKU) rather than creating duplicates.
- Exported CSVs open correctly in Excel/Google Sheets with no broken columns, even for product names/descriptions containing commas or quotes (this is exactly what `opencsv` handles correctly and hand-rolled CSV would get wrong).

---

## 3. Low-Stock Reorder Suggestions

### 3.1 Goal
Go beyond a static "stock is below X" alert — actually suggest *how much* to reorder and from *which supplier*, based on real sales velocity and purchase history you already have.

### 3.2 Backend — new endpoint building on existing data

You already have: low-stock detection (`ProductRepository.findLowStockProducts`), sales history (for velocity), and purchase history with supplier links (for "who do I usually buy this from"). This feature is mostly about combining data you already have into one useful view, not new core logic.

```java
@GetMapping("/reorder-suggestions")
@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
public ResponseEntity<ApiResponse<List<ReorderSuggestionDto>>> getReorderSuggestions() {
    return ResponseEntity.ok(new ApiResponse<>(true, "...", inventoryService.getReorderSuggestions()));
}
```

**`ReorderSuggestionDto`:**
```java
{
  productId, productName, currentStock, minStockLevel,
  averageDailySales,       // computed from sales in the last 30 days
  daysUntilStockout,       // currentStock / averageDailySales, null if averageDailySales is 0
  suggestedReorderQuantity,// see formula below
  lastSupplierId, lastSupplierName,  // from the most recent PurchaseItem for this product
  lastPurchaseUnitCost     // for a quick "here's roughly what this'll cost" estimate
}
```

**Suggested quantity formula (simple, transparent — an SME owner should be able to understand *why* a number was suggested, not treat it as a black box):**
```java
// Aim to have enough stock for 14 days of average sales, minus what's already on hand
BigDecimal targetStock = averageDailySales.multiply(BigDecimal.valueOf(14));
int suggestedQuantity = targetStock.subtract(BigDecimal.valueOf(currentStock)).max(BigDecimal.ZERO).intValue();
```
(14 days is a reasonable default assumption for a small shop's reorder cycle — make this configurable later via `SystemSetting` if you find different shops want different target windows, but hardcode a sensible default for v1 rather than over-engineering a setting nobody asked for yet.)

**Finding "last supplier":** query the most recent `PurchaseItem` for this `productId` (via its parent `Purchase.purchaseDate DESC`, limit 1) and pull the supplier from there — this is exactly the kind of "which supplier did I last buy this from" lookup that's now easy precisely because Document 2's purchase-cost-tracking work already ties `PurchaseItem` → `Product` → `Supplier` together properly.

**Handling products with no sales history yet:** if `averageDailySales` is zero (new product, or one that never sells), don't suggest a reorder quantity at all — show "insufficient sales history" rather than a nonsensical `0` or an error.

### 3.3 Frontend

**New page or a prominent section on the existing Low Stock view** — a table: Product, Current Stock, Days Until Stockout (color-coded: red if under 7 days, yellow under 14, green otherwise), Suggested Reorder Qty, Last Supplier, Last Cost, and a **"Create Purchase Order"** button per row that pre-fills a new Purchase form (`/purchases/new`) with that supplier and product/quantity already filled in — this is the payoff of the whole feature: turning a suggestion directly into action with one click, rather than the owner having to re-type everything into a blank purchase form.

### 3.4 Acceptance criteria
- A product selling 2 units/day with 5 in stock shows "~2.5 days until stockout" and a suggested reorder quantity targeting 14 days of coverage.
- A product with no sales in the last 30 days shows no misleading suggestion, just an honest "not enough data."
- Clicking "Create Purchase Order" from a suggestion correctly pre-fills the new-purchase form with the right supplier and product already selected.

---

## Suggested order

1. §2 (CSV import/export) — most independent of the three, and valuable immediately for onboarding.
2. §3 (Reorder suggestions) — depends on Document 2's purchase-cost-tracking work already being in place (it is, per the last audit), otherwise fully independent.
3. §1 (Cash shift reconciliation) — most involved (new entity, touches the sale-creation path, needs its own UI surfaced prominently for cashiers) — do this last and test the variance math carefully by hand before considering it done, the same way the weighted-average cost math was worth verifying by hand earlier.
