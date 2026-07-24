# BMS-v1-4 — Requirements Document 6 of 7
## Profit Tracking by Supplier & Checkout Customer Flow
Covers: supplier/time price variation for profit calculation, the "getting to know a walk-in" checkout flow.

A note on scope before diving in: you also asked for "more analytic features on products x sale x customers x suppliers" — cross-analysis across all four. I'm deliberately **not** spec'ing that out in this document. You described it yourself as complex with many routes, and you said you want Model 1 to be stable and simple — building deep cross-dimensional analytics now works against that goal. Everything in this document (accurate per-supplier cost history) is exactly the data foundation that kind of analytics would need later — do this first, prove it's accurate, and treat full cross-analytics as a deliberate Phase 2 once Model 1 is genuinely stable in real use.

---

## 1. Supplier Price Variation & Per-Batch Profit Visibility

### 1.1 What you have now vs. what you're asking for

Earlier work added **weighted average cost** — `Product.costPrice` blends into one running number every time you record a purchase (buy 10 @ $10, then 20 @ $12 → blends to $11.33). That's good for "what's my product worth right now," but it **cannot answer** "how much profit did I make specifically on the batch I bought from Factory A vs. Factory B" — the moment they blend, that distinction is gone.

What you're describing now is different and more detailed: you want to **keep each purchase batch's identity** so you can look back and see exactly which supplier/batch a given sale's profit came from. This is a real design upgrade, not a tweak — worth being honest that it's a bigger lift than the weighted-average work, but it's the right foundation if per-supplier profit visibility genuinely matters to you (which, running a real business, it likely does).

### 1.2 Design decision — lightweight batch tracking (not full FIFO consumption)

Full FIFO (each sale consumes stock from specific batches in purchase order, batch-by-batch) is the "textbook correct" approach but is a substantial rebuild of your stock-deduction logic. For Model 1, I'd recommend a lighter version that gets you real supplier-level profit visibility without that full rebuild:

- **Keep `Product.costPrice` as the weighted average** — this stays your single source of truth for "what does this cost me right now," used for day-to-day sale profit (`unitPrice - costPriceAtSale`, already built).
- **Separately, keep every `PurchaseItem` as a permanent, queryable batch record** — you already have this data (supplier, quantity, unit cost, purchase date, per batch), it just isn't surfaced as its own view yet. The new work is a **reporting layer** on top of existing data, not a change to how stock or sales work day-to-day.

This gives you: "show me every batch I've ever bought this product in, from which supplier, at what cost, and roughly what margin that implies against current selling price" — without touching how checkout or stock deduction actually works. If you later find you genuinely need true FIFO (e.g., because costs swing wildly and the weighted average feels misleading), that's a real, larger v2 project — cross that bridge only if you actually hit it.

### 1.3 Backend — new reporting endpoint

```java
@GetMapping("/api/products/{id}/cost-history")
@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
public ResponseEntity<ApiResponse<List<CostHistoryDto>>> getCostHistory(@PathVariable Long id) { ... }
```
`CostHistoryDto`: `{ purchaseDate, supplierName, quantity, unitCost, currentSellingPrice, impliedMarginPercent }` — one row per historical `PurchaseItem` for this product, newest first. `impliedMarginPercent = (currentSellingPrice - unitCost) / currentSellingPrice * 100` — "if I'd been selling at today's price against this specific batch's cost, here's the margin" — a useful comparative view even though actual recorded sale profit still uses the weighted-average `costPriceAtSale`, not this number.

