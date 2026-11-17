# BMS-v1-4 Fix Spec 7: Lockout, Price Lists, Restore, Receipts

Based on commit `4f73e14` (PR #20). Read the code only; nothing was run. Written in simple words for an AI agent.

## How to work

- Do NOT touch the Android app. Do not change old API shapes. New request fields must be optional. New response fields are OK.
- One item = one pull request. Write the failing test first. Then fix. Then run all tests.
- Money is `BigDecimal`, rounded `HALF_UP`, 2 decimals.
- Schema changes: SQLite uses `db/sqlite/schema.sql` + `SqliteSchemaMigrator`. H2 and MySQL use `AccountingSchemaMigration`. Never add a NOT NULL column without a default.
- Tag **\[Decision\]** = owner choice. Use the default given and put it in a setting.
- Keep LF line endings (the repo has a check).

## Order of work

1. P1 (a cashier can lock a manager out)
2. P2, P3 (price lists)
3. P4, P5, P6 (restore and backup files)
4. P7 (receipts)
5. Final checks at the end

---

## P1. A cashier can lock any manager or admin out of login (Medium)

**Files:** `service/ManagerApprovalService.java`, `service/LoginAttemptTracker.java`, `service/AuthService.java`, `controller/UserController.java`.

**What is wrong:**

1. In `ManagerApprovalService.reject(...)` every wrong approval password calls `attemptTracker.recordFailure(managerKey, LoginAttemptTracker.LOGIN)`. That is the SAME counter the login page uses for that username.
2. After 5 wrong approvals the manager's login is locked for 15 minutes. That includes the admin.
3. The cashier's own approval lock is only 10 minutes. After it ends the cashier can do 5 more wrong tries and lock the manager again. So a cashier can keep a manager or admin out of the system.
4. On a good approval the code also clears the manager's login counter (`attemptTracker.clear(managerKey)`). This mixes two different things.

**Fix steps:**

1. Keep two groups of counters, never mixed:
   - Login counters: key = lower-case username. Only `AuthService` changes them.
   - Approval counters: key = `cashier:<cashierId>` and key = `approval:<cashierId>:<managerKey>` (cashier + manager pair).
2. In `reject(...)` do NOT touch the manager's login key. Record the failure on the cashier key and on the pair key, both with `approvalPolicy()`.
3. In `verify(...)`:
   - reject if the cashier key is locked (message with minutes left, as now);
   - reject if the pair key is locked (use the generic `Invalid manager credentials` message);
   - do NOT reject because the manager's LOGIN key is locked. Login lock is about login only. (Reason: a person who locked the login page must not also be able to freeze approvals, and approvals are already limited per cashier.)
4. On a good approval clear only the cashier key and the pair key. Do not clear the manager's login key.
5. Add an admin action to unlock a login: `POST /api/users/{id}/unlock-login`, ADMIN only. It calls `attemptTracker.clear(keyOf(username))`. Audit entry `USER_LOGIN_UNLOCKED`. In the Users page add an "Unlock" button that shows only when the user is locked (add an optional field `loginLocked` and `loginLockedMinutes` to the user response).
6. **\[Decision\]** Admin accounts never lock for more than 1 minute after failed logins (setting `auth.admin.maxLockMinutes`, default 1)? Default: NO. Keep the same lock for everyone, because the unlock button fixes the lock-out.
7. Keep the audit rows `MANAGER_APPROVAL_OK` and `MANAGER_APPROVAL_FAILED`. Keep the generic error text.

**Tests (extend `ManagerApprovalLockoutTest`):**

- Cashier does 5 wrong approvals with manager M. Then M can still log in with the right password. (Today this fails.)
- The same cashier is locked from approvals for the lock time.
- Another cashier can still approve with M.
- Pair lock: cashier A fails 5 times with M, then A tries manager N: only the cashier lock applies (5 total failures already lock A); after the time passes A can use N.
- Good approval does not clear M's login failures.
- Unlock endpoint: needs ADMIN; cleared account can log in at once; audit row exists.

---

## P2. Saving an edited price list may fail (Medium)

**Files:** `service/PriceListService.java` (`apply`), `entity/PriceList.java`, `entity/PriceListItem.java`, `test/.../PriceListServiceTest.java` (new).

**What is wrong:**

1. `PriceListItem` has a unique rule on `(price_list_id, product_id)`.
2. `apply()` does `priceList.getItems().clear()` and then adds new item objects, often for the SAME products.
3. The list uses `orphanRemoval = true`. Hibernate runs INSERTs before the orphan DELETEs when it flushes. So the new row for product A is inserted while the old row for product A still exists. The unique rule fails.
4. No test covers a price list update, so nobody saw it. Prove it with a test first. If the test passes on H2 and SQLite, still do step 2 (it is cleaner and safe).

**Fix steps:**

1. Write the failing test first: create a list with products A and B (prices 10, 20). Update it with A (price 12) and C (price 5). Expected: A has price 12 (same row id), B is gone, C exists, no exception.
2. Change `apply()` so it does NOT clear the list:
   - Build `Map<Long, PriceListItem> existing` from the current items by product id.
   - For each request item: if it exists, only set the new price. If not, create a new item and add it.
   - After the loop remove items whose product id is not in the request: `priceList.getItems().removeIf(...)`.
3. Keep all current checks (duplicate product in request, negative price, product exists, below-cost warnings).
4. Run the test on H2 and on SQLite.

**Tests:** the update case above; update with an empty item list removes all items; update keeps item row ids for unchanged products; duplicate product in the request is rejected.

---

## P3. Editing a deleted price list turns it back on; rename can duplicate (Low)

**Files:** `service/PriceListService.java` (`create`, `update`, `delete`, `apply`), `repository/PriceListRepository.java`, `frontend/src/pages/PriceLists.jsx`.

**What is wrong:**

1. `delete` only sets `isActive = false`.
2. `apply` sets `isActive` from `request.getActive() == null || request.getActive()`. If an update does not send `active`, the value becomes `true`. A deleted list comes back.
3. `create` checks duplicate names, `update` does not. A list can be renamed to an existing name or a case variant.

**Fix steps:**

1. In `apply`: for create, default `active = true` when null. For update, when `request.getActive()` is null keep the current value.
2. Add `existsByNameIgnoreCaseAndIdNot(String name, Long id)` in the repository. In `update` call it and throw `BusinessException("A price list with this name already exists")`.
3. **\[Decision\]** Updating an inactive list is allowed, but it stays inactive unless the request sends `active = true`. The page should show inactive lists with an "Inactive" tag and a Reactivate button that sends `active = true`.
4. Customers pointing at an inactive list: keep as is (the price is ignored). Show a small warning on the customer edit page when the chosen list is inactive.

**Tests:** update without `active` keeps an inactive list inactive; update with `active=true` reactivates; rename to existing name (other case) is rejected; rename to its own name is allowed.

---

## P4. Restore accepts any valid SQLite file (Medium-Low)

**Files:** `service/BackupRestoreService.java` (`assertUsableSqlite`, `startDatabaseRestore`), `controller/BackupRestoreController.java`.

**What is wrong:** `assertUsableSqlite` only runs `PRAGMA quick_check`. Any healthy SQLite file passes, even an empty one or a file from another program. After restart the migrator creates empty tables and the shop looks wiped. The old data is only in the safety copy.

**Fix steps:**

1. After `quick_check`, open the file read-only and check:
   - these tables exist: `users`, `roles`, `products`, `sales`, `system_settings`, `shop_info`;
   - table `users` has at least 1 row that is not deleted;
   - the file is not empty (size above a few KB).
2. If a check fails, throw `IOException("That file is a database, but not a LumiPOS backup.")` and name the first missing item in the log (not in the message).
3. Reject a backup that is NEWER than this app: if `SELECT max(...)` is not possible, compare the table list: when the file has tables or columns this app does not know, only warn in the job result (do not block). **\[Decision\]** default: warn only.
4. Add the audit entry `DB_RESTORE_STAGED` (who, file id, size, date). Do not log secrets.
5. Show the checks in the job step text: "Checking the database... ok".

**Tests:** empty SQLite file -> rejected; SQLite file with unrelated tables -> rejected; real backup -> accepted; a backup with zero users -> rejected.

---

## P5. The restore safety copy can lose data (Low-Medium)

**Files:** `sqlite/SqliteDataDirMigration.java` (`applyPendingRestore`), `service/BackupRestoreService.java`.

**What is wrong:**

1. `applyPendingRestore` copies the live DB to `lumipos.db.pre-restore` with `REPLACE_EXISTING`. A second restore overwrites the first safety copy. The original data is gone if the second restore was also wrong.
2. It copies only the main file. If a `lumipos.db-wal` file exists (for example after a crash), recent saved data is only inside it. The code then deletes the live `-wal` file. That data is lost and the safety copy does not have it.

**Fix steps:**

1. Name the safety copy with a time stamp: `lumipos.db.pre-restore-<yyyyMMddHHmmss>`.
2. Before copying, if `lumipos.db-wal` exists, open the live DB with a plain JDBC connection and run `PRAGMA wal_checkpoint(TRUNCATE)`. This moves the log into the main file. (This runs at startup before the Spring pool exists, so no other connection is open.)
3. If the checkpoint fails, copy the `-wal` and `-shm` files too, with the same time stamp suffix, so nothing is lost. Log a warning.
4. Verify the safety copy opens and passes `quick_check`. If it does not, STOP the restore and keep the live DB.
5. Keep the newest 3 safety copies. Delete older ones (setting `sqlite.restoreBackup.keep`, default 3). Never delete the one just made.
6. Log the full path of the safety copy at WARN level.

**Tests (extend the existing restore test):**

- Two restores in a row leave two safety copies with different names.
- A live DB in WAL mode with rows not yet checkpointed: the safety copy contains those rows.
- A broken safety copy stops the restore and leaves the live DB untouched.
- A 4th restore removes the oldest copy only.

---

## P6. Old pre-upgrade backup files pile up (Low)

**File:** `sqlite/SqliteSchemaMigrator.java` (`backUpDatabaseFile`).

**What is wrong:** Each upgrade that rebuilds tables makes `lumipos.db.pre-upgrade-<time>.bak` and nothing deletes it. The data folder grows by the size of the database every time.

**Fix steps:**

1. After a successful migration in the same start, list files named `<db>.pre-upgrade-*.bak` in the folder, sort by name (time stamp), keep the newest `sqlite.upgradeBackup.keep` (default 3), delete the rest.
2. Never delete the file made in this start. If any step fails, do not delete anything.
3. Log each deleted file name at INFO level.
4. Do not touch other files (`.pre-restore*`, user backups).

**Tests:** 5 old backup files + 1 new: after migration 3 remain (the new one + 2 newest old). Failed migration deletes nothing.

---

## P7. Receipt balance ignores returns (Low-Medium)

**Files:** `service/ReceiptService.java` (`getReceiptByInvoiceNumber`, `getReceiptById`, `getArPaymentReceipt`), `dto/response/ReceiptDto.java` and `ArPaymentReceiptDto.java` (find the exact paths), print templates in the frontend.

**What is wrong:**

1. The sale receipt shows `balanceDue = total - amountPaid`. For a credit invoice with a return this is too high, because the AR screens use `total - paid - amountReturned`. The customer sees two different numbers.
2. The AR payment receipt shows `balanceAfter = total - amountPaid` using the CURRENT paid amount. A reprint of an old payment receipt shows today's balance, not the balance right after that payment. It also ignores returns.
3. The AR payment receipt does not check if the invoice was voided. The sale receipt does check.

**Fix steps:**

1. Add optional fields to the receipt response: `amountReturned` (default 0). Print it on the receipt only when it is above 0 as a line "Returned".
2. Change `balanceDue` to `max(0, total - amountPaid - amountReturned)`. This is the correct meaning, so it is a fix and not a contract change.
3. In `getArPaymentReceipt` compute the balance at that payment time:
   - `paymentsAfter` = sum of AR payments of the same invoice with id greater than this payment id;
   - `paidAtThatTime = sale.amountPaid - paymentsAfter`;
   - `balanceAfter = max(0, total - amountReturned - paidAtThatTime)`. (Returns after that payment are included. This is OK; say "balance now, after returns" only if you want. **\[Decision\]** default: include returns.)
4. If the invoice is voided, throw `IllegalStateException("Invoice was voided: <number>")` like the sale receipt does. Show a clear message in the UI.
5. Add a repository method for the sum, for example `sumAmountByInvoiceIdAndIdGreaterThan`. It must exclude reversed payments (`reversed_at` is not null) if that column exists.

**Tests:** credit invoice 100, paid 30, return 20: receipt balance = 50. Two AR payments (30 then 20): reprint of the first shows balance 50 (before the second), reprint of the second shows 30 (all numbers without returns); with a return of 10 add the effect. Voided invoice: payment receipt rejected.

---

## Already done in earlier PRs (do not redo)

M1 enum check rebuild, M2 manager approval service (except P1), M3 discount limit on the computed discount, M4 line endings, M5 multi-line debt split, T1 period-close settings are enforced, T6 `auth.token.requireVersionClaim` is read by `JwtUtil`, plus everything listed in Fix Spec 6.

## Final checks (run after all items)

- Run all existing tests and the new ones.
- Lock test by hand: 6 wrong manager passwords at the till, then log in as that manager. Login must work.
- Edit a price list (keep some products, add one, remove one). Save must work on SQLite and H2.
- Restore a real backup, then a wrong SQLite file. The wrong one must be rejected. After two restores two safety copies must exist.
- Reprint a receipt for a credit invoice that has a return and an AR payment. Numbers match the AR screen.
- Confirm no Android-facing contract changed.

## Report format per item

One short block: what changed, files, tests added, any difference from this spec, setting keys used.
