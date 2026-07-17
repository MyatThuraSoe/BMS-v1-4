# BMS-v1-4 — Requirements Document 1 of 2
## Foundation & UX Improvements
Covers: tax default (already done), Shop Information, receipt branding, category filtering, dashboard chart.

Grounded in the actual current codebase (`BMS-v1-4`) — field names, endpoints, and files referenced below are real, not illustrative.


---

## 1. Shop Information

### 1.1 Goal
One place storing your shop's identity — name, type, address, contact info, logo — used across the app (and by Document 2's receipt/voucher branding).

### 1.2 Data model — new entity

**New table: `shop_info`** (deliberately a singleton — the app only ever has one shop, one row):
```sql
CREATE TABLE shop_info (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_name VARCHAR(255) NOT NULL,
    shop_type VARCHAR(50) NOT NULL,
    address TEXT,
    phone VARCHAR(20),
    email VARCHAR(100),
    logo_data LONGBLOB NULL,
    logo_type VARCHAR(10) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

**New entity: `ShopInfo.java`** — mirror the pattern already used for `Product`'s image fields (`imageData`/`imageType`, from the recent single-image redesign) for the logo, plus:
- `shopType` as a Java `enum ShopType { MINI_MART, GROCERY, PHARMACY, FURNITURE_SHOP, ELECTRONICS, CLOTHING, RESTAURANT, OTHER }` stored as `VARCHAR` — pick whatever set of categories actually matches your shop; this list is a starting point, easy to extend later since it's just an enum.

### 1.3 Backend endpoints

Since there's only ever one row, skip full CRUD — treat it as a single resource:

| Method | Path | Role | Behavior |
|---|---|---|---|
| `GET` | `/api/shop-info` | ADMIN, MANAGER, CASHIER (needed by POS/receipts for every role) | Returns the one row. If none exists yet, return sensible empty defaults (don't 404 — every other feature that reads this should never have to special-case "doesn't exist yet") |
| `PUT` | `/api/shop-info` | ADMIN only | Upserts — creates the row if it doesn't exist, updates it if it does. JSON body for text fields |
| `POST` | `/api/shop-info/logo` | ADMIN only | Multipart upload, same authenticated-blob pattern as the product image redesign — replaces any existing logo |
| `GET` | `/api/shop-info/logo` | ADMIN, MANAGER, CASHIER | Returns raw logo bytes, same pattern as `GET /api/products/{id}/image` |
| `DELETE` | `/api/shop-info/logo` | ADMIN only | Removes the logo |

`ShopInfoResponse` DTO should include a `hasLogo` boolean (same pattern as `Product.hasImage`) so the frontend knows whether to attempt fetching the logo at all.

### 1.4 Frontend

New page: `frontend/src/pages/ShopInfo.jsx`, reachable from the sidebar under Settings, **Admin only** (add to `DashboardLayout.jsx`'s role-filtered menu and to the router's role guard, matching the pattern used for the Users/Settings/Audit Logs routes).

Form fields: Shop Name (text, required), Shop Type (dropdown, the enum values above), Address (multiline text), Phone, Email, and a logo uploader — **reuse the `ProductImage`-style component pattern** (authenticated blob fetch → `URL.createObjectURL`) built for product images; a `ShopLogo.jsx` component following the identical shape (fetch `/api/shop-info/logo`, show placeholder icon if `hasLogo` is false) is the cleanest way to do this without duplicating the fetch/cleanup logic.

Add `shopInfoService` to `services.js`: `get()`, `update(data)`, `uploadLogo(file)`, `deleteLogo()` — same shapes as the existing `productService` image functions.

### 1.5 Acceptance criteria
- Admin can set shop name, type, address, contact info, and upload a logo.
- Manager/Cashier can view shop info (read-only) if you ever expose it to them, but cannot edit.
- Uploading a new logo replaces the old one (not additive — same "always upsert" rule as the single product image work).
- If no shop info has been set yet, the app doesn't break anywhere that reads it (receipts, etc. from Document 2) — falls back to blank/placeholder gracefully.

---

## 2. Shop Logo & Info on Receipt/Voucher

### 2.1 Goal
Every receipt (print view, PDF, PNG) shows your actual shop identity instead of the current hardcoded "BMS v1" placeholder header.

### 2.2 Backend changes

**File: `ReceiptService.java`** — wherever the receipt HTML/PDF/PNG is being assembled (the header section), replace the hardcoded shop name with a live lookup:
- Inject `ShopInfoService` (or `ShopInfoRepository`) into `ReceiptService`.
- Fetch the current `ShopInfo` row (or defaults if none exists) at the top of each receipt-generation method.
- Replace whatever currently renders as the receipt's title/header with `shopInfo.getShopName()`, and add `shopInfo.getAddress()` / `shopInfo.getPhone()` as smaller subtext beneath it — same place the current "BMS v1" text lives.

**For the logo specifically**, each output format needs a different embedding approach:
- **HTML/print view:** embed the logo as a base64 data URI directly in an `<img>` tag (`data:image/{type};base64,{encoded bytes}`) — simplest, no extra request needed since it's inline.
- **PDF (OpenPDF):** use OpenPDF's `Image.getInstance(byte[])` to load the logo bytes directly and add it to the document near the top, above the existing text header.
- **PNG (Graphics2D/BufferedImage):** use `ImageIO.read()` on the logo bytes to get a `BufferedImage`, then `Graphics2D.drawImage(...)` it onto the receipt canvas near the top, same position as the PDF version for visual consistency.

In all three cases: **check `shopInfo.getLogoData() != null` first** and skip the image entirely if there's no logo set — don't let a missing logo break receipt generation for shops that haven't uploaded one yet.

### 2.3 Frontend changes

`ReceiptPreview.jsx` (or wherever the in-app receipt dialog lives) — if it renders its own HTML preview separately from calling the backend's print endpoint, apply the same shop-name/logo substitution there too, using the `shopInfoService.get()` call and the same `ShopLogo` component from §1.4, so the on-screen preview matches what actually prints/downloads.

### 2.4 Acceptance criteria
- Printed receipt, downloaded PDF, and downloaded PNG all show the shop name and logo (when set).
- A shop with no logo uploaded still generates clean receipts — no broken image icon, no error.
- Changing the shop name/logo in Shop Info immediately reflects on the next receipt generated (no caching issue — always read fresh).

---

## 3. View Products By Category (POS + Products Page)

### 3.1 Goal
Both the POS screen and the Products management page let you filter the visible product list down to one category at a time.

### 3.2 Backend change (Products page only — POS can filter client-side, see below)

**File: `ProductController.java`**, the list endpoint (`GET /api/products`) — add an optional query parameter:
```java
@GetMapping
public ResponseEntity<ApiResponse<Page<ProductResponse>>> getAllProducts(
        @RequestParam(required = false) Long categoryId,
        Pageable pageable) {
    return ResponseEntity.ok(new ApiResponse<>(true, "...", productService.getAllProducts(categoryId, pageable)));
}
```
**File: `ProductService.java`** — update `getAllProducts` to branch: if `categoryId` is null, existing behavior; if provided, filter. **File: `ProductRepository.java`** — add:
```java
Page<Product> findByCategoryIdAndIsActiveTrue(Long categoryId, Pageable pageable);
```
(matching whatever the existing "get all active products" query already looks like — check the current method name/pattern in `ProductRepository` and mirror it exactly rather than introducing an inconsistent naming style).

### 3.3 Frontend — `Products.jsx`

Add a category filter control above the table — either a `<Select>` dropdown ("All Categories" + each category) or MUI `<Tabs>` if you want a more visual switcher. Wire it into the existing `useQuery` call's `queryKey` (so changing category triggers a refetch) and pass `categoryId` through to `productService.getAll(...)`. Fetch the category list via the existing `categoryService.getAll()` (remember: unwrap `.data.content`, per the earlier Categories bug fix — this is exactly the kind of place that bug likes to resurface).

### 3.4 Frontend — `POS.jsx`

POS already loads up to 100 products into memory and filters them client-side by search text (`filteredProducts`). Category filtering fits the same pattern — no backend change needed here:
- Add category "chips" or a small horizontal tab bar above the product grid (fetch categories via `categoryService.getAll()` once on mount).
- Add `const [selectedCategory, setSelectedCategory] = useState(null);`
- Extend the existing `filteredProducts` derivation to also check `(!selectedCategory || p.categoryId === selectedCategory)`, combined with the existing search-text condition.
- Include an "All" option that clears the filter.

### 3.5 Acceptance criteria
- Selecting a category on the Products page shows only products in that category, with pagination still working correctly within the filtered set.
- Selecting a category on POS instantly filters the visible tiles (no network round-trip, since it's the same 100-product client-side dataset already loaded).
- "All Categories" / clearing the filter restores the full list on both pages.

---

## 4. Dashboard — Sales Per Week Chart

### 4.1 Goal
Dashboard shows a visual trend of recent sales instead of just static numbers.

### 4.2 Backend — new endpoint

**File: `ReportController.java`** — add:
```java
@GetMapping("/sales-trend")
@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
public ResponseEntity<ApiResponse<List<DailySalesTrendDto>>> getSalesTrend(
        @RequestParam(defaultValue = "7") int days) {
    return ResponseEntity.ok(new ApiResponse<>(true, "Sales trend retrieved", reportService.getSalesTrend(days)));
}
```
**New DTO** `dto/response/DailySalesTrendDto.java`: `date` (LocalDate), `totalSales` (BigDecimal), `transactionCount` (int).

**File: `ReportService.java`** (or wherever report logic currently lives) — add `getSalesTrend(int days)`: query non-voided `Sale`s where `saleDate` falls within `[today - days, today]`, group by calendar date, sum `totalAmount` and count rows per date. If a day has zero sales, still include it in the response with `totalSales = 0` (a chart with gaps looks broken — always return a full contiguous date range).

### 4.3 Frontend — `Dashboard.jsx`

Add a `useQuery` for `reportService.getSalesTrend(7)` (add the matching function to `reportService` in `services.js`). Render with `recharts` (already a project dependency):
```jsx
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';

