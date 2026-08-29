# Backup/Restore Testing Guide

Complete testing framework for validating LumiPOS backup export, import (MERGE/REPLACE_ALL), and disaster recovery workflows.

## Files in This Package

### Documentation
- **backup-restore-validation.md** - Comprehensive 8-phase test plan with 17 test cases
- **test-fixtures/** - Sample JSON backups for merge/replace testing

### Test Scripts
- **Node.js Test Suite** - `scripts/backup-restore-test.js` (automated API testing)
- **Bash/Curl Test Suite** - `scripts/backup-restore-test.sh` (quick validation)

### Test Fixtures
- **test-fixture-merge.json** - Sample data for MERGE mode testing (2 test products)
- **test-fixture-replace-all.json** - Minimal backup for REPLACE_ALL mode (1 product)

---

## Quick Start

### 1. Run Automated Node.js Tests

Requires:
- Node.js 14+
- Backend running on port 17234
- Valid admin JWT token

```bash
# First, get your JWT token (login to the app or check network requests)
ADMIN_TOKEN="eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."

# Run tests
node scripts/backup-restore-test.js http://localhost:17234 $ADMIN_TOKEN
```

**Expected Output:**
```
============================================================
Backup/Restore API Test Suite
API URL: http://localhost:17234
============================================================

[TEST] 1.1 - Export data via GET /api/data/export
✅ PASSED
  ✓ Exported 14 entity types

[TEST] 1.2 - Import data with MERGE mode
✅ PASSED
  ✓ Import completed

...

============================================================
Tests Passed: 6
Tests Failed: 0
============================================================
```

### 2. Run Quick Curl Tests

Works on any system with curl and bash. No authentication required for initial checks.

```bash
# Run without token (basic connectivity check)
bash scripts/backup-restore-test.sh http://localhost:17234

# Run with token (full API testing)
bash scripts/backup-restore-test.sh http://localhost:17234 $ADMIN_TOKEN
```

**Output Files:**
- `test-backups/backup-export-test.json` - Captured export for inspection

---

## Manual Testing (UI-Based)

### Prerequisites
1. Backend running on http://localhost:17234
2. Frontend at http://localhost:3000 (or Electron app)
3. MySQL running with sample data
4. Admin user account

### Phase 1: Export Test

**Steps:**
1. Open http://localhost:3000 → Login as Admin
2. Navigate: Settings → Data Management
3. Click "Download Full Backup (JSON)"
4. File `lumipos-backup-YYYY-MM-DD.json` should download

**Verify:**
- [ ] File downloads successfully
- [ ] File is valid JSON (open in editor)
- [ ] Contains `data.products[]`, `data.sales[]`, etc.
- [ ] File size > 1KB

### Phase 2: Import MERGE Mode

**Setup:**
1. Note current product count (Settings → Inventory)
2. Download backup from Phase 1

**Test:**
1. Go to Settings → Data Management
2. Click "Choose File" → Select test-fixture-merge.json
3. Preview shows: "Backup loaded from ..."
4. Set Mode to "MERGE"
5. Click "Import"

**Verify:**
- [ ] Import succeeds
- [ ] Test products (TEST_PRODUCT_MERGE_1, TEST_PRODUCT_MERGE_2) appear in inventory
- [ ] Original products still exist
- [ ] Product count increased by 2

**Cleanup:**
1. Go to Inventory
2. Delete products TEST_PRODUCT_MERGE_1 and TEST_PRODUCT_MERGE_2

### Phase 3: Import REPLACE_ALL Mode (Destructive)

⚠️ **WARNING: This test DELETES all data. Back up before proceeding.**

**Setup:**
1. Export current state as "backup-before-replace.json"
2. Prepare test-fixture-replace-all.json

**Test:**
1. Go to Settings → Data Management
2. Click "Choose File" → Select test-fixture-replace-all.json
3. Preview shows: "Backup loaded from ..."
4. Set Mode to "REPLACE_ALL"
5. Dialog appears: "Confirm Destructive Import"
6. Check the confirmation box
7. Click "Import"

**Verify:**
- [ ] Confirmation dialog requires checkbox
- [ ] Import completes
- [ ] Inventory shows ONLY "TEST_REPLACE_ONLY_PRODUCT"
- [ ] All other products deleted
- [ ] Sales records gone
- [ ] Customers list empty

**Restore:**
1. Go to Settings → Data Management
2. Click "Choose File" → Select "backup-before-replace.json"
3. Set Mode to "REPLACE_ALL"
4. Confirm and import

**Verify:**
- [ ] Original data restored
- [ ] All products, sales, customers back
- [ ] TEST_REPLACE_ONLY_PRODUCT no longer present

---

## API Testing with Curl

### Test Export
```bash
# Export full backup
curl -X GET http://localhost:17234/api/data/export \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Accept: application/json" \
  -o backup-test.json

# Verify file
jq '.data | keys' backup-test.json
```

### Test Import with MERGE
```bash
# Import with additive merge
curl -X POST http://localhost:17234/api/data/import?mode=MERGE \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d @backup-test.json
```

### Test Import with REPLACE_ALL
```bash
# Import with destructive replace
curl -X POST http://localhost:17234/api/data/import?mode=REPLACE_ALL \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d @backup-test.json
```

---

## Test Fixtures

### test-fixture-merge.json
Contains minimal sample data for testing MERGE mode:
- 2 test products (TEST_PRODUCT_MERGE_1, TEST_PRODUCT_MERGE_2)
- 1 category (TEST_CATEGORY_1)
- 1 customer (TEST_CUSTOMER_MERGE)
- Empty: sales, purchases, orders, expenses

**Use Case:** Verify MERGE mode adds data without deleting existing records

### test-fixture-replace-all.json
Contains minimal sample data for testing REPLACE_ALL mode:
- 1 product (TEST_REPLACE_ONLY_PRODUCT)
- 1 category (TEST_CATEGORY_REPLACE)
- Empty: everything else

**Use Case:** Verify REPLACE_ALL clears old data and imports new data

---

## Expected Results

### Export Success
- HTTP 200 OK
- Response: Valid JSON with structure:
  ```json
  {
    "exportedAt": "2026-08-29T...",
    "data": {
      "products": [...],
      "sales": [...],
      ...
    }
  }
  ```

### Import MERGE Success
- HTTP 200 OK
- Response: `{"success": true, "data": {"counts": {...}}}`
- Database: Old data + new data (additive)

### Import REPLACE_ALL Success
- HTTP 200 OK
- Response: `{"success": true, "data": {"counts": {...}}}`
- Database: Cleared and replaced with new data

### Error Handling
- Missing auth: HTTP 401/403
- Invalid JSON: HTTP 400 (error message)
- Invalid mode: HTTP 400 (error message)
- Missing required fields: HTTP 400 (error message)

---

## Troubleshooting

### Export Fails with 401/403
- [ ] Admin user logged in?
- [ ] JWT token valid and not expired?
- [ ] Authorization header format: `Bearer <TOKEN>`

### Import Fails with 400
- [ ] JSON file is valid? (run `jq empty backup.json`)
- [ ] Contains `data` object with entity arrays?
- [ ] Mode is MERGE or REPLACE_ALL?

### Import Seems to Hang
- [ ] Large backup? (>50MB) - May take 60+ seconds
- [ ] Backend still running? (check `/api/health`)
- [ ] Check backend logs for errors

### Data Not Changing
- [ ] Correct database selected? (MySQL vs H2)
- [ ] Using correct import mode? (MERGE vs REPLACE_ALL)
- [ ] Refresh page after import? (may need to clear cache)

---

## Test Checklist

Complete this checklist to validate backup/restore for shop deployment:

- [ ] Export: Valid JSON file downloads
- [ ] Export: Contains all entity types
- [ ] Import MERGE: Adds data without deletion
- [ ] Import MERGE: Verification of added records
- [ ] Import REPLACE_ALL: Destructive clear works
- [ ] Import REPLACE_ALL: Restore from backup succeeds
- [ ] API: Export endpoint accessible
- [ ] API: Import MERGE endpoint accessible
- [ ] API: Import REPLACE_ALL endpoint accessible
- [ ] Security: Non-admin cannot access endpoints
- [ ] Error Handling: Invalid JSON rejected
- [ ] Error Handling: Invalid mode rejected
- [ ] Error Handling: Missing fields rejected
- [ ] Performance: Large backup (10K+ records) exports < 30s
- [ ] Performance: Large backup imports < 60s
- [ ] LAN Access: Backup accessible from 192.168.x.x client

---

## Support

For issues or questions:
1. Check the full validation plan: `.test-plans/backup-restore-validation.md`
2. Review backend logs: `docker logs lumipos-backend` (if containerized)
3. Check browser console: F12 → Network tab → Watch API calls
4. Verify data in MySQL: `SELECT COUNT(*) FROM products;` etc.
