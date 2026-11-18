# BMS-v1-4 Fix Spec 9: Encryption, Schedules, Merge Import

Based on commit `f7a09db` (PR #22). Read the code only; nothing was run. Written in simple words for an AI agent.

## How to work

- Do NOT touch the Android app. Do not change old API shapes. New request fields must be optional. New response fields are OK.
- One item = one pull request. Write the failing test first. Then fix. Then run all tests.
- Schema changes: SQLite uses `db/sqlite/schema.sql` + `SqliteSchemaMigrator`. H2 and MySQL use `AccountingSchemaMigration`. Never add a NOT NULL column without a default.
- Tag **\[Decision\]** = owner choice. Use the default given and put it in a setting.
- Keep LF line endings (the repo has a check).
- Old backups must stay restorable. Never break reading of an older file format.

## Order of work

1. V1 (big encrypted backups can run out of memory)
2. V4 (merge import can mix two shops)
3. V2, V3, V8 (backup schedule)
4. V5, V6, V7 (small fixes)
5. Final checks at the end

---

## V1. Restoring a large encrypted backup can run out of memory (Medium)

**Files:** `service/BackupEncryption.java`, `service/BackupRestoreService.java`, `service/DatabaseSnapshotService.java`, `service/BackupService.java`, `test/.../BackupEncryptionTest.java`.

**What is wrong:**

1. `encrypt` and `decrypt` read the file in 64 KB pieces (`seal`). That sounds safe. But Java's `AES/GCM/NoPadding` in DECRYPT mode does not release any plain bytes until the final tag check in `doFinal()`. So `cipher.update(...)` keeps the whole ciphertext in memory. A database of a few hundred MB can fill the heap of the desktop app and crash the restore.
2. The file header holds only `LUMIENC1`, salt and IV. The PBKDF2 iteration count (210000) is a constant in the code. If the number is raised later, every old backup stops opening.

**Fix steps:**

1. Add a new format `LUMIENC2`. Keep reading `LUMIENC1` forever (decrypt code stays). New backups are written as `LUMIENC2`.
2. `LUMIENC2` layout:
   - magic `LUMIENC2` (8 bytes)
   - 1 byte: PBKDF2 version (1 = HMAC-SHA256)
   - 4 bytes: iteration count (big endian)
   - 16 bytes: salt
   - 12 bytes: base IV (random)
   - 4 bytes: chunk size in bytes (default 1 MiB = 1048576)
   - then chunks.
3. Each chunk is sealed separately with AES-256-GCM:
   - IV for chunk `i` = base IV with its last 4 bytes XOR the chunk number `i` (unsigned int; stop with an error at 2^32 chunks).
   - Additional data (AAD) = the whole header bytes + 1 byte `lastChunkFlag` (1 for the final chunk, else 0). This stops reordering and cutting the end off the file.
   - A chunk on disk is `ciphertext + 16 byte tag`. The final chunk may be shorter. A file with zero bytes still writes one empty final chunk.
4. `decrypt` reads chunk by chunk (`chunkSize + 16` bytes at a time), checks each tag, writes the plain bytes to the temp file, and stops at the first bad tag (delete the temp file, throw `StateConflictException(WRONG_PASSPHRASE_MESSAGE)`). If the file ends without a chunk that has `lastChunkFlag = 1`, throw the same message (cut file).
5. Use the iteration count and version from the header when deriving the key. Reject an iteration count below 100000 or above 5000000 with a clear error (protects against a bad header).
6. `looksEncrypted` must accept both `LUMIENC1` and `LUMIENC2`.
7. Keep `ENCRYPTED_SUFFIX = ".enc"`. Keep all messages the same.
8. Memory test: encrypt and decrypt a 300 MB generated file with the test JVM heap limited to 128 MB (`-Xmx128m` in the test fork) and show it passes.

**Tests:**

- Round trip for sizes 0, 1, 1 MiB - 1, 1 MiB, 1 MiB + 1 and 5 MiB.
- One flipped byte in the middle, in the header and in the last chunk: each fails with the wrong-passphrase message.
- Two whole chunks swapped: fails.
- File cut after a full chunk: fails.
- A file made with the old `LUMIENC1` code (keep a small fixture file in test resources) still decrypts.
- Iteration count in the header is used (make a test file with a lower count and check it opens).

---

## V4. Merge import can mix data of two different shops (Medium-Low)

**Files:** `service/DataImportService.java` (`importAll`, `keepOnlyNew`, `MERGE` branch), `service/DataExportService.java`, `entity/ShopInfo.java` (or system settings), `controller/DataImportController.java`, import page in the frontend.

**What is wrong:** In `MERGE` mode the code removes every row whose id already exists in this database (`keepOnlyNew(list, repository::findAll)`). It looks only at ids. If the file comes from ANOTHER shop (or another install), ids collide: rows are skipped, and child rows (sale items, payments, ledger rows) that point to those ids can attach to the wrong parent. The result is mixed or wrong books and no error.

**Fix steps:**

1. Give every install an id. Add a system setting `install.id` (UUID) created once at first start (same pattern as the print agent token: create in `@PostConstruct` if blank). It is never changed.
2. Write it into every export: a top-level field `installId` next to the existing version fields. Old files have no `installId`.
3. In `MERGE` mode:
   - same `installId`: merge as now.
   - different or missing `installId`: refuse with `This file came from another shop or an older export. Use Replace everything, or confirm that it is the same shop.` unless the request has the new optional field `confirmSameShop = true`.
   - Even with the confirmation, if ANY id in a table collides with an existing row, stop and roll back with a list of the first 10 collisions (table and id). Do not skip silently. (Skipping is only correct when the rows are identical, so also allow skip when the existing row has the same natural key: invoice number, product SKU, customer code.)
4. `REPLACE_ALL` is not changed.
5. Frontend: on the import page show a clear warning, and send `confirmSameShop` only after the user ticks "This file is from this shop".
6. Write `install.id` into the JSON backup too, and when restoring a backup into a fresh install with `REPLACE_ALL`, copy the file's `installId` into the new database (so later merges of the same shop work).

**Tests:** merge of a file from the same install works; a file with a different `installId` is refused; a file without `installId` is refused unless confirmed; confirmed merge with a colliding id rolls back and lists the collision; replace-all restores the `installId`.

---

## V2. Custom cron backups repeat after downtime (Low-Medium)

**File:** `service/BackupService.java` (`calculateNextBackupDate`).

**What is wrong:** For frequency `CUSTOM` the next date is `CronExpression.next(from)` where `from` is the OLD planned time. If the app was off for 3 days on a daily cron, `next` is still in the past. The scheduler runs a backup, sets another past time, and runs again at the next poll. You get several extra backups in a row until it catches up.

**Fix steps:** use `from = max(from, shopTimeService.now())` before calling `next`. Then the next time is always in the future. Make sure the result is strictly after `now`; if not, call `next` again with the result as `from` (max 5 times) and then fall back to `now + 1 day`.

**Tests:** daily cron, planned time 3 days ago, now: one run happens and the next date is in the future (not in the past).

---

## V3. Monthly backups slide to the 28th (Low)

**File:** `service/BackupService.java` (`plusPeriods`, `calculateNextBackupDate`), `entity/BackupSetting.java`.

**What is wrong:** `MONTHLY` uses `slot.plusMonths(1)` from the previous planned date. Jan 31 becomes Feb 28. The next run starts from Feb 28 and gives Mar 28, Apr 28, and so on. The day of month never goes back to 31.

**Fix steps:**

1. Add nullable column `anchor_day_of_month` (INTEGER) to `BackupSetting` (migration; nullable; no default needed).
2. When the schedule is saved or enabled with `MONTHLY`, set the anchor to the day of the first planned date. For old rows with null anchor, use the day of `nextBackupDate`.
3. For monthly: `next = firstOfNextMonth.withDayOfMonth(min(anchor, lengthOfThatMonth))` and keep the time of day.
4. Do the same for `YEARLY` (Feb 29 anchor falls back to Feb 28 in normal years and comes back in leap years).

**Tests:** anchor 31: Jan 31, Feb 28, Mar 31, Apr 30, May 31. Anchor 29 in a leap year.

---

## V8. A wrong cron text silently becomes a daily backup (Low)

**Files:** `service/BackupService.java` (`calculateNextBackupDate`, the CUSTOM branch), `controller/BackupController.java` (settings save), settings page.

**What is wrong:** If the custom cron text cannot be parsed, the code catches the error and returns `from.plusDays(1)`. The shop thinks it has the schedule it typed but gets a daily backup.

**Fix steps:**

1. When the settings are saved with `CUSTOM`, parse the text with `CronExpression.parse`. If it fails, return HTTP 400 with `That schedule text is not valid`. Also reject a cron that fires more often than once per 15 minutes (compare two `next` times).
2. In `calculateNextBackupDate` keep the old fallback only for rows that were saved before this fix, and write a WARN log and set `lastErrorMessage` to `Custom schedule is not valid; using daily`.

**Tests:** invalid text is rejected on save; a valid text is saved; a too-frequent cron is rejected.

---

## V5. Print agent token is easy to read; loopback list misses one form (Low)

**Files:** `service/PrintAgentAccess.java`, `controller/SystemSettingController.java`, `service/DataExportService.java`, settings DTO/response code.

**What is wrong:**

1. `print.agent.token` is a normal system setting. Managers can read it with `GET /api/settings/key/print.agent.token` (and in the settings list). It is also written into the JSON backup, so it goes to Google Drive.
2. `LOOPBACK_ADDRESSES` has `127.0.0.1`, `::1`, `0:0:0:0:0:0:0:1` but not `::ffff:127.0.0.1` (IPv4 inside IPv6), which some Java stacks report. Printing from the same PC can then be refused.

**Fix steps:**

1. Add a list of secret setting keys in one place (for example `SystemSettingService.SECRET_KEYS = Set.of("print.agent.token")`). When returning a setting (single or list), if the key is in the list return the value masked (`********`) unless the caller is ADMIN. For admins also mask by default and add `?reveal=true` (ADMIN only, audit entry `SETTING_REVEALED`).
2. In `DataExportService` skip keys in `SECRET_KEYS` when exporting system settings. In `DataImportService` never import them (a restored shop makes its own token).
3. In `PrintAgentAccess.isLoopback` normalize the address first: trim, lower-case, and strip a leading `::ffff:`. Then test against `127.0.0.1`, `::1`, `0:0:0:0:0:0:0:1`. Also accept any address in `127.0.0.0/8` (use `InetAddress.getByName(address).isLoopbackAddress()` after the normalize step, inside try/catch; a failed parse means not loopback).

**Tests:** manager gets a masked token; admin with `reveal=true` gets the real one and an audit row exists; export JSON does not contain the token; `::ffff:127.0.0.1` and `127.0.0.5` count as local; `192.168.1.20` does not.

---

## V6. Retention has no "keep forever" and no clear label (Low)

**Files:** `service/GoogleDriveService.java` (`filesToPrune`, `backupKindOf`), `service/BackupService.java`, `frontend/src/components/DriveRestoreCard.jsx`, `frontend/src/pages/BackupSettings.jsx`.

**What is wrong:**

1. Retention counts every backup of a kind together, newest first. A manual backup (for example an encrypted one the shop made before a big change) is deleted after enough nightly backups. There is no way to protect one.
2. With encryption switched on, nightly (automatic) backups are still plain files by design. The page does not show which backup is encrypted and which is not, so the shop may think all of them are sealed.

**Fix steps:**

1. Name manual backups with a suffix in the file name: `..._manual` before the extension (for example `LumiPOS_DB_2026-11-17_142000_manual.db.enc`). Retention must NOT delete files with `_manual` in the name. **\[Decision\]** default: manual backups are kept until the shop deletes them.
2. In the restore list show two small tags per row: `Automatic` or `Manual`, and `Encrypted` or `Not encrypted` (from the `.enc` suffix).
3. On the backup settings page, when encryption is switched on, show one plain line: "Automatic backups are not encrypted. Only backups you run by hand with a passphrase are encrypted."
4. Add a button "Delete" on manual rows (ADMIN only, confirm first). Use the existing Drive delete code (`deleteQuietly`) and check the file belongs to the app folder.

**Tests:** `filesToPrune` never returns a `_manual` file; the counts of automatic files stay the same as before; the list response has the new tags.

---

## V7. Date range uses the server clock zone for old date types (Low)

**File:** `service/BackupService.java` (`isDateInRange`).

**What is wrong:** For a `java.util.Date` the code uses `ZoneId.systemDefault()`. The rest of the app uses the shop time zone (`ShopTimeService`). When the two differ a record near midnight is put on the wrong day and can be missing from an export range.

**Fix steps:** inject `ShopTimeService`, use `shopTimeService.zone()` (add a `getZone()` method if it does not exist) instead of `ZoneId.systemDefault()`. Search the whole project for `ZoneId.systemDefault()` and `new Date()` in `service/` and fix each one the same way, unless it is inside `ShopTimeService` itself.

**Tests:** a `Date` at 23:30 shop time on the last day of the range is inside the range when the JVM zone is UTC and the shop zone is `Asia/Yangon`.

---

## Not reviewed yet (check before the next audit)

- `SqliteDataDirMigration.applyPendingRestore`: the new path for a damaged live database (Fix Spec 8, Q1). Tests exist (`SqliteDataDirMigrationTest`), I did not read the code.
- `DataExportService` and `DataImportService` full round trip on a real, large database (only reviewed the headings and guard rules).
- Frontend pages `BackupSettings.jsx` and `DriveRestoreCard.jsx` (new fields, passphrase handling in the browser).

## Already done in earlier PRs (do not redo)

Everything in Fix Spec 8 (format dispatch, snapshot upload with checksum, retention, login address lock, `StateConflictException`, print agent access, backup schedule anchor, encryption, removal of the unused Google auth class), plus all items of Fix Specs 1 to 7.

## Final checks (run after all items)

- Run all existing tests and the new ones.
- Encrypt and decrypt a 300 MB file with a small heap. Restore it into a test database.
- Merge a backup from another install: it must be refused. Merge a backup from the same install: it works.
- Set a daily cron, stop the app for 3 days (use a fake clock), start it: exactly one backup runs.
- Manager opens the settings page: the print token is masked.
- Confirm no Android-facing contract changed.

## Report format per item

One short block: what changed, files, tests added, any difference from this spec, setting keys used.