<ResponsiveContainer width="100%" height={250}>
  <LineChart data={salesTrend}>
    <CartesianGrid strokeDasharray="3 3" />
    <XAxis dataKey="date" />
    <YAxis />
    <Tooltip formatter={(value) => formatCurrency(value)} />
    <Line type="monotone" dataKey="totalSales" stroke="#1976d2" strokeWidth={2} />
  </LineChart>
</ResponsiveContainer>
```
Place this in the currently-placeholder "Quick Actions"/"Recent Activity" area of `Dashboard.jsx` (from the earlier frontend audit — that page has real gaps already flagged; this is a good opportunity to fill them in the same pass).

### 4.4 Acceptance criteria
- Dashboard shows a 7-day line/bar chart of daily sales totals for Admin and Manager (Cashiers likely don't need this — match the existing report RBAC pattern, Admin/Manager only).
- Days with no sales show as zero, not a gap in the chart.
- Chart updates when new sales are made (standard React Query refetch behavior — no special work needed beyond normal query invalidation, but confirm the dashboard's query isn't stale-cached indefinitely).

---

## Suggested order for this document

1. Shop Information (§1) — foundational, needed before §2.
2. Receipt branding (§2) — quick once §1 exists.
3. Category filtering (§3) — fully independent, can be done in parallel with 1/2.
4. Dashboard chart (§4) — fully independent, can be done in parallel with anything else.

See **Document 2** for Analytics, Purchase Cost Tracking / Profit, Refunds, and the Accounting page — those depend on more significant new business logic and are written up separately.
