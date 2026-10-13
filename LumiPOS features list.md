# LumiPOS Features List

LumiPOS is a desktop + web-capable Business Management System (BMS) for retail stores (mini-marts, shops, supermarkets). The application is delivered as a Windows Electron desktop app that bundles a Spring Boot backend and ships a bundled JRE. It supports SQLite for offline/single-PC setups and MySQL for multi-terminal usage.

---

## 1. Point of Sale (POS)
- **Product Search**: Search by product name or SKU.
- **Keyboard-Friendly Search Workflow**: Press Enter to quickly add an exact SKU match (or a single matching product) to the cart.
- **Quick Cart Management**: Add/update/remove line items, adjust quantities, and view running totals in real time.
- **Discounts**: Apply percentage or amount-based discounts at the sale level (where permitted).
- **Tax Handling**: Supports configurable tax rates per product/sale context as configured in the system.
- **Multiple Payment Methods**: Cash, Card, Bank Transfer, Other, as available in the payment flow.
- **Hold/Resume Sale**: Save an in-progress transaction and resume it later.
- **Void/Cancel Sale**: Void entire sale or individual line items with appropriate permissions.
- **Receipt Printing**: Thermal/standard receipt printing via the printer integration. Receipt layout is customizable.
- **Sale Return/Refund**: Process returns/refunds against previous sales. Returns can be partial or full as applicable.
- **Customer Selection**: Link a sale to an existing customer (optional).
- **Real-time Stock Updates**: Sales decrement stock quantities based on product units.
- **Keyboard-Friendly Operation**: Designed for fast cashier workflows.

---

## 2. Inventory Management
- **Product Master**: SKU, name, description, category, brand, supplier, cost price, selling price, tax rate, unit of measure, reorder level, active/inactive.
- **Stock Tracking**: Real-time on-hand quantities with low-stock alerts/minimum reorder levels.
- **Stock Adjustments**: Manual stock adjustments (add/deduct) with audit trail.
- **Purchase Integration**: Purchases increase stock; goods received flow into inventory.
- **Expiry Tracking**: Expiry date support for perishable items (where enabled).
- **Batch/Lot Tracking**: Support for batches/lots as applicable to products.
- **Barcode/Label Support**: Product SKU field is used as the lookup key; barcode scanning hardware that inputs the SKU/identifier into the search field is supported via standard keyboard input. The application does not have a dedicated barcode camera scanning implementation in the core product model.
- **SKU-based Lookup**: Products use SKU as the unique identifier for quick lookup.

---

## 3. Purchases & Suppliers
- **Purchase Orders/Invoices**: Create, edit, view and manage purchase records with supplier, date, reference number, items, quantities, prices and totals.
- **Supplier Management**: Add/edit suppliers with contact details, address, tax info and status.
- **Goods Received**: Record goods received against purchases, updating inventory.
- **Purchase Returns**: Handle returns to suppliers and reverse stock/cost effects as configured.
- **Payment Tracking**: Track supplier payments and outstanding balances (Accounts Payable context).
- **Purchase History**: Filterable purchase history by supplier, date range, status.

---

## 4. Customers & CRM
- **Customer Master**: Add/edit customers with name, phone, email, address, loyalty info.
- **Customer Balances**: Track customer balances (credit sales, payments, outstanding AR).
- **Credit Sales (AR)**: Allow sales on credit; track due amounts and payment receipts.
- **Customer History**: View purchase history, returns, payments and account statements per customer.
- **Loyalty/Points**: Customer loyalty points tracking where applicable.
- **Customer Groups/Notes**: Manage groups and notes on customer records.
- **Credit Limit**: Enforce/configure credit limits per customer (if used).

---

## 5. Sales, Orders & Invoicing
- **Sales History**: Browse all sales with filters (cashier, date range, payment method, customer, status).
- **Sale Details**: View invoice details, line items, payments, discounts, taxes, returns.
- **Order Management**: Track sales/orders through states as applicable.
- **Hold Orders**: Resume held orders from POS or order list.
- **Invoice Printing**: Reprint invoices/receipts from sales history.
- **Credit Note/Return Receipts**: Generate credit notes for returns.

---

## 6. Cash Shift Management
- **Open Shift**: Cashier opens a shift with opening cash float (now supports 0.00 empty drawer). 
- **Live Shift View**: See cash sales, returns/refunds, expected drawer, variance during the shift.
- **Close Shift**: Count actual cash, enter closing amount, record notes. Variance calculated as actual minus expected (expected = opening + cash sales − refunds). Cross-shift refunds are correctly attributed to the shift when returned (by return date) so drawer math is accurate.
- **Shift Audit Trail**: SHIFT_OPEN and SHIFT_CLOSE logged with amounts, expected/actual, variance and over-tolerance annotations.
- **Shift History**: Review past shifts (dates, cashier, opening/closing, sales, returns, expected, actual, variance, status, notes).
- **Concurrency Safety**: Pessimistic locks prevent opening/closing conflicts for the same cashier/shift.
- **Tolerance & Warnings**: Configurable variance tolerance (default 10.00 currency units) with explicit warnings when exceeded; boundary (±10.00) is treated as within tolerance.
- **Multi-Cashier Support**: Track shifts per cashier with manager visibility.

---

## 7. Accounting & AR/AP
- **Accounts Receivable (AR)**: Manage customer receivables, credit invoices, due amounts, aging.
- **Customer Payments**: Record receipts against outstanding AR invoices.
- **Accounts Payable (AP)**: Track supplier invoices, payments, outstanding balances.
- **Expenses**: Record business expenses (category, amount, date, vendor/notes, attachments).
- **Chart of Accounts/Accounting View**: Accounting module screens for managing AR/AP and related transactions.
- **Financial Reports**: Integration with reports for sales, profit, expenses, receivables/payables.

