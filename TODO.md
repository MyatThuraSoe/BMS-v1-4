# BMS-v1-4 Implementation Progress

## Section 5: Product Detail View with Supplier Widget

### 5.2 Backend — PurchaseItemRepository query ✅
- Added `findProductSupplierHistory(Long productId)` JPQL query in `PurchaseItemRepository.java`
- Returns supplierId, supplierName, timesPurchased, mostRecentUnitCost, mostRecentPurchaseDate

### 5.3 Backend — Endpoint `/api/products/{id}/suppliers` ✅
- Added `GET /{id}/suppliers` endpoint in `ProductController.java`
- Added `getProductSuppliers(Long productId)` method with DTO mapping in `ProductService.java`
- Created `ProductSupplierHistoryDto.java` with required fields

### 5.4 Frontend — Product Detail Page with Supplier Widget ✅
- Created `frontend/src/pages/ProductDetail.jsx` — read-only product detail view
  - Product info card (name, SKU, barcode, description, image)
  - Details grid (category, unit price, cost price, tax rate, stock, min stock, created/updated dates)
  - "Purchased From" supplier history widget/table
  - Each supplier row shows: name, times purchased, most recent unit cost, last purchase date
  - "Order More" button → navigates to `/purchases/new?supplierId=X&productId=Y`
  - "View Supplier" button → navigates to `/suppliers/{supplierId}`
  - Shows "Not purchased yet" alert for new products with no history
- Updated `frontend/src/api/services.js` — added `productService.getSuppliers(productId)`
- Updated `frontend/src/App.jsx` — added routes:
  - `/products/:id` → ProductDetail (view mode)
  - `/products/:id/edit` → ProductForm (edit mode)
- Updated `frontend/src/pages/Products.jsx` — added View Details button (eye icon), Edit button now goes to `/products/:id/edit`

### Section 1: Self-Service "Change My Password" — Pending
- Backend: ChangePasswordRequest DTO, AuthController endpoint, UserService method
- Frontend: Change password UI in DashboardLayout or Settings

### Section 2 + 3: Filters + Clickable Dashboard — Partially Done
- Products page already has view presets (All, Most Sold, Least Sold, Low Stock) ✅
- Dashboard clickable widgets — needs navigation integration
- Inventory page filters — pending

### Section 4: Sales Filtering — Pending
- SaleController already has date-range parameter support
- Frontend Sales page needs filter UI
