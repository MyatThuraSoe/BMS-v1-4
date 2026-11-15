# BMS-v1-4 Technical Fix Specification: Fourth Pass (Accounts, Customers, Period Close)

Basis: clone at commit `29fc3d8` (merge of PR #17), static review only; the build and tests were not run. This is a separate document from the first three specifications. Item IDs use the prefix **T** (T1–T7) to avoid clashing with F, R, O and S items.

Not reviewed in this pass: `ReportService` in depth, `BackupRestoreService`, `SupplierService`, `PriceListService`, receipts and Google Drive integration.

## 0. Scope and rules

- **In scope:** `bms-backend` and the web `frontend`. **Out of scope: the Android app. It is not touched.** Keep every REST contract backward compatible: new request fields optional, new response fields additive, no endpoint removed or renamed.
- One pull request per item. Order: failing test first, then the fix, then a green run. Name tests after the item (`t1_closeRejectsCurrentMonth`).
- Schema changes use the existing idempotent startup runner (`AccountingSchemaMigration`). All new columns are nullable or defaulted. Existing data is migrated, never assumed clean (see T2 and T4).
- **\[Decision\]** marks an owner choice. Implement the stated default, hold it in a system setting, and list it in the PR.

## 1. Summary and order of work

| ID | Severity | Theme |
| --- | --- | --- |
| T1 | Medium | Period close accepts a month that has not ended; reopen ignores later closed months |
| T2 | Medium | Usernames can differ only by case and break login for both accounts |
| T3 | Medium | Customers with a balance can be deleted |
| T4 | Medium-Low | Customer code generator fails after the newest code of the day is deleted |
| T5 | Low-Medium | Deleting or deactivating a user leaves an open shift and drawer session |
| T6 | Low-Medium | Tokens issued before a password change stay valid |
| T7 | Low | Soft-deleted users appear in the user list |

Suggested order: T1 and T2 first (outage risks), then T3 and T4, then T5, T6, T7.

---

## T1. Period close accepts a month that has not ended (Medium)

**Files:** `AccountingPeriodService.close`, `reopen`, `PeriodGuard`, `AccountingController`, accounting UI (`PeriodControl.jsx`).

**Current behavior.** `close(YearMonth month, userId)` checks only that ledger reads are active, that the month is not already closed, and that the history reconciles. It never checks that the month is over. Closing the current month makes `periodGuard.assertOpen(...)` reject every sale, return, purchase, expense and drawer operation dated in that month, so the POS stops working until someone reopens it. A future month can be closed too. `reopen` deletes the `PeriodClose` row without checking that later months are still closed, so an earlier month can be edited while the months after it keep frozen totals that depended on it (inventory valuation and weighted cost carry forward).

**Required change.**

1. Add a guard in `close`: `month` must be strictly before the current shop month (`YearMonth.from(shopTimeService.today())`). Otherwise throw `BusinessException("Only a completed month can be closed")`. **\[Decision\]** Setting `accounting.period.minDaysAfterMonthEnd` (default 0) can require some grace days after the month ends before closing is allowed (for late supplier invoices).
2. Optional safety check, default on (`accounting.period.requireNoOpenDrawers`): refuse to close a month while a cash drawer session opened in that month is still `OPEN`, naming the session. Reason: its sales and movements are still being added.
3. Close order: closing month M requires every earlier month with ledger history to be closed already, **\[Decision\]** default on (`accounting.period.requireSequentialClose`), error text lists the first open month. This keeps the frozen carry-forward values consistent.
4. `reopen`: allow only the **latest** closed month by default. If later months are closed, reject with the list of months that must be reopened first (latest first). An ADMIN-only flag `reopenCascade=true` may reopen the later months in one transaction, with a single audit entry listing them and the reason.
5. Keep the reason requirement and the audit entry. Add the previous frozen totals to the audit text (already present).
6. UI: on the accounting period screen disable the Close button for the current and future months with a short tooltip, and show the reopen cascade warning before confirming.

**Acceptance tests.**

- Closing the current month and a future month is rejected; closing last month succeeds.
- With `requireSequentialClose` on and January open, closing February is rejected naming January.
- With a drawer session from the month still open, close is rejected naming the session.
- Reopening January while February is closed is rejected; with `reopenCascade` both reopen and one audit entry lists both.
- After a rejected close nothing is written (no `PeriodClose` row, no audit entry for success).

---

## T2. Usernames can differ only by case and break login (Medium)

**Files:** `UserService.createUser`, `updateUser`, `UserRepository` (`existsByUsername`, `existsByEmail`, `findByUsernameIgnoreCase`), `User` entity (unique constraints), `AuthService`, `DatabaseSeeder`, migration runner.

**Current behavior.** Uniqueness checks use `existsByUsername` and `existsByEmail`, which are exact-match queries. On H2 and SQLite exact match is case-sensitive, and the unique column constraints are case-sensitive too. Login resolves the user with `findByUsernameIgnoreCase`, which returns an `Optional`. If both `admin` and `Admin` exist, that query returns two rows and throws `IncorrectResultSizeDataAccessException`, so **neither account can log in**, including the real administrator. The failed-attempt tracker already lower-cases the key, which confirms usernames are meant to be case-insensitive. Emails have the same shape of problem.

**Required change.**

1. Add `existsByUsernameIgnoreCase` and `existsByEmailIgnoreCase` to `UserRepository` and use them in `createUser` and `updateUser`. In `updateUser` compare ignoring case so renaming `admin` to `Admin` for the same user is allowed.
2. Normalize on write: store `username` trimmed, keep the display case as typed, and add a `username_normalized` column (lower case, trimmed) with a unique index. Same for `email_normalized`. Login uses the normalized column through a new `findByUsernameNormalized`. Keep `findByUsernameIgnoreCase` only as a compatibility wrapper that cannot return more than one row.
3. Migration (idempotent): populate the normalized columns for existing users. If duplicates already exist, do not fail startup: keep the lowest id as is, rename later duplicates to `<name>#dup_<id>` in the normalized column only, set them inactive, log a `USER_DUPLICATE_DETECTED` audit entry per account, and show a banner to admins ("Duplicate usernames found, review accounts"). Never delete accounts automatically.
4. Soft-delete: `SoftDeleteKeys.release` must also update the normalized columns so a deleted name can be reused.
5. `AuthService.authenticateUser` catches `IncorrectResultSizeDataAccessException` once as a safety net and returns the normal "invalid credentials" response (and logs an error) rather than a 500.

**Acceptance tests.**

- Creating `Admin` when `admin` exists is rejected with the "Username already exists" message; creating by email differing only in case is rejected.
- Renaming a user to a case variant of its own name succeeds; to another user's name fails.
- Migration over a database that already contains `admin` and `Admin`: both rows load, startup succeeds, the later one is inactive, one audit entry per duplicate.
- Login for `ADMIN`, `admin` and `Admin` all reach the same single account.
- Delete a user then create a new user with the same username: succeeds.

---

## T3. Customers with a balance can be deleted (Medium)

**Files:** `CustomerService.deleteCustomer`, `CustomerController`, `ArService`, `SaleRepository.sumOutstandingAr`, customer list UI.

**Current behavior.** `deleteCustomer` soft-deletes without checking `currentBalance` (money the customer owes), `creditBalance` (store credit owed to the customer) or open credit invoices. Their invoices keep outstanding amounts that still count in `sumOutstandingAr`, but the customer is hidden from AR screens, so the debt cannot be collected from the UI and the report total no longer matches any visible list. Store credit is lost the same way. The audit entry is written with a null user, so it does not record who deleted the customer.

**Required change.**

1. In `deleteCustomer` refuse when any of these is true, with a specific message and the amounts: `currentBalance > 0`, `creditBalance > 0`, any credit sale with `paymentStatus` in (`UNPAID`, `PARTIAL`) and an outstanding amount above zero, any pending order (`PENDING` status) for the customer.
2. **\[Decision\]** Provide a second, explicit path for write-offs: `POST /api/customers/{id}/write-off` (ADMIN only, reason required) that posts the remaining debt as a bad-debt expense through `LedgerPostingService.postExpense` (category `BAD_DEBT`, add the enum value if missing), marks the invoices `WRITTEN_OFF` (or `PAID` with a write-off note), zeroes `currentBalance`, and then allows deletion. Store credit is refunded or zeroed with a recorded movement. Default: no write-off path is exposed in the UI until the owner enables it (`shop.customer.allowWriteOff`, default false); deletion simply stays blocked.
3. Pass the acting user id into `deleteCustomer` (controller reads it from `Authentication`) and log it. Add the user to other customer audit entries that currently pass null.
4. Deleted customers with history stay readable on old invoices; do not cascade anything.
5. UI: show the blocking reasons in the delete dialog instead of a generic error.

**Acceptance tests.**

- Customer with `currentBalance` 50: delete rejected with the amount; after full payment, delete succeeds.
- Customer with `creditBalance` 20: delete rejected.
- Customer with only paid invoices and zero balances: delete succeeds and the audit entry has the acting user.
- With write-off enabled: debt written off posts one expense and one audit entry; invoices no longer count in `sumOutstandingAr`; the AR total equals the sum of visible customer balances afterwards.

---

## T4. Customer code generator fails after deleting the newest code of the day (Medium-Low)

**Files:** `CustomerService.generateCustomerCode`, `CustomerRepository`, `SequenceService`, `SoftDeleteKeys`.

**Current behavior.** The generator loads the customer with the highest `customerCode` (string sort, descending, page of one), keeps it only if it starts with `CUST-<yyyyMMdd>`, and parses the number after the last dash. A soft-deleted customer's code is rewritten to `CUST-20261115-0005#del_9`. If that is the highest code, the substring after the last dash is `0005#del_9`, `Integer.parseInt` throws `NumberFormatException`, the catch sets `seqNum = 1`, and the generator returns `CUST-20261115-0001`, which already exists. Every customer creation for the rest of that day fails on the unique constraint. Two concurrent creates can also read the same highest code and collide.

**Required change.**

1. Use `SequenceService` (the same pattern as `nextTransferNumber`): a `customer_sequence` table with one row per shop date and a pessimistic lock on that row (`findByDateForUpdate`), `lastNumber + 1` formatted `%04d`. Create the row with `saveAndFlush` and re-read on `DataIntegrityViolationException`.
2. Because existing data may already hold codes for today, seed `lastNumber` on first use of a date from the highest numeric suffix found by a safe query: strip any `#del_...` suffix, parse only digits, ignore non-matching codes.
3. Keep the code format `CUST-yyyyMMdd-NNNN` and the shop date from `ShopTimeService`.
4. Add a defensive retry: if the insert still fails with a unique violation on `customer_code`, increment and retry up to 5 times.
5. Remove the page-of-one `findAll` approach.

**Acceptance tests.**

- Create three customers, delete the newest, create another: new code is `...-0004`, no exception.
- Existing row `CUST-<today>-0007#del_3` as the only code of the day: next code is `...-0008`.
- Two concurrent creations produce two distinct codes.
- A new day restarts at `0001`.

---

## T5. Deleting or deactivating a user leaves an open shift and drawer session (Low-Medium)

**Files:** `UserService.updateUser`, `deleteUser`, `CashShiftService`, `CashDrawerSessionService.closeSession`, `UserController`.

**Current behavior.** Deactivating or deleting a user does not look at open cash shifts. The shift stays `OPEN` and its drawer session stays open, so the drawer reports "already open" and nobody can start a new session until a manager force-closes it. If the deleted user was the only one on the drawer, expected cash and variance wait for a manual close. Deleting yourself is allowed when another admin exists.

**Required change.**

1. Before deactivating or deleting a user, check for an `OPEN` `CashShift` with `cashierId = userId`. If one exists, reject with a message naming the shift and drawer, unless the request includes `closeOpenShifts=true` from a manager or admin. With the flag, close the shift through the existing force-close path (`closeShift` with `forceClose=true` and reason "User deactivated"), which also applies the drawer rules, and write one audit entry. Never close a shift silently.
2. If the shift owns a shared drawer session with other attached employees, do not close the drawer; only close that user's shift (the existing attach/transfer rules apply).
3. Reject deleting your own account (separate from the last-admin guard), with the message `auth.user.self.delete`; add the string to the locale files.
4. UI: in the user edit and delete dialogs show the open shift and the "close shifts and continue" option for managers.

**Acceptance tests.**

- Deactivating a cashier with an open shift is rejected; with the flag the shift closes with the force-close reason and audit entry, and the drawer can be opened again.
- A user cannot delete their own account.
- Deactivating a user with no open shift behaves as today.

---

## T6. Tokens issued before a password change stay valid (Low-Medium)

**Files:** `JwtAuthenticationFilter`, `JwtUtil`, `UserService.changeOwnPassword`, `updateUser` (password reset), `User` entity, `AuthService`.

**Current behavior.** The filter reloads the user on every request and rejects deactivated or deleted accounts, but `jwtUtil.validateToken(token, userDetails)` checks only the username and expiry. After a user changes their password, or an admin resets it (for example after a suspected leak), every token issued earlier keeps working until it expires.

**Required change.**

1. Add `token_version` (int, default 0) to `users` through the migration runner. Include it as a claim `tv` when generating a token.
2. In the filter, after loading the user, reject the token (401 "Session no longer valid") if the claim is missing for tokens issued after this change, or differs from the user's current `token_version`. Tokens without the claim (issued before deploy) are accepted until they expire, so existing sessions are not broken on upgrade. **\[Decision\]** setting `auth.token.requireVersionClaim` (default false) can force the strict mode after one session lifetime.
3. Increment `token_version` on: own password change, admin password reset in `updateUser`, role change, deactivation, and an explicit "sign out everywhere" action.
4. The response to a password change returns a fresh token so the current session continues (the frontend stores it); other sessions are signed out.
5. Add an admin action "Sign out all sessions for this user" (increments the version, audit entry).

**Acceptance tests.**

- Token A issued, password changed: token A rejected with 401, a new token works.
- Role change invalidates older tokens (and the next request shows the new role after login).
- A pre-upgrade token without `tv` is accepted in default mode and rejected when `requireVersionClaim` is true.

---

## T7. Soft-deleted users appear in the user list (Low)

**Files:** `UserService.getAllUsers`, `UserController.getAllUsers`, `UserRepository`, `Users.jsx`.

**Current behavior.** `getAllUsers` calls `userRepository.findAll()`. Soft-deleted rows (with `deletedAt` set and a mangled `#del_` username) are returned and listed.

**Required change.** Add `findByDeletedAtIsNullOrderByUsernameAsc()` and use it by default. **\[Decision\]** Optional query parameter `includeDeleted=true` (ADMIN only) for audit views. Do not change the loading used by `loadUserByUsername` (it already hides deleted users). The UI shows no deleted users by default.

**Acceptance tests.** A deleted user is absent from the default list and present with `includeDeleted=true` for an admin and not for a manager.

---

## 2. Schema and settings summary (all additive)

| Table | Change | Item |
| --- | --- | --- |
| `users` | `username_normalized`, `email_normalized` with unique indexes; `token_version` | T2, T6 |
| `customer_sequence` | new table: `sequence_date` (unique), `last_number` | T4 |
| `expense` category enum | `BAD_DEBT` (only if the write-off path is enabled) | T3 |

New settings (seeded when missing): `accounting.period.minDaysAfterMonthEnd`, `accounting.period.requireNoOpenDrawers`, `accounting.period.requireSequentialClose`, `shop.customer.allowWriteOff`, `auth.token.requireVersionClaim`.

## 3. API compatibility checklist (Android untouched)

- No endpoint is removed or renamed. New optional request fields: `closeOpenShifts`, `reopenCascade`, `includeDeleted`.
- Password-change response keeps its shape and adds a new token field; clients that ignore it simply need to sign in again, because other sessions end.
- Old tokens without the version claim keep working in default mode so a deployed upgrade does not log everyone out. If the Android app uses the same login endpoint, it keeps working unchanged.
- Run the existing e2e specs under `frontend/e2e` after each item.

## 4. Test plan

| Item | Tests (each must fail on the current code first) |
| --- | --- |
| T1 | `t1_closeRejectsCurrentMonth`, `t1_sequentialCloseRequired`, `t1_openDrawerBlocksClose`, `t1_reopenRequiresLatest` |
| T2 | `t2_createRejectsCaseVariantUsername`, `t2_migrationHandlesExistingDuplicates`, `t2_loginCaseInsensitiveSingleAccount` |
| T3 | `t3_deleteBlockedWithBalance`, `t3_deleteBlockedWithCredit`, `t3_deleteAuditHasUser` |
| T4 | `t4_codeAfterDeletingNewest`, `t4_concurrentCodesDistinct`, `t4_newDayRestarts` |
| T5 | `t5_deactivateBlockedWithOpenShift`, `t5_selfDeleteRejected`, `t5_closeOpenShiftsFlag` |
| T6 | `t6_oldTokenRejectedAfterPasswordChange`, `t6_preUpgradeTokenAccepted` |
| T7 | `t7_deletedUsersHiddenByDefault` |

After each item re-run the existing user, auth and accounting-period tests and confirm that logging in, creating a sale and closing a drawer still work with the new guards.

## 5. Report template per item

What changed, files touched, tests added, any deviation from this spec, owner decisions applied (with the setting that flips each one), and confirmation that no Android-facing contract changed.