---

## 8. Reports & Analytics
- **Sales Reports**: Daily/period sales summaries, by cashier, by payment method, by category/product.
- **Profit/Loss**: Gross profit based on cost vs selling price where configured.
- **Inventory Reports**: Stock on hand, low stock, valuation, movement history.
- **Customer Reports**: Top customers, customer balances, AR aging.
- **Purchase Reports**: Purchases by supplier/date, purchase value.
- **Expense Reports**: Expense breakdown by category/date.
- **Cash Shift Reports**: Shift summaries and variance history.
- **Audit Logs**: System audit trail of key actions (who, when, what, entity, details).
- **Exportable Reports**: Export reports in common formats (e.g. CSV/Excel as supported by UI).
- **Date Range Filters**: All major reports support flexible date ranges.

---

## 9. Users, Roles & Permissions
- **User Management**: Add/edit users (admins, cashiers, managers, etc.) with personal/contact details.
- **Role-Based Access Control (RBAC)**: Roles control access to modules (POS, Inventory, Reports, Settings, Users, etc.).
- **Authentication**: Secure login with username/password.
- **Session Management**: User sessions, logout, and session state handling.
- **Auditability**: User actions recorded in audit logs.

---

## 10. Settings & Configuration
- **Store Information**: Company/store name, address, phone, email, logo, tax ID.
- **Receipt Customization**: Customize receipt header/footer, logo, fields, layout and printer settings.
- **System Preferences**: Currency, date/time format, language/locale, tax settings.
- **Backup & Restore**: Full data backup/restore (local and Google Drive integration). Includes data directory management and restore with progress tracking.
- **Data Management**: Tools for managing data/settings with contextual help tooltips.
- **License/Activation**: License key management/activation flow.
- **Printer Configuration**: Configure default printer for receipts.
- **Google Integration**: Google Drive backup/restore and related OAuth settings (where configured).

---

## 11. Multi-Platform / Deployment
- **Windows Desktop (Electron)**: Packaged as NSIS installer (`LumiPOS Setup 1.0.0.exe`) bundling backend JAR + JRE + frontend assets.
- **Spring Boot Backend**: REST API (Java 17 target; runs on bundled Temurin 21 in installer).
- **React + Vite Frontend**: SPA served from backend static resources when packaged, or run standalone in dev.
- **Database Options**: SQLite (offline/single workstation) and MySQL (multi-terminal/networked). Automatic schema creation/migrations handled by JPA/Hibernate (ddl-auto update in configured profiles).
- **Cross-Terminal**: Multi-terminal mode with MySQL on shared network allows multiple PCs/tablets to access the same store DB.
- **Portable-Friendly Paths**: AppData-based SQLite storage under `%LOCALAPPDATA%\LumiPOS\data` with legacy reconciliation and migration utilities.
- **Bundled Runtime**: Ships Temurin JRE in `bundled-jre/` for zero-dependency install.
- **Auto-Update/Restart Considerations**: Backend process managed by Electron; system keep-awake and restart behavior present for stability.

---

## 12. Usability, UX & Theming
- **Responsive UI**: Material-UI (MUI) based responsive design.
- **Internationalization (i18n)**: Multi-language support (English, French, Japanese, Myanmar, Thai) with locale JSON files.
- **Dark/Light Themes**: Theme support where available.
- **Icons/Branding**: Custom logos and icons.
- **Help Tooltips**: Contextual help (e.g. backup/data settings) via reusable HelpTip component.
- **Clear Validation Messages**: Form validation with user-friendly messages (including zero-opening validation and variance messaging).
- **Loading States & Notifications**: Snackbar/alerts for success/warning/error/info.

---

## 13. Security, Stability & Compliance
- **Role-Based Restrictions**: Sensitive actions restricted by role/permissions.
- **Audit Logging**: Comprehensive audit log for accountability.
- **Data Integrity**: Soft deletes and referential handling in data model; validation constraints on DTOs.
- **Stability Features**: Restart/backoff, cleanup of background jobs, and resilience for backend connectivity.
- **Safe Migrations**: SQLite schema migrations (NOT NULL additions, data dir migration) to preserve existing data.
- **License Key Protection**: License file stored under `C:/LumiPOS/license.key` (as configured); runtime checks where applicable.
- **Secrets Management**: `.env` used for DB/OAuth credentials and excluded from Git.

---

## 14. Technical Details (for operators/developers)
- **Backend**: Spring Boot 3+, Java 17 (bytecode), Maven build, JUnit 5 tests (45+ tests).
- **Frontend**: React, Vite, TanStack Query, React Router, Material-UI, i18next.
- **Build Pipeline**: Frontend built via Vite, copied to `bms-backend/src/main/resources/static`, backend packaged as executable JAR, Electron bundles JAR+JRE+assets into NSIS installer.
- **Testing**: Unit tests for validation (OpenShiftRequest), utility (CashVarianceUtil), SQLite migrations, receipt layout, etc.
- **Data Layer**: JPA/Hibernate with Spring Data; SQLite via custom dialect/URL handling; MySQL via standard JDBC.
- **Validation**: Bean Validation (`@NotNull`, `@PositiveOrZero`, `@Positive`, etc.) on DTOs.
- **Version**: 1.0.0 (app/build metadata).

---

*This document captures features as implemented in the codebase at branch `fix/cash-shift-drawer-reconciliation` (commit 2e3383d). Some features depend on license/activation state, role permissions, and deployment profile (SQLite vs MySQL).*