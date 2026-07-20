# BMS-v1-4 — Requirements Document 3 of 4
## Checkout & Product Operations
Covers: discounts at checkout, store credit / tab sales, barcode label printing.

Grounded in the actual current codebase — field names and current state referenced below are real, verified against the repo just now.

---

## 1. Discounts at Checkout

### 1.1 Current state
`Sale.discountAmount` already exists in the schema and entity — it's just permanently hardcoded to `BigDecimal.ZERO` in `SaleService.createSale()`. This feature is mostly about actually using a field that's already there, plus the UI to set it.

### 1.2 Design decision — two kinds of discount, support both

- **Percentage discount** (e.g., "10% off") — most common for promotions/loyalty.
- **Fixed-amount discount** (e.g., "$2 off") — most common for a cashier's manual judgment call ("I'll knock off this small chip in the packaging").

Support both, applied at the **whole-sale level** for v1 (not per-line-item) — simpler to implement and reason about, and covers the overwhelming majority of real small-shop discount scenarios. Per-item discounts are a reasonable v2 addition if you find you need them later.

### 1.3 Backend changes

**`SaleCreateRequest.java`** — add:
```java
private String discountType;   // "PERCENTAGE" or "FIXED", null = no discount
private BigDecimal discountValue; // e.g. 10 (meaning 10%) or 2.00 (meaning $2 off)
```

**`SaleService.createSale()`** — after `subtotal`/`taxAmount` are computed (before `totalAmount` is finalized), add:
```java
BigDecimal discountAmount = BigDecimal.ZERO;
if (request.getDiscountType() != null && request.getDiscountValue() != null) {
    if ("PERCENTAGE".equals(request.getDiscountType())) {
        if (request.getDiscountValue().compareTo(BigDecimal.ZERO) < 0 || request.getDiscountValue().compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new BusinessException("Discount percentage must be between 0 and 100");
        }
        discountAmount = subtotal.multiply(request.getDiscountValue()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    } else if ("FIXED".equals(request.getDiscountType())) {
        discountAmount = request.getDiscountValue();
    }
    if (discountAmount.compareTo(subtotal.add(taxAmount)) > 0) {
        throw new BusinessException("Discount cannot exceed the sale total");
    }
}
sale.setDiscountAmount(discountAmount);

BigDecimal totalAmount = subtotal.add(taxAmount).subtract(discountAmount);
sale.setTotalAmount(totalAmount);
```
(This replaces whatever line currently sets `totalAmount = subtotal.add(taxAmount)` directly — the discount now factors in before the final total, same place the existing amount-paid-vs-total validation already happens.)

**Audit trail:** every sale with a non-zero discount should record *why* — add an optional `discountReason` field to the same request/entity (`TEXT`, nullable), so a Manager reviewing sales later can see "10% off — customer loyalty" rather than just a mystery discount.

**RBAC decision:** consider whether Cashiers should be able to apply discounts freely, or whether it should require Manager/Admin approval above a certain threshold. For v1, simplest is: any role that can create a sale can apply a discount, but log every discounted sale distinctly in the audit log (`SALE_CREATE_WITH_DISCOUNT` or similar) so an owner can review discount patterns later without needing a whole approval workflow yet.

### 1.4 Frontend — `POS.jsx`

Add a "Discount" section near the cart total (between the cart items and the Cash Amount field):
```jsx
<TextField select label="Discount Type" value={discountType} onChange={(e) => setDiscountType(e.target.value)} size="small">
  <MenuItem value="">No discount</MenuItem>
  <MenuItem value="PERCENTAGE">Percentage (%)</MenuItem>
  <MenuItem value="FIXED">Fixed Amount ($)</MenuItem>
</TextField>
{discountType && (
  <TextField label={discountType === 'PERCENTAGE' ? 'Discount %' : 'Discount $'} type="number" value={discountValue} onChange={(e) => setDiscountValue(e.target.value)} size="small" />
)}
```
Update the live subtotal/total calculation client-side (same place `verifiedTotals` logic already lives from the earlier checkout-fix work) to reflect the discount for immediate UX feedback, and pass `discountType`/`discountValue` through to `saleService.create()`'s payload. **Important: reuse the existing verify-cart pattern** — since discount changes the total the same way tax mismatches did before, run the discounted total through the same authoritative-total confirmation flow rather than trusting the client-computed number for the actual cash-collected validation.

