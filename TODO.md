# TODO

## Phase doc4-back-office (v1.4 requirements doc4)

### §2 CSV Import/Export
- [x] Update `bms-backend/pom.xml` add `com.opencsv:opencsv`.

- [x] Add DTO `ImportResultDto`.

- [x] Implement `ProductService.importProductsFromCsv(MultipartFile)` row-by-row with per-row errors, auto-create categories, update by SKU.


- [x] Implement `ProductService.exportProductsToCsv(Writer)` using OpenCSV.

- [x] Add endpoints in `ProductController`:

  - [x] `POST /api/products/import` (ADMIN/MANAGER)

  - [x] `GET /api/products/export` (ADMIN/MANAGER, raw CSV)

- [ ] Implement Sales CSV export in backend (`SaleService.exportSalesToCsv` + `SaleController` endpoint `GET /api/sales/export?startDate=&endDate=`).
- [ ] Implement Customers CSV export in backend (`CustomerService.exportCustomersToCsv` + `CustomerController` endpoint `GET /api/customers/export`).

- [x] Frontend `frontend/public/` add CSV template file (header + sample row).


- [ ] Update `frontend/src/api/services.js` add import/export helpers (products/sales/customers) incl. blob download.
- [ ] Update `frontend/src/pages/Products.jsx`:
  - [ ] Add Import dialog (file picker + download template + upload)
  - [ ] Show import result summary with per-row errors
  - [ ] Add Export button downloading raw CSV
- [ ] Update `frontend/src/pages/Sales.jsx` add Export button.
- [ ] Update `frontend/src/pages/Customers.jsx` add Export button.

### §3 Low-Stock Reorder Suggestions
- [ ] Add DTO `ReorderSuggestionDto`.
- [ ] Implement `InventoryService.getReorderSuggestions()`:
  - [ ] averageDailySales from last 30 days
  - [ ] daysUntilStockout and suggestedReorderQuantity targeting 14 days
  - [ ] last supplier + last unit cost from latest PurchaseItem
  - [ ] handle averageDailySales==0 as insufficient sales history
- [ ] Add endpoint `GET /api/.../reorder-suggestions` (ADMIN/MANAGER).
- [ ] Update low-stock UI (likely `frontend/src/pages/Inventory.jsx`):
  - [ ] show reorder suggestion table
  - [ ] days color coding
  - [ ] per-row “Create Purchase Order” prefill `/purchases/new`.

### §1 Cash Drawer / Shift Reconciliation (last)
- [ ] Add `cash_shifts` table + `cash_shift_id` column to `sales` (entity + schema).
- [ ] Add `CashShift` entity/repository.
- [ ] Implement `CashShiftService.openShift/current/closeShift` + shift calculation.
- [ ] Add `CashShiftController` endpoints.
- [ ] Modify `SaleService.createSale()` to tag cash sales to open shift.
- [ ] Frontend add `frontend/src/pages/CashShift.jsx`.
- [ ] Show persistent shift indicator for cashiers.
- [ ] Add shift history list for admin/manager.

### Testing / Validation (after all)
- [ ] Backend Maven build.
- [ ] Frontend Vite build.
- [ ] Manual acceptance checks for doc4 criteria.

