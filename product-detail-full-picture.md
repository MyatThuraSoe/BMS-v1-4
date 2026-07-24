# Product Detail Page — Full Business Picture

## What already exists vs. what this document adds

`ProductDetail.jsx` already exists and already shows: basic product info, a "Purchased From" suppliers table (`getProductSuppliers`), and a buying-price "Cost History" table (`getCostHistory`). This document **extends** that existing page — it doesn't replace it.

New in this document:
1. **Selling price history** — genuinely new, nothing tracks this today. `Product.unitPrice` is just overwritten silently every time someone edits it; there's no record of what it used to be.
2. **Top customers for this product** — new.
3. **Total units sold + total profit made** — new.
4. A small extension to the existing supplier table (add total quantity/amount, not just "times purchased").

**One thing to flag before you start:** selling price history can only track changes *from the moment this feature ships forward* — there's no way to retroactively know what a product's price was six months ago if nothing was recording it then. Set that expectation now rather than someone being confused later why the history "starts empty."

---

## PART 1 — Backend

### 1. Selling Price History (new)

**Database:**
```sql
CREATE TABLE product_price_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    old_price DECIMAL(10,2) NOT NULL,
    new_price DECIMAL(10,2) NOT NULL,
    changed_by BIGINT NOT NULL,
    changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (product_id) REFERENCES products(id),
    FOREIGN KEY (changed_by) REFERENCES users(id),
    INDEX idx_price_history_product (product_id)
);
```

**New entity `ProductPriceHistory.java`** — straightforward, mirror the style of `StockMovement.java`: `id`, `@ManyToOne Product product`, `oldPrice`, `newPrice`, `@ManyToOne User changedBy`, `changedAt`.

**New `ProductPriceHistoryRepository`:**
```java
List<ProductPriceHistory> findByProductIdOrderByChangedAtDesc(Long productId);
```

**Hook into the existing update flow — `ProductService.updateProduct()`:**

