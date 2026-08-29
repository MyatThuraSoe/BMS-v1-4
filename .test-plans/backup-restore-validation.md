# Backup/Restore End-to-End Validation Plan

**Objective:** Verify backup export, import, merge, and destructive restore workflows work correctly.

**Status:** In Progress  
**Date:** 2026-08-29

---

## Phase 1: Pre-Test Setup

### Backend Health Check
- [ ] Verify Spring Boot is running on port 17234
- [ ] Confirm MySQL is running (or H2 in electron profile)
- [ ] Check `/api/health` responds with 200 OK

### Frontend Access
- [ ] Open app at `http://127.0.0.1:3000` (or Electron desktop app)
- [ ] Login with Admin role (required for backup/restore)
- [ ] Navigate to Settings → Data Management

### Initial Data State
- [ ] Record current product count (expected: N products)
- [ ] Record current sales count (expected: M sales)
- [ ] Note any recent customer/supplier records

---

## Phase 2: Full JSON Export Test

### Test 2.1: Export Functionality
**Steps:**
1. In DataManagement page, click "Download Full Backup (JSON)"
2. File should download automatically: `lumipos-backup-YYYY-MM-DD.json`
3. Open file in text editor and verify:
   - [ ] File is valid JSON (no syntax errors)
   - [ ] Contains `data` object with entity collections
   - [ ] Includes all tables: `products`, `sales`, `customers`, `suppliers`, etc.
   - [ ] Contains `exportedAt` timestamp
   - [ ] File size > 1KB (non-empty data)

**Expected Result:** ✅ Valid JSON file with all entities present

### Test 2.2: Export Data Integrity
**Verify structure contains:**
- [ ] `data.products[]` - Product catalog
- [ ] `data.sales[]` - All sales records
- [ ] `data.saleItems[]` - Line items from sales
- [ ] `data.customers[]` - Customer master data
- [ ] `data.suppliers[]` - Supplier records
- [ ] `data.categories[]` - Product categories
- [ ] `data.purchases[]` - Procurement records
- [ ] `data.orders[]` - Orders (if enabled)

**Expected Result:** ✅ All 15+ entity types present with data

---

## Phase 3: Import with MERGE Mode (Additive)

### Test 3.1: MERGE Mode - Add New Records
**Setup:**
1. Export current backup as "backup-before-merge.json"
2. Manually add new test data to JSON:
   ```json
   {
     "data": {
       "products": [
         {"id": 9999, "name": "TEST_PRODUCT_MERGE", "price": 100},
         ...existing products...
       ]
     }
   }
   ```

**Steps:**
1. Open DataManagement page
2. Click "Choose File" and select modified backup
3. Preview should show file loaded successfully
4. Set mode to "MERGE"
5. Click "Import"

**Expected Result:**
- [ ] Import completes without errors
- [ ] Test product appears in Products list
- [ ] Original products still exist
- [ ] No data loss occurred

### Test 3.2: MERGE Mode - Verify Addition
**Verification:**
1. Go to Products page
2. Search for "TEST_PRODUCT_MERGE"
3. Verify record exists with correct values

**Expected Result:** ✅ New data merged, original data preserved

---

## Phase 4: Import with REPLACE_ALL Mode (Destructive)

### Test 4.1: Backup Before Destructive Test
**Critical Steps:**
1. Export current state as "backup-before-replace.json"
2. Record current product/sales counts
3. Proceed with caution (this test DELETES data)

### Test 4.2: REPLACE_ALL Mode - Data Wipe & Restore
**Steps:**
1. Create minimal test backup:
   ```json
   {
     "exportedAt": "2026-08-29T00:00:00",
     "data": {
       "products": [
         {"id": 1, "name": "TEST_ONLY_PRODUCT", "price": 50}
       ],
       "customers": [],
       "sales": [],
       "purchases": []
     }
   }
   ```

2. Open DataManagement
3. Load test backup file
4. Set mode to "REPLACE_ALL"
5. Accept confirmation dialog (checkbox required)
6. Click "Import"

**Expected Result:**
- [ ] Warning dialog appears requiring confirmation
- [ ] Import completes
- [ ] Products list shows ONLY "TEST_ONLY_PRODUCT"
- [ ] All other products deleted
- [ ] All sales records deleted

### Test 4.3: Restore from Pre-Test Backup
**Steps:**
1. Open DataManagement
2. Load "backup-before-replace.json"
3. Set mode to "REPLACE_ALL"
4. Confirm and import

**Expected Result:**
- [ ] All original data restored
- [ ] Product counts match pre-test
- [ ] Sales records restored
- [ ] Test-only records removed

