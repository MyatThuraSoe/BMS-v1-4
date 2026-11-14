# Changelog

## Unreleased — Business Logic Fixes (BMS-v1-4 Business Logic Fix Specification)

Implements findings C1–C11, P1–P10 and O1–O8 from
`BMS-v1-4 Business Logic Fix Specification.md` (backend + web frontend; Android app unchanged).

### Accounting ledgers and period close (P1, O8)

- New append-only `inventory_ledger` and `profit_ledger` tables with unique source keys;
  `LedgerPostingService` is the sole writer of `Product.costPrice`.
- `period_close` freezes revenue / COGS / expense totals per month; closed periods reject
  back-dated mutations and can only be reopened by an ADMIN with a recorded reason.
- Schema changes are applied by an idempotent startup runner (`AccountingSchemaMigration`,
  H2 / SQLite / MySQL) following the project's existing repair-runner mechanism; Flyway
  remains disabled in all profiles.

### Ledger read cut-over — read this before comparing reports

Historical `costPriceAtSale` values are **not** rewritten. Past gross profit stays exactly
as recorded. Reports before and after the cut-over are therefore computed differently and
must be understood accordingly (spec section 9.5).

- The cut-over is **not automatic**. An ADMIN activates ledger-backed reporting from
  Accounting → Accounting Period Control → "Activate Ledger Reads"
  (`POST /api/accounting/ledger-cutover`). Activation is refused unless both the profit
  and inventory reconciliations report fully reconciled with no unexplained differences.
- The cut-over date/time is recorded in the system setting **`accounting.ledger.cutover`**
  (ISO local date-time, shop timezone convention) and in the audit log as
  `LEDGER_READ_CUTOVER`. To find the effective cut-over date, read that setting.
- Before the cut-over: reports are computed from live transactional tables (legacy path).
- After the cut-over: reports read from the reconciled ledgers; periods closed before the
  cut-over serve their frozen totals.
- Backfills (`POST /api/accounting/profit-backfill`, `/inventory-backfill`) require an
  explicit backup confirmation and refuse to run while reconciliation blockers remain.
  Always back up the database first and rehearse migrations on a copy of production data.

### Corrected business logic (summary)

- C1: weighted-average cost uses **all owned units** (warehouse + quarantine + shop),
  not shop stock only.
- C2: return-to-vendor is a complete transaction (stock out, valued reversal, supplier
  credit in invoice currency incl. pro-rata invoice tax).
- C3: deleting/editing unreceived purchase orders no longer touches stock or cost.
- C4: cost changes require an explicit reasoned adjustment endpoint; no silent rewrites.
- C5/C6: landed cost follows paid and received quantities.
- C7–C11: cost precision (scale 6), shared reversal helper, opening-stock valuation,
  safe deletion rules, live-cost snapshots at sale time.
- P2: order conversion books COGS at fulfilment cost; P3: write-offs/shrinkage reach P&L.
- P4: tax after discount; GL no longer double-counts promotion discounts; journal balances.
- P5–P10: audit fields on price/cost history, void cash trail, no silent report truncation,
  correct return valuation + disposition, below-cost guard with manager override policy.
- O1: customer overpayments/returns become `creditBalance` (never dropped); nightly
  read-only AR reconciliation + `GET /api/ar/reconciliation`.
- O2: orders lock prices at creation with the same promotion/discount/tax engine as sales.
- O3: purchase payments recorded per purchase; payable status derived
  (PENDING/PARTIAL/PAID); payables aging report; RTV credit in invoice currency.
- O4: expenses can be capitalized into purchase cost (excluded from operating P&L);
  duplicate-expense warnings near purchase dates.
- O7: all day/period boundaries use the shop timezone (`shop.timezone` setting, UTC
  default); deterministic ordering tie-breakers on same-day records.

### Known limitations (owner decisions applied)

- Customer credit is stored but not auto-applied to future invoices or credit-limit checks.
- Duplicate-expense detection matches amount + date window only (no supplier field on expenses).
- RTV supplier credit includes invoice tax regardless of tax recoverability.
- Payables aging is shown in base currency.
- Entity timestamps remain wall-clock local date-times interpreted in the shop timezone
  (no Instant conversion).