Find where the method currently does something like `product.setUnitPrice(request.getUnitPrice());` and change it to check for an actual change first:
```java
BigDecimal oldPrice = product.getUnitPrice();
BigDecimal newPrice = request.getUnitPrice();

if (oldPrice.compareTo(newPrice) != 0) {
    ProductPriceHistory history = new ProductPriceHistory();
    history.setProduct(product);
    history.setOldPrice(oldPrice);
    history.setNewPrice(newPrice);
    history.setChangedBy(userRepository.findById(currentUserId).orElse(null));
    priceHistoryRepository.save(history);
}

product.setUnitPrice(newPrice);
```
(`currentUserId` — check how the rest of `ProductService` currently gets the acting user; if it doesn't have one passed in yet, this method needs a `Long userId` parameter added, threaded through from the controller the same way `SaleService`/`PurchaseService` already do it.)

**New endpoint:**
```java
@GetMapping("/{id}/price-history")
@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
public ResponseEntity<ApiResponse<List<PriceHistoryDto>>> getPriceHistory(@PathVariable Long id) {
    return ResponseEntity.ok(new ApiResponse<>(true, "...", productService.getPriceHistory(id)));
}
```
`PriceHistoryDto`: `{ oldPrice, newPrice, changedAt, changedByUsername }`.

### 2. Top Customers For This Product (new)

**Important, read before building:** this can only count sales tied to a real registered customer (`sale.customer_id IS NOT NULL`). Walk-in sales and the quick-typed-name sales from the recent checkout redesign have no stable identity to aggregate by — a plain string like `"David"` typed three separate times has no way to be recognized as the same person. This is exactly the trade-off we discussed when designing that feature — it shows up concretely here. The UI needs to say this plainly (see §2, Part 2) rather than just silently showing an incomplete list with no explanation.

**New repository query** (on `SaleItemRepository` or wherever similar aggregate queries live):
```java
@Query("""
SELECT si.sale.customer.id, si.sale.customer.firstName, si.sale.customer.lastName, si.sale.customer.phone,
       SUM(si.quantity - si.quantityRefunded), SUM(si.unitPrice * (si.quantity - si.quantityRefunded)),
       MAX(si.sale.saleDate)
FROM SaleItem si
WHERE si.product.id = :productId
  AND si.sale.customer IS NOT NULL
  AND si.sale.isVoided = false
GROUP BY si.sale.customer.id, si.sale.customer.firstName, si.sale.customer.lastName, si.sale.customer.phone
ORDER BY SUM(si.quantity - si.quantityRefunded) DESC
""")
List<Object[]> findTopCustomersForProduct(@Param("productId") Long productId, Pageable pageable);
```
(Raw `Object[]` projection is fine here — map it to a proper `ProductTopCustomerDto` in the service layer. If your JPA setup handles constructor-expression projections cleanly elsewhere in the codebase, use that pattern instead for consistency — match whatever style the rest of the reporting queries already use.)

**New endpoint:**
```java
@GetMapping("/{id}/top-customers")
@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
public ResponseEntity<ApiResponse<List<ProductTopCustomerDto>>> getTopCustomers(
        @PathVariable Long id, @RequestParam(defaultValue = "10") int limit) {
    return ResponseEntity.ok(new ApiResponse<>(true, "...", productService.getTopCustomers(id, limit)));
}
```
`ProductTopCustomerDto`: `{ customerId, customerName, phone, totalQuantityBought, totalSpent, lastPurchaseDate }`.

### 3. Total Sold & Total Profit (new)

This needs to correctly account for refunds — a unit that was sold and later refunded shouldn't count toward "how much we sold" or "how much profit we made."

**New repository query:**
```java
@Query("""
SELECT SUM(si.quantity - si.quantityRefunded),
       SUM(si.unitPrice * (si.quantity - si.quantityRefunded)),
       SUM((si.unitPrice - COALESCE(si.costPriceAtSale, 0)) * (si.quantity - si.quantityRefunded))
FROM SaleItem si
WHERE si.product.id = :productId
  AND si.sale.isVoided = false
""")
Object[] getSalesSummaryForProduct(@Param("productId") Long productId);
```

**New endpoint:**
```java
@GetMapping("/{id}/sales-summary")
@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
public ResponseEntity<ApiResponse<ProductSalesSummaryDto>> getSalesSummary(@PathVariable Long id) {
    return ResponseEntity.ok(new ApiResponse<>(true, "...", productService.getSalesSummary(id)));
}
```
`ProductSalesSummaryDto`: `{ totalQuantitySold, totalRevenue, totalProfit, profitMarginPercent }` — `profitMarginPercent = totalProfit / totalRevenue * 100`, guard against divide-by-zero (a product with zero sales should show `0%` or `null`, not throw or show `NaN`).

**Note on `costPriceAtSale` being null for old data:** sales created before the profit-tracking feature was added won't have this field populated. The `COALESCE(..., 0)` above prevents a crash, but be aware it'll understate profit for any pre-existing sales — worth a one-time note to whoever reviews these numbers rather than something to "fix" in code, since there's no way to retroactively know a historical cost that was never recorded.

### 4. Extend the existing supplier widget with totals

**`ProductSupplierHistoryDto.java`** — add two fields:
```java
private Integer totalQuantityPurchased;
private BigDecimal totalAmountSpent;
```
plus matching getters/setters.

**Wherever `getProductSuppliers()` currently builds this DTO** — the underlying query needs to also `SUM(pi.quantity)` and `SUM(pi.quantity * pi.unitCost)` per supplier alongside the existing `COUNT(*)`/most-recent logic, and populate the two new fields.

---

## PART 2 — Frontend

### `frontend/src/api/services.js` — add four functions to `productService`

```js
  getPriceHistory: async (productId) => {
    const response = await apiClient.get(`/products/${productId}/price-history`);
    return response.data;
  },

  getTopCustomers: async (productId, limit = 10) => {
    const response = await apiClient.get(`/products/${productId}/top-customers?limit=${limit}`);
    return response.data;
  },

  getSalesSummary: async (productId) => {
    const response = await apiClient.get(`/products/${productId}/sales-summary`);
    return response.data;
  },
```
(`getSuppliers`/`getCostHistory` already exist — no change needed there beyond the backend DTO gaining two fields, which the existing frontend call will automatically receive.)

### `ProductDetail.jsx` — add three `useQuery` calls

Right next to the existing `suppliersData`/`costHistoryData` queries:
```js
  const { data: priceHistoryData } = useQuery({
    queryKey: ['product-price-history', id],
    queryFn: () => productService.getPriceHistory(id),
    enabled: !!id,
  });

  const { data: topCustomersData } = useQuery({
    queryKey: ['product-top-customers', id],
    queryFn: () => productService.getTopCustomers(id),
    enabled: !!id,
  });

  const { data: salesSummaryData } = useQuery({
    queryKey: ['product-sales-summary', id],
    queryFn: () => productService.getSalesSummary(id),
    enabled: !!id,
  });

  const priceHistory = priceHistoryData?.data || [];
  const topCustomers = topCustomersData?.data || [];
  const salesSummary = salesSummaryData?.data;
```

### Section A — Sales Summary cards (add near the top, right after the main product info Paper)

This is the headline info — put it where it's seen first, not buried below the supplier tables:
```jsx
      <Grid container spacing={2} sx={{ mb: 3 }}>
        <Grid item xs={6} md={3}>
          <Paper sx={{ p: 2 }}>
            <Typography variant="caption" color="text.secondary">Total Sold</Typography>
            <Typography variant="h5" fontWeight="bold">{salesSummary?.totalQuantitySold ?? 0}</Typography>
          </Paper>
        </Grid>
        <Grid item xs={6} md={3}>
          <Paper sx={{ p: 2 }}>
            <Typography variant="caption" color="text.secondary">Total Revenue</Typography>
            <Typography variant="h5" fontWeight="bold">{formatCurrency(salesSummary?.totalRevenue)}</Typography>
          </Paper>
        </Grid>
        <Grid item xs={6} md={3}>
          <Paper sx={{ p: 2, bgcolor: 'success.50' }}>
            <Typography variant="caption" color="text.secondary">Total Profit</Typography>
            <Typography variant="h5" fontWeight="bold" color="success.main">{formatCurrency(salesSummary?.totalProfit)}</Typography>
          </Paper>
        </Grid>
        <Grid item xs={6} md={3}>
          <Paper sx={{ p: 2 }}>
            <Typography variant="caption" color="text.secondary">Profit Margin</Typography>
            <Typography variant="h5" fontWeight="bold">
              {salesSummary?.profitMarginPercent != null ? `${salesSummary.profitMarginPercent.toFixed(1)}%` : '-'}
            </Typography>
          </Paper>
        </Grid>
      </Grid>
```

### Section B — Selling Price History (add as a new section, alongside the existing Cost History table)

```jsx
      <Paper sx={{ p: 3, mb: 3 }}>
        <Typography variant="h6" gutterBottom>Selling Price History</Typography>
        {priceHistory.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            No price changes recorded yet. This only tracks changes made from now on.
          </Typography>
        ) : (
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Date</TableCell>
                  <TableCell align="right">Old Price</TableCell>
                  <TableCell align="right">New Price</TableCell>
                  <TableCell align="right">Change</TableCell>
                  <TableCell>Changed By</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {priceHistory.map((h, idx) => {
                  const diff = h.newPrice - h.oldPrice;
                  return (
                    <TableRow key={idx}>
                      <TableCell>{formatDateTime(h.changedAt)}</TableCell>
                      <TableCell align="right">{formatCurrency(h.oldPrice)}</TableCell>
                      <TableCell align="right">{formatCurrency(h.newPrice)}</TableCell>
                      <TableCell align="right" sx={{ color: diff >= 0 ? 'success.main' : 'error.main' }}>
                        {diff >= 0 ? '+' : ''}{formatCurrency(diff)}
                      </TableCell>
                      <TableCell>{h.changedByUsername}</TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </TableContainer>
        )}
      </Paper>
```

### Section C — Top Customers (new section)

```jsx
      <Paper sx={{ p: 3, mb: 3 }}>
        <Typography variant="h6" gutterBottom>Customers Who Buy This Most</Typography>
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 2 }}>
          Only counts registered customers — walk-in sales and quick-typed names can't be tracked as a repeat customer.
        </Typography>
        {topCustomers.length === 0 ? (
          <Typography variant="body2" color="text.secondary">No registered-customer purchases yet.</Typography>
        ) : (
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Customer</TableCell>
                  <TableCell align="right">Quantity Bought</TableCell>
                  <TableCell align="right">Total Spent</TableCell>
                  <TableCell>Last Purchase</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {topCustomers.map((c) => (
                  <TableRow
                    key={c.customerId}
                    hover
                    sx={{ cursor: 'pointer' }}
                    onClick={() => navigate(`/customers/${c.customerId}`)}
                  >
                    <TableCell>{c.customerName}{c.phone ? ` (${c.phone})` : ''}</TableCell>
                    <TableCell align="right">{c.totalQuantityBought}</TableCell>
                    <TableCell align="right">{formatCurrency(c.totalSpent)}</TableCell>
                    <TableCell>{formatDateTime(c.lastPurchaseDate)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        )}
      </Paper>
```
(The row's `onClick` navigating to a customer detail page assumes one exists at `/customers/:id` — if it doesn't yet, either build a minimal one or drop the click-through for now and just show the table.)

### Section D — Extend the existing Suppliers table with the two new columns

Find the existing suppliers `<TableHead>` and add two columns:
```jsx
                  <TableCell align="right">Total Qty</TableCell>
                  <TableCell align="right">Total Spent</TableCell>
```
And in the corresponding row mapping, add:
```jsx
                    <TableCell align="right">{s.totalQuantityPurchased}</TableCell>
                    <TableCell align="right">{formatCurrency(s.totalAmountSpent)}</TableCell>
```

---

## Testing checklist

- [ ] Editing a product's price twice creates two price-history rows, each showing the correct old→new values and who made the change.
- [ ] A product that's never had its price changed shows the "no price changes recorded yet" message, not an error.
- [ ] Total Sold / Total Profit correctly exclude voided sales and correctly subtract refunded quantities — test by selling 10, refunding 3, confirm the summary shows 7 sold, not 10.
- [ ] Top Customers only shows people from actual registered-customer sales; a product mostly bought by walk-ins shows a short or empty list, with the explanatory caption visible, not a confusing blank table.
- [ ] The supplier table's new Total Qty/Total Spent columns sum correctly across multiple purchases from the same supplier, not just showing the most recent one.
