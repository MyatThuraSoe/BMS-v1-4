# BMS-v1-4 — Requirements Document 5 of 7
## Navigation, Filtering & Dashboard UX
Covers: self-service password change, filters across Products/Inventory/Sales, clickable dashboard widgets, sales filtering, product-detail cross-navigation to suppliers.

This is the "make the app easy to move around in" cluster — mostly frontend work, reusing data that already exists on the backend. No new entities needed for anything in this document.

---

## 1. Self-Service "Change My Password"

### 1.1 Why this instead of just patching the bug
Right now the only way to change *any* password, including your own, is the Admin-only "edit a user" screen. That's an unusual and confusing pattern — replacing it with a proper self-service option sidesteps the bug entirely and is better UX regardless of what the original root cause turns out to be.

### 1.2 Backend — new endpoint, distinct from user management

```java
@PostMapping("/api/auth/change-password")
@PreAuthorize("isAuthenticated()")  // any logged-in user, for their own account only
public ResponseEntity<ApiResponse<Void>> changeOwnPassword(
        @Valid @RequestBody ChangePasswordRequest request,
        Authentication authentication) {
    UserDetails userDetails = (UserDetails) authentication.getPrincipal();
    userService.changeOwnPassword(userDetails.getUsername(), request);
    return ResponseEntity.ok(new ApiResponse<>(true, "Password changed successfully", null));
}
```
`ChangePasswordRequest`: `{ currentPassword: String, newPassword: String }` (`@NotBlank` on both, `@Size(min=6)` on `newPassword`).

**`UserService.changeOwnPassword()` — the two rules that matter:**
```java
public void changeOwnPassword(String username, ChangePasswordRequest request) {
    User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));

    if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
        throw new BusinessException("Current password is incorrect");
    }

    user.setPassword(passwordEncoder.encode(request.getNewPassword()));
    userRepository.save(user);
    auditLogService.logAction(user.getId(), "PASSWORD_CHANGE", "User changed their own password", "User", user.getId(), null, null);
}
```
Requiring the current password prevents someone with a stolen, still-logged-in session from silently locking the real owner out — a meaningful security difference from the Admin-editing-anyone flow, which correctly doesn't need this (an Admin resetting someone else's forgotten password shouldn't need to know their old one).

### 1.3 Frontend — small addition, not a new page necessarily

Add a "Change Password" option to whatever menu shows the logged-in user's name (top-right avatar/menu in `DashboardLayout.jsx`, most likely) — opens a small dialog: Current Password, New Password, Confirm New Password (client-side check that the last two match before submitting). On success, show a confirmation toast; no need to log the user out or redirect, their existing session token is still valid.

Add `authService.changePassword(currentPassword, newPassword)` to `services.js`.

### 1.4 Acceptance criteria
- Any logged-in user (any role) can change their own password from this dialog without needing Admin access.
- Providing the wrong current password is rejected with a clear message, not a generic error.
- This doesn't touch or replace the existing Admin "edit any user" flow — that stays as-is for Admin-driven resets.

---

## 2. Filters on Products, Inventory, and Sales

### 2.1 Design decision — a shared filter bar pattern, not three different UIs

Since you want consistent "reach anywhere by clicking" navigation, build one reusable filter pattern and apply it across all three pages rather than inventing a different filter UI per page.

### 2.2 Products page (`Products.jsx`) — extend what's already there

You already have category filtering (from earlier work). Add view presets as a row of chips/tabs above the existing table:
- **All Products** (default — current behavior)
- **Most Sold** — sorted by total quantity sold (needs a backend sort option, see below)
- **Least Sold** — same data, ascending
- **Low Stock** — reuses the existing low-stock endpoint

**Backend:** `GET /api/products` already takes `sortBy`. Add a computed sort option:
```java
@GetMapping
public ResponseEntity<ApiResponse<Page<ProductResponse>>> getAllProducts(
        @RequestParam(required = false) Long categoryId,
        @RequestParam(required = false) String view,  // "most-sold", "least-sold", "low-stock", or null
        Pageable pageable) { ... }
```
For `most-sold`/`least-sold`, this needs a query joining `sale_items` grouped by product, summing `quantity` (excluding voided sales), ordered accordingly — a new repository method (`ProductRepository.findMostSoldProducts(Pageable)` / a native or JPQL query with a `GROUP BY`). For `low-stock`, just delegate to the existing endpoint's logic.

### 2.3 Inventory page (`Inventory.jsx`) — same preset pattern
Mirror the same chips (All / Low Stock / Most Sold / Least Sold) — Inventory and Products likely show overlapping data, so reuse the same backend endpoint and query params rather than duplicating logic in a separate inventory-specific query.

### 2.4 Sales page — see §4 below (bigger topic, own section).

### 2.5 Acceptance criteria
- Clicking "Low Stock" on either Products or Inventory shows the same filtered set (both pages reading from the same underlying query, not two different implementations that could drift).
- "Most Sold" / "Least Sold" correctly exclude voided sales from the ranking.
- Switching between presets updates the URL (e.g., `?view=low-stock`) so the filtered view is bookmarkable/shareable and survives a page refresh — this also directly enables §3 below (dashboard widgets linking straight to a specific filtered view).

---

## 3. Clickable Dashboard Widgets

### 3.1 Goal
Every stat card on the Dashboard becomes a shortcut to the relevant filtered page, not just a static number.

### 3.2 Frontend — `Dashboard.jsx`