**Verification:**
- [ ] Search for "TEST_ONLY_PRODUCT" → NOT FOUND
- [ ] Product count = original count
- [ ] Sales records accessible

---

## Phase 5: API Direct Testing (curl/Postman)

### Test 5.1: Export via API
```bash
curl -X GET http://localhost:17234/api/data/export \
  -H "Authorization: Bearer <ADMIN_TOKEN>" \
  -H "Accept: application/json" \
  -o test-export.json
```

**Expected Result:**
- [ ] Status 200 OK
- [ ] File saved with valid JSON
- [ ] File is identical to UI export

### Test 5.2: Import via API
```bash
curl -X POST http://localhost:17234/api/data/import?mode=MERGE \
  -H "Authorization: Bearer <ADMIN_TOKEN>" \
  -H "Content-Type: application/json" \
  -d @test-export.json
```

**Expected Result:**
- [ ] Status 200 OK
- [ ] Response contains `counts` summary
- [ ] Counts match imported data

---

## Phase 6: Permission & Security Tests

### Test 6.1: Admin-Only Access
**Steps:**
1. Login as non-Admin user (e.g., Cashier role)
2. Navigate to DataManagement page
3. Verify export/import buttons are disabled/hidden

**Expected Result:** ✅ Feature restricted to ADMIN role

### Test 6.2: CORS & LAN Access
**Setup:** If testing from a LAN client (phone/tablet)

**Steps:**
1. From LAN client, call `http://<SERVER_LAN_IP>:17234/api/data/export`
2. Verify CORS headers allow cross-origin access

**Expected Result:** ✅ LAN clients can export/import data

---

## Phase 7: Edge Cases & Error Handling

### Test 7.1: Invalid JSON Upload
**Steps:**
1. Create malformed JSON file
2. Try to import via DataManagement
3. Click "Choose File" → select bad JSON

**Expected Result:**
- [ ] Error notification appears
- [ ] Message: "Invalid backup file"
- [ ] No data imported

### Test 7.2: Missing Required Fields
**Steps:**
1. Create JSON without `data` object:
   ```json
   { "products": [] }
   ```
2. Try to import

**Expected Result:**
- [ ] Validation fails
- [ ] Error message displayed
- [ ] Original data unchanged

### Test 7.3: Empty Backup
**Steps:**
1. Export backup with no records (empty database scenario)
2. Re-import same backup

**Expected Result:**
- [ ] Export/import succeeds
- [ ] No errors
- [ ] Database empty after restore

### Test 7.4: Partial Backup (Missing Tables)
**Steps:**
1. Export full backup
2. Remove `sales` array from JSON
3. Import with MERGE mode

**Expected Result:**
- [ ] Import succeeds
- [ ] Existing sales preserved (not in import, so not overwritten)
- [ ] Other tables updated

---

## Phase 8: Performance & Large Data Test

### Test 8.1: Large Backup Export
**Setup:** Database with 10,000+ sales records

**Steps:**
1. Trigger export from DataManagement
2. Measure export time
3. Verify file size is reasonable

**Expected Result:**
- [ ] Export completes in < 30 seconds
- [ ] File size < 50MB

### Test 8.2: Large Backup Import
**Steps:**
1. Import large backup with MERGE mode
2. Monitor progress

**Expected Result:**
- [ ] Import completes in < 60 seconds
- [ ] No timeout errors
- [ ] All records imported correctly

---

## Test Results Summary

| Test | Status | Notes |
|------|--------|-------|
| 2.1 - Export Valid JSON | ⬜ | |
| 2.2 - Export Data Integrity | ⬜ | |
| 3.1 - MERGE Mode Add | ⬜ | |
| 3.2 - MERGE Verification | ⬜ | |
| 4.1 - Pre-Test Backup | ⬜ | |
| 4.2 - REPLACE_ALL Destructive | ⬜ | |
| 4.3 - Restore from Backup | ⬜ | |
| 5.1 - API Export | ⬜ | |
| 5.2 - API Import | ⬜ | |
| 6.1 - Admin-Only Access | ⬜ | |
| 6.2 - CORS & LAN Access | ⬜ | |
| 7.1 - Invalid JSON | ⬜ | |
| 7.2 - Missing Fields | ⬜ | |
| 7.3 - Empty Backup | ⬜ | |
| 7.4 - Partial Backup | ⬜ | |
| 8.1 - Large Export | ⬜ | |
| 8.2 - Large Import | ⬜ | |

---

## Validation Checklist

- [ ] All tests completed
- [ ] No data loss detected
- [ ] All edge cases handled gracefully
- [ ] Performance acceptable
- [ ] Security controls working
- [ ] Backup/restore feature approved for shop deployment
