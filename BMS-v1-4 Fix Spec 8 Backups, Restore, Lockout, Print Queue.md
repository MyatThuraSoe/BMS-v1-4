# BMS-v1-4 Fix Spec 8: Backups, Restore, Lockout, Print Queue

Based on commit `f3a62ee` (PR #21). Read the code only; nothing was run. Written in simple words for an AI agent.

## How to work

- Do NOT touch the Android app. Do not change old API shapes. New request fields must be optional. New response fields are OK.
- One item = one pull request. Write the failing test first. Then fix. Then run all tests.
- Schema changes: SQLite uses `db/sqlite/schema.sql` + `SqliteSchemaMigrator`. H2 and MySQL use `AccountingSchemaMigration`. Never add a NOT NULL column without a default.
- Tag **\[Decision\]** = owner choice. Use the default given and put it in a setting.
- Keep LF line endings (the repo has a check).

## Order of work

1. Q5 (automatic Drive backup does not save everything)
2. Q1 (cannot restore over a damaged database)
3. Q2, Q3 (login lock and error handler)
4. Q9 (print queue)
5. Q6, Q7, Q8 (backup schedule, retention, encryption)
6. Q4, Q10, Q11 (small fixes)
7. Final checks at the end

---

## Q5. Automatic Drive backup saves a partial copy (High)

**Files:** `service/BackupService.java` (`executeGoogleDriveBackup`), `service/BackupScheduler.java`, `controller/BackupController.java` (`/run-now`), `service/BackupRestoreService.java` (`runDatabaseSnapshot`, `createConsistentSnapshot`), `service/DataExportService.java`, `service/DataImportService.java`, `entity/BackupSetting.java`, `frontend/src/pages/Backup*.jsx`.

**What is wrong:**

1. The scheduler and the "Run now" button both call `executeGoogleDriveBackup`. It uploads `LumiPOS_Backup_<date>.json` made by `DataExportService.exportAllAsJson()`.
2. That JSON holds only: categories, products, customers, suppliers, sales, purchases, saleReturns, expenses, cashShifts, users, shopInfo, systemSettings, arPayments, receiptCustomizations, orders, orderSequences.
3. It does NOT hold (unless nested inside the lines above, check each): `InventoryLedger`, `ProfitLedger`, `PeriodClose`, `GlAccount`, `GlMapping`, `Location`, `LocationStock`, `StockMovement`, `StockTransfer(+Items)`, `CountSheet(+Items)`, `CashDrawer`, `CashDrawerSession`, `CashDrawerMovement`, `PriceList(+Items)`, `Promotion(+Tiers)`, `ProductPriceHistory`, `PurchasePayment`, `PurchaseExpense`, `AuditLog`, and the number sequences `InvoiceSequence`, `PurchaseSequence`, `CustomerSequence`, `TransferSequence`.
4. `DataImportService` refuses to import business rows into a database where ledger reads are active. So a JSON backup of a real shop can only be restored into a fresh database, and then the accounting history, closed periods, warehouse stock, price lists, promotions and invoice sequences are gone. Missing invoice sequences can make new invoice numbers repeat.
5. The complete backup (`VACUUM INTO` snapshot, file `LumiPOS_DB_<time>.db`) exists, but only behind the manual button `POST /api/backups/drive/snapshot`. Nothing runs it on a schedule.
6. The JSON export also loads every table with `findAll()` in memory (large shops can run out of memory).

**Fix steps:**

1. Add setting `backup.format` with values `DB`, `JSON`, `BOTH`. **\[Decision\]** default `DB` when the app runs on SQLite, `JSON` on other databases (they have no `VACUUM INTO`).
2. Change `executeGoogleDriveBackup(...)`:
   - `DB` (SQLite): call the same code as `runDatabaseSnapshot` (create snapshot with `VACUUM INTO`, upload with name `LumiPOS_DB_<yyyy-MM-dd_HHmmss>.db`, delete the temp file in `finally`).
   - `JSON`: current behaviour.
   - `BOTH`: do `DB` first, then `JSON`. If one fails, report the failure but keep the other.
   - Put the shared snapshot code in one place (for example `DatabaseSnapshotService`) and call it from both `BackupRestoreService` and `BackupService`. Do not copy the code.
3. After the upload, compare the uploaded file with the local one: ask Drive for `size` and `md5Checksum` (`files().get(id).setFields("id,size,md5Checksum")`) and compare to the local size and MD5. If different: delete the bad Drive file, throw an error, mark the backup FAILED.
4. Make the JSON export complete. In `DataExportService` add these sections: `invoiceSequences`, `purchaseSequences`, `customerSequences`, `transferSequences`, `locations`, `locationStock`, `stockMovements`, `stockTransfers`, `priceLists`, `promotions`, `productPriceHistory`, `purchasePayments`, `cashDrawers`, `cashDrawerSessions`, `cashDrawerMovements`, `countSheets`, `glAccounts`, `glMappings`, `periodCloses`, `inventoryLedger`, `profitLedger`. Use the repositories. For tables that are not nested in a parent entity export flat rows (ids instead of full objects).
5. Make `DataImportService` read the same new sections (all optional, so old backups still import). In `REPLACE_ALL` mode import them in foreign-key order. Keep the ledger guard but allow `REPLACE_ALL` when the file contains the ledger sections (then ledger rows are restored as they were). **\[Decision\]** default: allow only when the file has `profitLedger` and `inventoryLedger`.
6. In `DataExportService` stop loading everything into one list: use pageable reads (`findAll(PageRequest.of(i, 500))`) and write with the Jackson streaming `JsonGenerator` straight to the temp file. Keep the output format the same.
7. UI: show the backup kind on each row of the restore list (`Full database` or `Data export`), and show a plain warning when the last automatic backup was only `JSON`.

**Tests:**

- `backup.format=DB`: the scheduled run uploads a `.db` file (use a fake `GoogleDriveService`), the file opens and has the same row counts as the live database, and a bad checksum makes the job FAILED and deletes the file.
- `BOTH`: two uploads; one failure does not cancel the other.
- Export then import round trip on a database with sales, returns, ledgers, a closed period, locations, price lists, promotions and sequences: every table has the same row count and the next invoice number is greater than the last one.
- Old JSON files (without the new sections) still import.
- Export of 5000 sales does not load all rows at once (check with a counting repository stub).

---

## Q1. Restore is blocked when the live database is damaged (Medium-High)

**Files:** `sqlite/SqliteDataDirMigration.java` (`applyPendingRestore`, `verify`).

**What is wrong:** Before a restore, the live database is copied to a safety file. Then `verify(null, safety)` runs `PRAGMA quick_check` on that copy. If the live database is damaged (the main reason to restore!), the copy fails the check and the code throws `LumiPOS will not restore over this database`. The app does not start, and the shop must delete files by hand.

**Fix steps:**

1. Before copying, run `quick_check` on the LIVE file with a plain JDBC connection. Call the result `liveHealthy`. If the file cannot be opened at all, `liveHealthy = false`.
2. Copy to the safety file as now.
3. If `liveHealthy` is true and the copy fails verification: stop and keep the live file (current behaviour).
4. If `liveHealthy` is false: do NOT stop. Rename the copy to `lumipos.db.corrupt-<timestamp>`, log a WARN with the path, and continue with the restore. Do not run the safety-copy pruning on `.corrupt-` files and never delete them automatically. **\[Decision\]** keep at most 3 `.corrupt-` files (setting `sqlite.corruptBackup.keep`, default 3); delete the oldest beyond that.
5. After the restore, show a notice in the app: "The old database was damaged and was saved as \<file name>." (use the existing `restore-pending` status endpoint or a banner).

**Tests:** live file is garbage bytes: restore succeeds, the corrupt copy is kept, the new database is in place. Live file healthy but copy broken (force an error): restore stops and the live file is untouched. Live file missing: restore succeeds with no copy.

---

## Q2. Anyone on the network can lock out the admin; the lock map grows forever (Medium)

**Files:** `service/AuthService.java` (`authenticateUser`, `attemptKey`), `service/LoginAttemptTracker.java`, `controller/AuthController.java`.

**What is wrong:**

1. Lockout is counted by username only. Five wrong logins for `admin` from any computer lock `admin` for 15 minutes. The unlock button is admin-only, so with one admin only an app restart fixes it.
2. The tracker also records failures for usernames that do not exist and never removes old records. Random usernames make the map grow without limit.

**Fix steps:**

1. Add the client address: in `AuthController.login` read `request.getRemoteAddr()` (do not trust `X-Forwarded-For` unless the app sits behind a known proxy; add setting `auth.trustForwardedFor`, default false) and pass it to `authenticateUser`.
2. Count two keys: `login:<username>` and `ip:<address>`. Lock the username only when failures come from the SAME address (`login:<username>:<ip>`). Lock the address when it has too many failures across any usernames (for example 20 in 15 minutes, setting `auth.ipMaxFailures`).
3. A locked address gets the same generic message as a wrong password. A different computer can still log in as `admin` while another address is being locked.
4. Only record a failure for a username when that user exists. For unknown usernames count only the address key.
5. Memory: in `LoginAttemptTracker` remove an entry when its window and lock have ended. Add a scheduled cleanup every 5 minutes (`@Scheduled`) that deletes expired records, and cap the map at 10000 keys (when full, remove the oldest expired ones first; if still full, reject new keys by locking the address key only).
6. Keep the admin unlock endpoint. Make it also clear the address keys of that user's last failed address if you store it (optional).

**Tests:** 5 wrong logins for `admin` from address A: A is locked for `admin`; address B can still log in as `admin`. 100 random unknown usernames from one address lock that address, and the map size stays under the cap. After the time passes the cleanup removes entries (use a fake clock). A good login clears that username-address key.

---

## Q3. Every IllegalStateException is returned to the client (Medium-Low)

**Files:** `exception/GlobalExceptionHandler.java`, `service/ReceiptService.java` (`refuseVoided`), any other place that throws `IllegalStateException` on purpose.

**What is wrong:** PR #21 added `@ExceptionHandler(IllegalStateException.class)` that returns the exception message with HTTP 409. Spring, Hibernate and the JDK also throw `IllegalStateException` for real server faults (for example a closed EntityManager). Now their internal text goes to the browser and the fault looks like a conflict instead of a 500.

**Fix steps:**

1. Create `class StateConflictException extends RuntimeException` (package `com.bms.exception`). Message is for the user.
2. Change the places that rely on the new handler (`ReceiptService.refuseVoided`, and every `throw new IllegalStateException(` in `service/` that is a user-facing refusal; search the project) to throw `StateConflictException`.
3. Change the handler to `@ExceptionHandler(StateConflictException.class)` only. Remove the `IllegalStateException` handler. Real `IllegalStateException` goes back to the generic 500 handler with the generic message and a log line.
4. Keep the HTTP 409 status for `StateConflictException`.

**Tests:** voided receipt still returns 409 with the message. A thrown plain `IllegalStateException("secret detail")` returns 500 with the generic message and does not contain `secret detail`.

---

## Q9. Any cashier can steal or finish print jobs (Medium-Low)

**Files:** `controller/CounterPrintController.java`, `service/CounterPrintService.java`, `frontend/src/api/services/receipts.js`, Electron start code.

**What is wrong:**

1. `/api/counter-print/receipt-jobs/next` and `/receipt-jobs/{jobId}/complete` are open to ADMIN, MANAGER and CASHIER. The comment says only the Electron client on the server should use them. A browser on another PC can poll the queue and take every job, so receipts never print at the counter.
2. A job taken but never completed (Electron crashed) stays `PRINTING` forever and is never printed again. Queued jobs are lost when the server restarts.
3. `jobStatuses` is never cleaned.
4. `enqueueReceipt` does not check that the invoice exists or is not voided. The error only shows later when the job fails.

**Fix steps:**

1. Only the print agent may claim and complete jobs. Choose: **\[Decision\]** default = request must come from the server itself (`request.getRemoteAddr()` is a loopback address: `127.0.0.1` or `::1`). Add setting `print.agent.allowRemote` (default false) for shops that run the print agent on another PC with an `X-Print-Agent-Token` header that matches setting `print.agent.token` (generated random value on first start).
2. Add a claim time to each job. In `claimNextReceipt` first requeue jobs that were claimed more than 60 seconds ago and not completed, at most 2 times, then mark them `FAILED`.
3. Remove entries from `jobStatuses` that are finished and older than 1 hour (cleanup inside `enqueueReceipt` is enough, no new scheduler).
4. In `enqueueReceipt` check that the invoice exists and is not voided (call `receiptService.getReceiptByInvoiceNumber` or a cheap existence check) and throw the normal error right away.
5. Add `GET /api/counter-print/receipt-jobs/{jobId}` (same roles as printing) that returns the status, so the POS can show "printing / failed". Optional but cheap.
6. Persist is NOT needed. Document that queued jobs do not survive a restart.

**Tests:** a request from a non-loopback address to `/receipt-jobs/next` gets 403; from loopback it works. A claimed job not completed in 60 seconds returns to the queue once, then fails after two retries. Unknown or voided invoice is refused at enqueue. Old finished statuses are removed.

---

## Q6. Backup times drift later every day (Medium-Low)

**File:** `service/BackupService.java` (`calculateNextBackupDate`, `executeGoogleDriveBackup`), `service/BackupScheduler.java`.

**What is wrong:** After each successful backup the next time is `now() + period`. The scheduler only checks every 10 minutes, and the run takes time. So a daily backup set for 02:00 slides by the delay each day (minutes per day). After weeks it can run during opening hours.

**Fix steps:**

1. Add optional field `scheduledTime` (LocalTime, nullable) to `BackupSetting` (migration: nullable column). **\[Decision\]** if null, use the time when the setting was first enabled as the anchor.
2. Compute the next date from the PLANNED time, not from the finish time: `next = previousPlanned + period`. If that is still in the past (server was off), move forward by whole periods until it is in the future, so one missed run does not cause many catch-up runs. Then run once.
3. For `DAILY`/`WEEKLY`/`MONTHLY` keep the same time of day in the shop time zone (`ShopTimeService`).
4. UI: add a time field to the backup settings page (default 02:00).

**Tests:** daily, planned 02:00, run finished at 02:07: next is tomorrow 02:00. Server off for 3 days: one backup now, next is the next 02:00 in the future. Monthly on the 31st handles short months (use `plusMonths` from the planned date, not from the last result).

---

## Q7. Drive backups are never deleted and the restore list shows only 50 (Low-Medium)

**Files:** `service/GoogleDriveService.java` (`listBackupFiles`, upload methods), `service/BackupService.java`, `entity/BackupSetting.java`.

**What is wrong:**

1. Nothing deletes old backups in Drive, so the folder grows until the account is full and uploads fail.
2. `listBackupFiles` reads one page of 50. Older backups are invisible in the restore list.
3. The JSON name is `LumiPOS_Backup_<date>.json`, so two backups on the same day get the same name.

**Fix steps:**

1. Name JSON files `LumiPOS_Backup_<yyyy-MM-dd_HHmmss>.json`.
2. Add `retentionCount` (int, default 30, minimum 3) to `BackupSetting` (migration, default value 30). After a successful upload delete the oldest files in the backup folder beyond that count, counting `.db` and `.json` files separately. Only delete files whose name starts with `LumiPOS_Backup_` or `LumiPOS_DB_` and that are in the app's own folder. Never delete the file that was just uploaded. Do not stop the backup if the delete fails (log it).
3. Add paging to `listBackupFiles`: follow `nextPageToken` until done (or until 500 files), still sorted by `modifiedTime desc`.
4. Make the retention number editable on the backup settings page.

**Tests:** with 35 fake backups and retention 30 the 5 oldest are deleted and the new one is kept. Files with other names are never deleted. A listing of 120 files returns all 120 in the right order.

---

## Q8. Backups on Drive are not encrypted (Low-Medium)

**Files:** `service/BackupService.java`, `service/BackupRestoreService.java`, `service/DataExportService.java`, backup settings UI.

**What is wrong:** The JSON backup holds customer details, product images and every user's password hash. The `.db` snapshot holds the same plus everything else. Both go to Google Drive as plain files. If the Google account is taken over, all shop data is exposed.

**Fix steps:**

1. Add setting `backup.encryption.enabled` (default false) and a passphrase that is typed in the settings page and NOT stored in the database. **\[Decision\]** For automatic backups the passphrase must be stored; store it only in an OS-level secret store if the Electron shell offers one, otherwise keep encryption for manual backups only and say so on the page.
2. Encrypt with AES-256-GCM. Key from the passphrase with PBKDF2-HMAC-SHA256 (at least 210000 iterations) and a random 16-byte salt. File layout: magic bytes `LUMIENC1`, salt, IV, ciphertext+tag. Name the file with the extra suffix `.enc`.
3. On restore, detect `.enc`, ask for the passphrase, decrypt to the temp file, then run the normal checks. A wrong passphrase gives the message `Wrong passphrase or damaged file`.
4. Never log the passphrase or the key.

**Tests:** encrypt then decrypt returns identical bytes; one flipped byte fails; wrong passphrase fails; the existing unencrypted backups still restore.

---

## Q4. Upgrade backup cleanup can delete the newest file (Low)

**File:** `sqlite/SqliteSchemaMigrator.java` (`pruneOldUpgradeBackups`, `backUpDatabaseFile`).

**What is wrong:** Files are sorted by name. A same-minute second copy is named `...<minute>-2.bak`. In text order `-2.bak` comes BEFORE `.bak` (because `-` sorts before `.`), so the newer file looks older and can be deleted first.

**Fix steps:** sort by the file's last modified time (`Files.getLastModifiedTime`), oldest first, and use name only to break ties. Keep the rule "never delete the file made in this start". Same fix for `pruneSafetyCopies` in `SqliteDataDirMigration` (it sorts by name too; the `-2` form exists there as well).

**Tests:** create `x.bak` (older mtime) and `x-2.bak` (newer mtime) plus two more; with keep = 2 the two newest by time stay.

---

## Q10. Unused code and small hygiene (Low)

**Files:** `service/GoogleDriveAuthService.java`, `service/GoogleDriveService.java` (`getAuthorizationUrl`, `handleCallback`), `controller/BackupController.java` (`pendingOAuthStates`).

**What to do:**

1. `GoogleDriveAuthService` is not used by any other class and repeats the OAuth code (without the state check). Delete it. Check that `google.oauth.*` properties still have defaults so the app starts without them (use `${google.oauth.client-id:}`).
2. `pendingOAuthStates` keeps states that were never used. Remove expired entries whenever a new state is created.
3. If `GoogleDriveService.getAuthorizationUrl` is not used, delete it too. Only keep what `BackupController` calls.

**Tests:** the app context starts with no Google properties set. Creating 10 states and waiting past the TTL leaves 0 old states after the next creation.

---

## Q11. Drive search text is not escaped (Low)

**File:** `service/GoogleDriveService.java` (`getOrCreateBackupFolderId`, `listBackupFiles`).

**What is wrong:** The Drive query is built with string joins. A folder id or name with a quote `'` breaks the query.

**Fix steps:** add a helper `escapeQuery(String)` that replaces `\` with `\\` and `'` with `\'`. Use it for every value placed in a query. The folder id also must match `^[A-Za-z0-9_-]+$`; if not, throw a clear error.

**Tests:** a name with a quote does not break the query; an invalid folder id is rejected.

---

## Reviewed and OK (do not change)

- OAuth callback already has a random one-time `state` with a 5 minute limit, and tokens are masked in the settings response. Disconnect clears both tokens.
- `downloadFile` writes to a `.part` file, checks the size, and renames at the end.
- `/api/sync/count-sheet` is idempotent by key; the server-side limits from Fix Spec 2 (F19, F20) cover it.

## Final checks (run after all items)

- Run all existing tests and the new ones.
- Full round trip: backup to a fake Drive (`BOTH`), restore the `.db`, then the JSON into a fresh database. Compare row counts of all tables, closed periods and the next invoice number.
- Put garbage bytes in the live database file, stage a restore, restart: the shop comes back with the backup and the damaged file is kept.
- Try 5 wrong `admin` logins from one address. Log in as `admin` from another address.
- Open the receipt queue from a non-local address: refused. Kill the print agent after claiming a job: the job is retried once.
- Confirm no Android-facing contract changed.

## Report format per item

One short block: what changed, files, tests added, any difference from this spec, setting keys used.
