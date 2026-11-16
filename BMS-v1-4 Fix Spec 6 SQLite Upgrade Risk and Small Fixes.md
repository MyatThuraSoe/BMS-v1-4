# BMS-v1-4 Fix Spec 6: SQLite Upgrade Risk and Small Fixes

Based on commit `d5ca6aa` (PR #19). Read the code only; nothing was run. Written in simple words for an AI agent.

## How to work

- Do NOT touch the Android app. Do not change old API shapes. New request fields must be optional. New response fields are OK.
- One item = one pull request. Write the failing test first. Then fix. Then run all tests.
- Money is `BigDecimal`, rounded `HALF_UP`, 2 decimals.
- Schema changes: for SQLite use `db/sqlite/schema.sql` and `SqliteSchemaMigrator`. For H2 and MySQL use `AccountingSchemaMigration`. Never add a NOT NULL column without a default.
- Tag **\[Decision\]** = owner choice. Use the default given and put it in a setting.

## Order of work

1. M1 (old SQLite databases may reject new values)
2. M2 (manager password can be guessed)
3. M3, M4, M5 (small fixes)
4. Final checks at the end

---

## M1. Old SQLite databases may reject new enum values (Medium-High)

**Files:** `sqlite/SqliteSchemaMigrator.java`, `resources/db/sqlite/schema.sql`, `config/DiscountTypeCheckConstraintRepair.java`, `test/.../SqliteSchemaMigratorTest.java`.

**What is wrong:**

1. `schema.sql` is a Hibernate export. Enum columns have a check rule, for example `event_type varchar(30) not null check (event_type in ('SALE','RETURN','VOID','WRITE_OFF','EXPENSE','REVALUATION','PURCHASE_VARIANCE','INVENTORY_GAIN'))`.
2. The migrator runs `create table if not exists`. If the table already exists, it is skipped. Then it only adds missing columns. It never changes the check rule of an existing column.
3. SQLite cannot change a check rule with ALTER. So an old database keeps its old list.
4. Later releases added enum values, for example `INVENTORY_GAIN` (profit ledger event), `REFUND_LIABILITY` (settlement account), new stock movement reference types, and new payment statuses. On an upgraded shop, saving a row with a new value can fail with `CHECK constraint failed`.
5. `DiscountTypeCheckConstraintRepair` says SQLite needs no repair, so it does nothing here.
6. I could not test an old database. Treat this as likely, and prove it with the test below first.

**Fix steps:**

1. Write a failing test first. Build an old SQLite file where `profit_ledger.event_type` has a check list WITHOUT `INVENTORY_GAIN`. Run the migrator. Then insert a row with `INVENTORY_GAIN`. Today this fails.
2. In `SqliteSchemaMigrator`, after `addMissingColumns`, add a step `refreshCheckConstraints(connection, expectedTables)`:
   - For each table that already existed, read its SQL: `SELECT sql FROM sqlite_master WHERE type='table' AND name=?`.
   - Find every `check (<col> in ('A','B',...))` in that SQL. Do the same for the same table in `schema.sql`.
   - If the old list is missing any value that the new list has, mark the table as needing a rebuild.
3. Rebuild a marked table with the standard SQLite steps, inside one transaction:
   - `PRAGMA foreign_keys=OFF` (set before the transaction starts)
   - create `<table>__new` using the CREATE statement from `schema.sql`
   - `INSERT INTO <table>__new (<common columns>) SELECT <common columns> FROM <table>`
   - `DROP TABLE <table>`
   - `ALTER TABLE <table>__new RENAME TO <table>`
   - re-create the indexes of that table from `schema.sql`
   - `PRAGMA foreign_key_check` must return no rows, then `PRAGMA foreign_keys=ON`
   - If any step fails: roll back and stop startup with a clear message that names the table. Never leave a half-built table.
4. Make a copy of the database file before the first rebuild: `<name>.pre-upgrade-<yyyyMMddHHmm>.bak` in the same folder. Do it once per start, only if a rebuild is needed.
5. Keep it safe to run twice: when all check lists already match, do nothing.
6. **\[Decision\]** Simpler option: drop all enum check rules from `schema.sql` (store enums as plain varchar). Then old tables with old rules still need a rebuild once, but new tables never have the problem again. Default: keep the rules and do the rebuild (step 2-3).
7. Add the new tests in `SqliteSchemaMigratorTest`:
   - old check list missing a value -> after migration the new value can be inserted, old rows are all still there, row counts are equal;
   - second start changes nothing and creates no backup;
   - a table with a foreign key reference keeps its data after rebuild;
   - a failed rebuild (force an error) leaves the original table untouched.

**Done when:** an upgraded SQLite shop can post `INVENTORY_GAIN`, `REFUND_LIABILITY` settlements, new stock movement types and new payment statuses without errors.

---

## M2. Manager password can be guessed through sale requests (Medium-Low)

**Files:** `service/SaleService.java` (`approveManagerCredentials`), `service/PurchaseService.java` (over-shipment override), `service/AuthService.java` (login attempt tracking), `repository/UserRepository.java`.

**What is wrong:** `approveManagerCredentials(username, password)` checks the password with `passwordEncoder.matches` and throws on a bad one. There is no attempt counter, no lockout and no audit entry. A cashier can send many sale requests with a guessed manager password. The login endpoint has an attempt tracker, but this path skips it. The receiving override in `PurchaseService` works the same way.

**Fix steps:**

1. Move the credential check into one shared method, for example `ManagerApprovalService.verify(username, password, purpose)`. Use it from both `SaleService` and `PurchaseService`.
2. Inside it use the same attempt tracker as login (the failed-attempt map and lock time in `AuthService`). Key by lower-case username. After the same number of failures as login, lock that username for the same time. A locked manager also cannot log in normally for that time (same tracker), which is the safe default.
3. Also count failures by the requesting cashier id. After 5 failed approvals in 10 minutes from the same cashier, reject all manager approvals from that cashier for 10 minutes. Put the numbers in settings: `auth.approval.maxFailures` (default 5), `auth.approval.lockMinutes` (default 10).
4. Audit every approval result: `MANAGER_APPROVAL_OK` or `MANAGER_APPROVAL_FAILED`, with cashier id, manager username, purpose (`SALE_DISCOUNT`, `BELOW_COST`, `OVER_SHIPMENT`). Never log the password.
5. Use the same error text for unknown user, wrong password and wrong role: `Invalid manager credentials`. Do not tell which one it was.
6. Reset the counters after one good approval.

**Tests:**

- 5 wrong passwords in a row -> the 6th attempt is rejected as locked, even with the right password, until the time passes.
- A good approval after 2 failures works and resets the counter.
- Failures write audit rows. The password never appears in the audit text.
- Unknown user, wrong password and a non-manager user all return the same message.

---

## M3. Discount limit uses the raw request value (Low)

**File:** `service/SaleService.java` (`createSale`, `assertDiscountWithinAuthority`, `computeDiscount`).

**What is wrong:** `computeDiscount` uses the request amount ONLY when the shop discount type is `AMOUNT`. For `FIXED` and `PERCENTAGE` it uses the shop setting and ignores the request. But `assertDiscountWithinAuthority` checks `request.getDiscountAmount()` in every mode. So in `PERCENTAGE` or `FIXED` mode a sent value can cause a false "exceeds allowed" error for a discount that was never applied.

**Fix steps:**

1. In `createSale` call `computeDiscount` first. Then call `assertDiscountWithinAuthority(subtotal, discountAmount, cashierId, managerOverride)` with the COMPUTED discount.
2. Only enforce the cashier limit when the discount type is `AMOUNT`. For `FIXED` and `PERCENTAGE` the shop owner set the discount on purpose, so skip the cashier limit. **\[Decision\]** default: skip for shop-set discounts.
3. Keep the audit entry `SALE_DISCOUNT` as is.

**Tests:** `PERCENTAGE` mode with a request amount of 500 on a 100 sale -> no error, discount is the shop percentage. `AMOUNT` mode 50 on a 100 sale by a cashier with limit 10% -> rejected; with valid manager credentials -> accepted.

---

## M4. Mixed line endings in `SaleService.java` (Low)

**File:** `service/SaleService.java` and any file shown as fully changed in a diff.

**What is wrong:** New lines have CRLF (`\r\n`). Old lines have LF. The file shows thousands of changed lines in a diff, so reviews and merges are hard.

**Fix steps:**

1. Add `.gitattributes` at the repo root: `* text=auto eol=lf` and mark binary files (`*.png binary`, `*.jpg binary`, `*.db binary`, `*.jar binary`).
2. Convert `SaleService.java` to LF in its own commit with no other change. Also normalize any other `.java`, `.jsx`, `.js`, `.json`, `.sql`, `.yml` file that has CRLF.
3. Add `.editorconfig` with `end_of_line = lf`, `charset = utf-8`, `insert_final_newline = true`.
4. Add a small test or CI step that fails when a source file contains `\r\n`.

**Done when:** `git diff --stat` after a small edit shows only the lines that were really changed.

---

## M5. Multi-line credit return rounding (Low, watch item)

**Files:** `service/SaleReturnService.java` (`debtForLine`, the line loop), `service/LedgerPostingService.java` (`snapshotRefundSettlement`, `SETTLEMENT_TOLERANCE`).

**What may be wrong:** For a return with many lines, each non-last line gets its share of the debt reduction rounded `HALF_UP`. The last line gets what is left. The rest can be a few cents larger than the last line's own total. Then `lineRefund = lineTotal - lineDebt` is slightly negative. `snapshotRefundSettlement` allows only 0.01 difference, so with many lines the guard could throw and block the return at the till.

**Fix steps:**

1. Write the test first: a credit invoice with 7 lines of uneven prices (like 3.33, 1.67, 9.99, 0.51, 4.44, 2.22, 0.01). Return all lines with debt reduction 17.99. Today this may throw the settlement error. If the test already passes, keep it as a guard and stop.
2. If it fails: in `debtForLine`, clamp every line (including the last) to `min(unallocated, lineTotal)`. If money is still left after the last line, add it to the earlier lines in order, one cent at a time, never above a line's own total.
3. Make sure `lineDebt + lineRefund = lineTotal` and `lineRefund >= 0` for every line.
4. Keep the 0.01 guard as is.

**Tests:** the 7-line case above; a 1-line case; debt reduction 0; debt reduction equal to the whole return. In all cases the sum of `lineDebt` equals the total debt reduction and the GL journal balances.

---

## Already fixed in PR #19 (do not redo)

N1 return ledger (negative amounts, per-line split, guard, repair), N2 user migration (tests added), N3 void double count, N4 store credit on any sale, N5 return estimate in the dialog, O3 purchase row lock, O4 bulk delete moved to `DataRetentionService` with backup confirmation, O5 discount limit and below-cost after discount, O6 order promotion fields, F22 reserved stock on order conversion, S1 and S2 count/setting fixes, supplier delete guard, manager-editable settings allow-list (only `shop.order.reservationHours`), `financial-summary` is admin-only. Tests exist for these; only add a test if one of the "Done when" lines below fails.

## Final checks (run after all items)

- Run all existing tests and the new ones.
- Start the app on a copy of an OLD SQLite database. Then post a stock-count gain, a store-credit return and a void of a credit sale. None may fail with a CHECK error.
- Make a sale with a manager discount approval. Try 6 wrong manager passwords. The lock must work and the audit rows must exist.
- A month with sales, credit sales, partial returns, multi-line returns, card refunds, store credit and voids: GL total debits = total credits.
- Confirm no Android-facing contract changed.

## Report format per item

One short block: what changed, files, tests added, any difference from this spec, setting keys used.
