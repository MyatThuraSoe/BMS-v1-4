

# LumiPOS Shop-Readiness Tasks

## Completed ✅

- [x] **Task 1: Audit Current Shop-Ready Gaps** (Aug 29, 2026)
  - Identified: Duplicate Electron shutdown handlers, missing backup/restore validation, no Windows startup
  
- [x] **Task 2: Implement Startup & Shutdown Reliability** (Aug 29, 2026)
  - Fixed: Consolidated Electron lifecycle handlers in `electron/main.js`
  - Improved: `killServerProcess()` idempotent; `registerShutdownHandlers()` central
  - Verified: Full build succeeds (Frontend + Backend + Electron packaging)
  - Output: `release\LumiPOS Setup 1.0.0.exe` (signed Windows installer)

- [x] **Task 4: Validate Build After Changes** (Aug 29, 2026)
  - Frontend: Vite build 30.77s ✅
  - Backend: Maven 27.072s, 200 Java files ✅
  - Desktop: Electron-builder NSIS installer created ✅

## In Progress 🔄

- [ ] **Task 3: Validate Backup/Restore Workflows** (In Progress)
  - Status: Test infrastructure created; awaiting manual validation
  - Files Created:
    - `.test-plans/backup-restore-validation.md` - 8-phase comprehensive test plan (17 test cases)
    - `.test-plans/VALIDATION-CHECKLIST.md` - Step-by-step manual validation with sign-off
    - `.test-plans/QUICK-START.md` - Copy-paste commands for fast testing
    - `.test-plans/README.md` - Complete testing guide
    - `scripts/backup-restore-test.js` - Node.js automated API test suite
    - `scripts/backup-restore-test.sh` - Bash/curl quick test suite
    - `.test-plans/test-fixture-merge.json` - Sample data for MERGE mode (2 products)
    - `.test-plans/test-fixture-replace-all.json` - Minimal data for REPLACE_ALL (1 product)
  - Test Phases:
    1. Export functionality (File download, JSON validity, data completeness)
    2. Import MERGE mode (Add data, preserve existing)
    3. Import REPLACE_ALL mode (Destructive clear, restore)
    4. API direct testing (curl/Node.js endpoints)
    5. Permission & security validation
  - Next Steps: Execute tests from QUICK-START.md or VALIDATION-CHECKLIST.md

## Backlog (Lower Priority) 📋

- [ ] **Task 5: Windows Boot Startup**
  - Design: Add Windows Services or registry autorun
  - Files: electron/main.js or package.json
  - Estimated: 30 minutes implementation

- [ ] **Task 6: Local File Storage Validation**
  - Verify backup paths use local storage
  - Confirm no hardcoded cloud-only paths
  - Estimated: 15 minutes audit

- [ ] **Task 7: POS Smoke Test on Real LAN**
  - Connect phone/tablet to 192.168.x.x
  - Test: Login, Sale, Print, Backup, Admin functions
  - Requires: Shop environment

- [ ] **Task 8: Role-Based Access Control Audit**
  - Verify Cashier/Manager/Admin permissions
  - Files: All @PreAuthorize annotations
  - Estimated: 1 hour

- [ ] **Task 9: Open/Close Shop Workflow Validation**
  - Test CashShift entity creation
  - Test daily reconciliation
  - Files: CashShift.jsx, CashShiftService.java

---

## Build & Deployment Status

### Current Build
- **Date:** 2026-08-29 10:51 (UTC+6:30)
- **Installer:** `release\LumiPOS Setup 1.0.0.exe` ✅
- **Size:** ~150MB (includes bundled JRE)
- **Signature:** Code-signed with signtool

### Configuration Profiles
- `application.yml` - Base config (Spring profiles: mysql, electron, prod)
- `application-prod.yml` - Production hardening (disabled actuators, H2 console)
- `application-electron.yml` - Electron profile (H2 embedded DB)
- `application-mysql.yml` - MySQL production config

### Known Issues
- None (all lifecycle bugs fixed)

### Ready for Shop Deployment
- ✅ Reliable startup/shutdown
- ✅ Backup/restore code complete (needs validation)
- ✅ LAN access configured (SecurityConfig.java)
- ✅ Admin role protection on sensitive endpoints
- ⚠️ Windows boot startup (not yet implemented)
- ⚠️ Smoke test on real LAN machine (not yet performed)

---

## Notes

- **Backup/Restore Already Implemented:** Backend controllers exist (DataController, BackupController), frontend UI exists (DataManagement.jsx, BackupSettings.jsx). Tests are ready but not yet executed.
- **Next Quick Win:** Run QUICK-START.md tests (~5 minutes automated, ~30 minutes manual)
- **Production Readiness:** Can deploy after backup/restore validation passes







I want Drafts stored in frontend storage/cookie only. the Drafts lifetime should not be long enough to live in the backend database.

and i have done some of these. help me continue -- i haved updated drafts.jsx, 