### 1.5 Acceptance criteria
- A 10% discount on a $50 subtotal reduces the total by exactly $5.00, rounded correctly.
- A discount cannot exceed the sale's total (rejected with a clear error, not silently clamped).
- The receipt shows the discount applied as its own line item, not just folded silently into the total.
- Voiding or refunding a discounted sale correctly accounts for the discount (a refund should be based on what the customer *actually paid* per item, proportionally reflecting any whole-sale discount — see §2.4's note on this same issue for store credit, since both features touch the same "what did they actually pay" question).

---

## 2. Store Credit / Tab Sales

### 2.1 Goal
Regular customers can buy now and settle up later — extremely common in small local shops, and currently impossible since every sale requires full payment at checkout.

### 2.2 Data model

**Extend `Customer`** with a running balance:
```sql
ALTER TABLE customers ADD COLUMN credit_balance DECIMAL(10,2) NOT NULL DEFAULT 0.00;
ALTER TABLE customers ADD COLUMN credit_limit DECIMAL(10,2) NULL;
```
`credit_balance` = how much this customer currently owes you (positive = they owe money). `credit_limit` = optional cap on how much tab a given customer is allowed to run (null = no limit, trust them fully).

**New table `credit_payments`** — records each time a customer pays down their tab (separate from `sales`, since paying down a tab isn't a new purchase):
```sql
CREATE TABLE credit_payments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    payment_date DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    recorded_by BIGINT NOT NULL,
    notes VARCHAR(255),
    FOREIGN KEY (customer_id) REFERENCES customers(id),
    FOREIGN KEY (recorded_by) REFERENCES users(id)
);
```

### 2.3 Backend — extend the payment method, add credit-specific rules

**`Sale.PaymentMethod`** enum — currently only has `CASH`. Extend:
```java
public enum PaymentMethod {
    CASH, CREDIT
}
```
(This is also the natural place to add `CARD`/`QR` if you decide to pursue that separately — worth doing at the same time since you're touching this enum anyway, even though it's not explicitly one of the six features you picked this round.)

**`SaleService.createSale()`** — when `paymentMethod == CREDIT`:
- `amountPaid` is not required to cover the total (it's fine for it to be `0.00` — the whole point is deferred payment). Skip the existing "amount paid must cover total" check entirely for this payment method.
- Require `customerId` to be present — **credit sales cannot be walk-in** (there must be a real customer to attach the debt to). Reject with a clear error if `customerId` is null and `paymentMethod == CREDIT`.
- If the customer has a `creditLimit` set, check `customer.creditBalance + saleTotal <= customer.creditLimit`, reject if it would exceed.
- On success: `customer.setCreditBalance(customer.getCreditBalance().add(totalAmount))`, save.

**New endpoint** — recording a payment against a customer's tab:
```java
@PostMapping("/{customerId}/credit-payments")
@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'CASHIER')")
public ResponseEntity<ApiResponse<Void>> recordCreditPayment(
        @PathVariable Long customerId,
        @Valid @RequestBody CreditPaymentRequest request) {
    customerService.recordCreditPayment(customerId, request, currentUserId);
    return ResponseEntity.ok(new ApiResponse<>(true, "Payment recorded", null));
}
```
`CustomerService.recordCreditPayment()`: validate `amount > 0` and `amount <= customer.getCreditBalance()` (can't "overpay" a tab into negative — if that's a real scenario you want to support later, it needs its own design, don't allow it silently for now), subtract from `creditBalance`, save a `CreditPayment` row, save the customer.

**New endpoint** — viewing a customer's credit history/current balance (for the Customers page and for a cashier checking "how much does this person owe" before extending more credit):
```java
@GetMapping("/{customerId}/credit-history")
```
returning current balance + a paginated list of `CreditPayment` records and credit sales, combined chronologically if you want a full ledger view, or just the payments list for a simpler v1.

### 2.4 A subtlety worth getting right: refunds on credit sales

If a customer bought something on credit and later returns it, the refund shouldn't hand them cash — it should reduce what they owe. **File: `SaleService.processRefund()`** — check `sale.getPaymentMethod()`:
- If `CASH`: existing behavior (stock restored, no cash change to customer balance needed — that's a physical hand-back-cash action outside the system).
- If `CREDIT`: stock restored as normal, **and** `customer.setCreditBalance(customer.getCreditBalance().subtract(refundAmount))`, save. This keeps the tab accurate — a returned item on credit reduces what's owed, it doesn't generate cash out of nowhere.

### 2.5 Frontend

**`POS.jsx`** — add `CREDIT` as a payment method option (a toggle/select near the existing cash amount field). When selected:
- Require a customer to be selected (disable the Checkout button with a clear message if none is chosen).
- Hide/disable the Cash Amount field (or repurpose it as an optional partial payment — simpler for v1 to just hide it and treat credit sales as fully deferred).
- Show the customer's current balance and credit limit (if set) right in the cart area once a customer is selected, so the cashier can make an informed call before completing the sale.

**`Customers.jsx`** — add a "Balance" column showing `creditBalance` for each customer (highlight in a warning color if non-zero), and a "Record Payment" action opening a small dialog (amount + optional note) calling the new endpoint.

**New page or dialog** — a simple credit history view per customer (list of credit sales and payments, running balance) — could be its own route (`/customers/:id/credit-history`) or a tab within a customer detail view if you build one.

### 2.6 Acceptance criteria
- A credit sale succeeds with `amountPaid = 0`, requires a customer, and correctly increases that customer's balance by the sale total.
- A walk-in (no customer) cannot use the CREDIT payment method — rejected with a clear message.
- Recording a payment correctly reduces the balance, and cannot reduce it below zero.
- Refunding an item from a credit sale reduces the customer's balance rather than implying cash was handed back.
- A customer at their credit limit cannot have a new credit sale added that would exceed it.

---

## 3. Barcode Label Printing

### 3.1 Goal
Print physical barcode stickers for products that don't already have a manufacturer barcode — common for produce, bulk goods, and house-branded items in a small shop.

### 3.2 Design decision — generate barcodes client-side, no new backend work needed

You don't need the backend to generate barcode images — a JS library can render a scannable barcode directly in the browser from the product's existing `barcode` field (or generate one if it's blank), and the browser's print dialog handles the physical printing. This keeps this entire feature frontend-only.

**Add the dependency:** `npm install jsbarcode` (a well-established, dependency-free barcode-rendering library that draws directly to an SVG or canvas element — works well for print layouts).

### 3.3 Backend — one small addition

If a product doesn't have a barcode yet, you need a way to generate one rather than requiring the shop owner to invent a number. **`ProductService.java`** — add a helper:
```java
public String generateBarcode() {
    // Simple, collision-safe: timestamp-based, prefixed so it's visually distinct from manufacturer barcodes
    return "BMS" + System.currentTimeMillis();
}
```
Expose this via a small endpoint, or just call it automatically when saving a product with a blank barcode field in `ProductService.createProduct()`:
```java
if (product.getBarcode() == null || product.getBarcode().isBlank()) {
    product.setBarcode(generateBarcode());
}
```
(This also quietly fixes a real usability gap: today, a product with no barcode can never be found via POS's barcode search field at all.)

### 3.4 Frontend — new page `frontend/src/pages/BarcodeLabels.jsx`

Admin/Manager only. Layout:
- A product picker (reuse the existing category-filtered product list from Document 1's category-filtering work) with checkboxes to select which products need labels printed.
- A "labels per product" quantity input (e.g., print 5 copies of this product's label).
- A live preview grid showing the actual barcode stickers as they'll print (product name, price, and the rendered barcode via `jsbarcode`), sized for standard label sheets (a common size like 30-per-sheet address labels works well for small product stickers).
- A "Print" button calling `window.print()` with a print-specific CSS layout (`@media print` rules hiding everything except the label grid, sized to match your label sheet's actual dimensions).

Example of the core rendering piece:
```jsx
import Barcode from 'react-barcode'; // simpler React wrapper around jsbarcode, alternative to raw jsbarcode calls

<Barcode value={product.barcode} width={1.5} height={40} fontSize={12} />
```
(`npm install react-barcode` instead of raw `jsbarcode` if you'd rather have a ready-made React component — either works, `react-barcode` just saves you writing the canvas-ref-and-effect boilerplate yourself.)

### 3.5 Acceptance criteria
- Selecting several products and printing produces one label per requested quantity, laid out cleanly for the label sheet size you actually use (confirm the real physical label size before finalizing the CSS grid dimensions — this is the one detail worth double-checking against your actual printer/labels before calling this done).
- A newly created product with a blank barcode field automatically gets one generated, and that barcode immediately works in POS's existing barcode search.
- The printed barcode is genuinely scannable — test this with a real barcode scanner, not just visually, before considering the feature complete.

---

## Suggested order

1. §1 (Discounts) — fully independent, smallest scope, good first win.
2. §3 (Barcode labels) — fully independent of the other two, frontend-only, good to parallelize.
3. §2 (Store credit) — the most involved of the three (new entity, new business rules touching both Sale and Refund logic) — do this with the most care and test the refund interaction (§2.4) specifically, since it's the easiest part to get subtly wrong.

See **Document 4** for cash drawer reconciliation, CSV import/export, and low-stock reorder suggestions.