The existing `StatCard` component has no `onClick` at all right now. Add one:
```jsx
const StatCard = ({ title, value, icon, color, onClick }) => (
  <Paper
    onClick={onClick}
    sx={{ p: 2, cursor: onClick ? 'pointer' : 'default', '&:hover': onClick ? { boxShadow: 4 } : {} }}
  >
    ...
  </Paper>
);
```
Wire each existing card to the matching destination, using the `?view=` query param pattern from §2.5:
- Low Stock card → `navigate('/inventory?view=low-stock')`
- Total Products card → `navigate('/products')`
- Today's Sales card → `navigate('/sales?range=today')` (see §4 for the sales-range query param)
- Total Customers card → `navigate('/customers')`

(Match this list to whatever stat cards actually exist on your current Dashboard — the pattern is the same regardless of exactly which cards you have.)

### 3.3 Acceptance criteria
- Clicking the Low Stock widget lands on Inventory already filtered to low-stock items, not a blank/unfiltered list requiring a second click.
- Every clickable widget shows a visual hover state (cursor pointer, subtle shadow) so it's discoverable as clickable before the user tries it.

---

## 4. Sales Filtering

### 4.1 Goal
Sales page gets real filters: time range (default **today**), by customer, by date, by receipt/invoice number.

### 4.2 Backend — extend the existing sales list endpoint

```java
@GetMapping
public ResponseEntity<ApiResponse<Page<SaleResponse>>> getAllSales(
        @RequestParam(defaultValue = "TODAY") String range,   // TODAY, WEEK, MONTH, YEAR, CUSTOM, ALL
        @RequestParam(required = false) String startDate,     // used when range=CUSTOM
        @RequestParam(required = false) String endDate,
        @RequestParam(required = false) Long customerId,
        @RequestParam(required = false) String invoiceNumber, // partial match, for the receipt search
        Pageable pageable) { ... }
```
`SaleService` translates `range` into an actual `[start, end]` `LocalDateTime` pair server-side (today = midnight-to-now, week = last 7 days, etc. — reuse the same period-translation logic pattern from the Analytics page's `period` parameter if that's already built, don't reinvent it a second way).

### 4.3 Frontend — `Sales.jsx`

Filter bar above the table:
- Range selector (chips or tabs: Today / This Week / This Month / This Year / All Time / Custom Range) — **default to Today**, matching what you asked for.
- Customer search/autocomplete (reuse the same `Autocomplete` component pattern already used in POS for customer selection).
- A "Search by Receipt #" text field — as the user types, filter by `invoiceNumber` (partial match, debounced).
- If `range === 'CUSTOM'`, show two date pickers.

All of these combine into one query — update the existing `useQuery`'s `queryKey`/`queryFn` to include all active filter values, same pattern as the existing category filter work.

### 4.4 Acceptance criteria
- Loading the Sales page with no interaction shows only today's sales by default.
- Searching by a partial invoice number (e.g., typing "0042") narrows results to matching invoices without needing the exact full number.
- Combining filters works together (e.g., "this customer, this month") rather than the last-applied filter silently overriding the others.

---

## 5. Product Detail — Supplier Widget & Cross-Navigation

### 5.1 Goal
Clicking into a product shows which suppliers you've bought it from, with direct action buttons — "Order more" (→ pre-filled purchase form) and "View supplier" (→ that supplier's page) — so the owner never has to manually hunt for "who do I buy this from again."

### 5.2 This needs a product detail page — check if one exists first

If there's currently no dedicated read-only product detail view (only the edit form), this is a good moment to build one — reuse the `ProductForm.jsx` pattern in **read-only mode** the same way `PurchaseForm.jsx` was converted for viewing existing purchases in earlier work, or build a lighter dedicated `ProductDetail.jsx` if you'd rather keep the edit form focused purely on editing.

### 5.3 Backend — "which suppliers has this product been bought from"

```java
@GetMapping("/api/products/{id}/suppliers")
@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
public ResponseEntity<ApiResponse<List<ProductSupplierHistoryDto>>> getProductSuppliers(@PathVariable Long id) { ... }
```
Query `PurchaseItem` joined to `Purchase`/`Supplier`, filtered by `productId`, grouped by supplier, returning: supplier name/id, number of times purchased from them, most recent unit cost, most recent purchase date. This is the same underlying data as the reorder-suggestions work discussed earlier — if you build both, share the query logic rather than writing it twice.

### 5.4 Frontend — the widget itself

On the product detail view, a small card/table: "Purchased From" — one row per supplier with the stats above, and two buttons per row:
- **"Order More"** → navigates to `/purchases/new` with `supplierId` and `productId` pre-filled (same pre-fill pattern as the reorder-suggestions "Create Purchase Order" button from earlier work).
- **"View Supplier"** → `/suppliers/{supplierId}`.

### 5.5 Acceptance criteria
- A product bought from two different suppliers shows both, each with their own most-recent-cost and last-purchase-date.
- "Order More" correctly pre-fills both the supplier and the product on the new purchase form — the user shouldn't have to re-select either.
- A brand-new product with no purchase history yet shows a clean "not purchased yet" state, not an empty/broken table.

---

## Suggested order

1. §1 (self-service password) — small, independent, resolves real user pain immediately.
2. §2 + §3 together (filters + clickable dashboard) — they share the `?view=` query param pattern, worth building as one pass.
3. §4 (sales filtering) — independent of the above, can be done in parallel.
4. §5 (product detail supplier widget) — most involved of the five, do last.

See **Document 6** for supplier price variation/profit tracking and the checkout customer flow, and **Document 7** for Bluetooth printing and backup.