**Extend the profit reporting from earlier work** (`profit-summary`/`profit-trend` on the Analytics page) with an optional supplier breakdown:
```java
@GetMapping("/api/reports/profit-by-supplier")
@PreAuthorize("hasRole('ADMIN')")
public ResponseEntity<ApiResponse<List<SupplierProfitDto>>> getProfitBySupplier(
        @RequestParam String startDate, @RequestParam String endDate) { ... }
```
This is more approximate by nature (since actual sales use blended weighted-average cost, not a specific batch), computed as: for each supplier, sum `(unitCost × quantity)` across their `PurchaseItem`s in the period as "amount supplied," alongside total revenue for products they supply in the same period — giving a rough per-supplier contribution view, clearly labeled as an estimate rather than an exact per-unit figure (be upfront about this distinction in the UI too, so it isn't mistaken for more precise than it actually is).

### 1.4 Frontend

**Product detail page** (from Document 5, §5) — add a "Cost History" tab/section showing the `cost-history` data as a simple table, sortable by date. This sits naturally next to the "Purchased From" supplier widget from Document 5 — same page, complementary information.

**Analytics page** — add a "Profit by Supplier" chart/table (Admin only), using the new endpoint, clearly labeled as an estimate (e.g., a small info icon/tooltip explaining "based on blended average cost, not exact per-batch tracking").

### 1.5 Acceptance criteria
- Buying the same product from two different suppliers at two different costs shows both purchases distinctly in Cost History, not blended together.
- The Cost History view never claims more precision than it has — the UI is honest that this is historical purchase data, not a per-sale profit breakdown.
- None of this changes how checkout, stock deduction, or the existing weighted-average `costPrice` actually behave — this is additive reporting only.

---

## 2. Checkout Customer Flow — Walk-in, Recognized, and "Getting to Know You"

### 2.1 The real-world scenario you're describing

Three different situations at checkout:
1. **True stranger** — "Walk-in Customer," no record needed.
2. **Regular you don't have in the system yet** — you recognize their face, maybe know a nickname, but they're not in your Customer database. Today, this person is indistinguishable from a stranger in your system.
3. **Known customer** — already in your Customer database, selected normally (this already works).

The gap is #2 — there's currently no lightweight way to say "I know this person a little, jot down a name/nickname, and keep them separate from the anonymous walk-in bucket" without going through the full "Add Customer" form (email, phone, address, etc. — too much friction mid-checkout with a customer standing in front of you).

### 2.2 Design decision — a "Quick Add" customer, promotable later

Add a fast, minimal-friction way to tag a sale with just a name/nickname at checkout, without requiring full customer details — and make it easy to "upgrade" that quick entry into a full customer record later when there's time (not while someone's waiting at the register).

**Backend — relax `Customer`'s required fields for a quick-add path:**

Check your current `CustomerCreateRequest` — likely requires email and phone as `@NotBlank`/unique (per earlier entity review, `Customer.email`/`Customer.phone` are both `nullable = false, unique = true`). A quick-add customer often won't have a phone or email offered up mid-transaction. Two ways to handle this:
- **Option A (simpler):** relax the DB constraints — make `email`/`phone` nullable on `Customer`, generate a placeholder unique value if blank (e.g., a synthetic value using the customer's ID) to satisfy the unique constraint without a real one. Add an `isQuickAdd` boolean flag so these are visually distinguishable in the customer list until someone fills in real details.
- **Option B:** keep the constraints strict, and quick-add customers get a required minimum of just a name — asking you to relax the unique/not-null constraints anyway, so this converges to the same schema change as Option A regardless.

Recommend Option A: `ALTER TABLE customers MODIFY email VARCHAR(255) NULL, MODIFY phone VARCHAR(255) NULL;` and drop the `UNIQUE` constraint on both (keep uniqueness only where a real value is actually provided — enforce that in application logic: `if (email != null && customerRepository.existsByEmail(email)) throw ...`, skip the check entirely when null).

### 2.3 Frontend — `POS.jsx`

Next to the existing customer `Autocomplete`, add a small "+ Quick Add" option (visible when no matching customer is found in the search, similar to how some apps offer "create new" inline within an autocomplete dropdown):
- Opens a tiny inline form: just **Name/Nickname** (required) and optionally **Phone** — nothing else.
- Submits immediately (`customerService.quickAdd({ name, phone })`), then auto-selects the newly created customer for the current sale, without leaving the POS page.

**Customers page (`Customers.jsx`)** — quick-add customers show a small "Quick Add" chip/badge so staff can spot them and know these records are incomplete. Add a "Complete Profile" action opening the normal full customer edit form pre-filled with whatever was captured, letting staff fill in the rest whenever there's time.

### 2.4 Acceptance criteria
- A cashier can tag a sale to a new, minimally-described customer in a few seconds without leaving the checkout screen or filling out a full form.
- Quick-add customers appear in the customer list, visually distinguished from fully-detailed ones, and are easy to find and complete later.
- Two quick-add customers with no phone/email provided don't collide on a uniqueness constraint (since neither has one) — confirm this specifically, it's the part most likely to break if the constraint relaxation isn't done carefully.

---

## Suggested order

1. §2 (checkout customer flow) — directly improves daily cashier experience, moderate scope.
2. §1 (cost history / supplier profit) — larger, more "nice to have" than urgent; worth confirming with yourself first that per-supplier granularity is something you'll actually look at regularly, since it's a real chunk of work for a reporting view.

See **Document 7** for Bluetooth printing and backup/Google Drive — both are more technical/infrastructure-heavy than anything in this document, worth reading with that in mind.
