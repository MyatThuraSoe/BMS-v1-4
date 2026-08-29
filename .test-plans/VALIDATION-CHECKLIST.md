# Backup/Restore Validation Checklist

**Date:** 2026-08-29  
**Tester:** [Your Name]  
**Status:** [ ] In Progress | [ ] Passed | [ ] Failed

---

## Pre-Flight Checks

### Backend Health
- [ ] Spring Boot running on port 17234
- [ ] `curl http://localhost:17234/api/health` returns 200 OK
- [ ] MySQL running (or H2 for dev)
- [ ] Database contains test data (10+ products, 5+ sales)

### Frontend Access
- [ ] Frontend running at http://localhost:3000 (or Electron app)
- [ ] Logged in as Admin user
- [ ] Can navigate to Settings → Data Management
- [ ] Export/Import buttons visible

### Environment
- [ ] Node.js installed (for API tests)
- [ ] curl available (for HTTP testing)
- [ ] jq available (for JSON validation, optional)
- [ ] ~30 minutes for full test cycle

---

## Test Phase 1: Export Functionality ⏱️ ~5 min

### UI Export Test
**Step 1.1.1 - Click Export Button**
- [ ] Go to Settings → Data Management
- [ ] Click "Download Full Backup (JSON)"
- [ ] File `lumipos-backup-YYYY-MM-DD.json` downloads
- **Result:** ✅ / ❌

**Step 1.1.2 - Verify File Integrity**
- [ ] Open downloaded file in text editor
- [ ] Verify first line starts with `{`
- [ ] Find `.data.products` in file
- [ ] File size > 1KB
- **Result:** ✅ / ❌

**Step 1.1.3 - Validate JSON Structure**
```bash
# In terminal, run:
jq '.data | keys' backup-file.json
```
- [ ] Command succeeds (no JSON syntax errors)
- [ ] Output shows entity types: products, sales, customers, etc.
- [ ] At least 5 entity types present
- **Result:** ✅ / ❌

**Step 1.1.4 - Verify Data Completeness**
```bash
# Check specific entity counts
jq '.data.products | length' backup-file.json
jq '.data.sales | length' backup-file.json
jq '.data.customers | length' backup-file.json
```
- [ ] Products count > 0
- [ ] At least one other entity has data
- [ ] exportedAt timestamp present
- **Result:** ✅ / ❌

**Phase 1 Summary:** 
| Test | Result |
|------|--------|
| 1.1.1 - File Download | ✅ / ❌ |
| 1.1.2 - File Format | ✅ / ❌ |
| 1.1.3 - JSON Syntax | ✅ / ❌ |
| 1.1.4 - Data Content | ✅ / ❌ |

---

## Test Phase 2: Import MERGE Mode ⏱️ ~5 min

### Initial State Recording
- [ ] Note current product count: **___ products**
- [ ] Note current customer count: **___ customers**
- [ ] Note current sale count: **___ sales**

### UI Import Test (MERGE)
**Step 2.1.1 - Load Test Fixture**
- [ ] Go to Settings → Data Management
- [ ] Click "Choose File"
- [ ] Select `.test-plans/test-fixture-merge.json`
- [ ] Preview displays: "Backup loaded from ..."
- **Result:** ✅ / ❌

**Step 2.1.2 - Set Mode to MERGE**
- [ ] Mode dropdown shows "MERGE" selected
- [ ] Import button is clickable
- [ ] No confirmation dialog appears yet
- **Result:** ✅ / ❌

**Step 2.1.3 - Execute Import**
- [ ] Click "Import" button
- [ ] Wait for import to complete (~5 seconds)
- [ ] Success notification appears
- **Result:** ✅ / ❌

**Step 2.1.4 - Verify Data Addition**
- [ ] Go to Inventory → Products
- [ ] Search for "TEST_PRODUCT_MERGE_1"
- [ ] Product found with:
  - [ ] Name: TEST_PRODUCT_MERGE_1
  - [ ] Price: 150.00
  - [ ] Stock: 50
- [ ] Search for "TEST_PRODUCT_MERGE_2"
- [ ] Second product found
- **Result:** ✅ / ❌

**Step 2.1.5 - Verify Original Data Preserved**
- [ ] Product count increased by 2: **___ products (was ___, now ___)**
- [ ] Original products still searchable
- [ ] Sales records intact
- [ ] Customers list unchanged
- **Result:** ✅ / ❌

**Step 2.1.6 - Cleanup**
- [ ] Go to Inventory → Products
- [ ] Find and delete TEST_PRODUCT_MERGE_1
- [ ] Find and delete TEST_PRODUCT_MERGE_2
- [ ] Product count back to original
- **Result:** ✅ / ❌

**Phase 2 Summary:**
| Test | Result |
|------|--------|
| 2.1.1 - Load File | ✅ / ❌ |
| 2.1.2 - Mode Selection | ✅ / ❌ |
| 2.1.3 - Execute Import | ✅ / ❌ |
| 2.1.4 - Verify Addition | ✅ / ❌ |
| 2.1.5 - Verify Preservation | ✅ / ❌ |
| 2.1.6 - Cleanup | ✅ / ❌ |

---

## Test Phase 3: Import REPLACE_ALL Mode ⏱️ ~10 min

⚠️ **DESTRUCTIVE TEST - THIS WILL DELETE ALL DATA**

### Critical: Create Backup
**Step 3.1.1 - Export Full Backup**
- [ ] Go to Settings → Data Management
- [ ] Click "Download Full Backup (JSON)"
- [ ] Save as `backup-before-replace.json`
- [ ] Store safely (you'll need this to restore)
- **Result:** ✅ / ❌

**Step 3.1.2 - Record Current Counts**
```bash
jq '.data | map_values(length)' backup-before-replace.json
```
- [ ] Original product count: **___**
- [ ] Original sales count: **___**
- [ ] Original customer count: **___**
- **Result:** ✅ / ❌

### Destructive Test (REPLACE_ALL)
**Step 3.2.1 - Load Minimal Fixture**
- [ ] Go to Settings → Data Management
- [ ] Click "Choose File"
- [ ] Select `.test-plans/test-fixture-replace-all.json`
- [ ] Preview displays: "Backup loaded from ..."
- **Result:** ✅ / ❌

**Step 3.2.2 - Set Mode to REPLACE_ALL**
- [ ] Mode dropdown shows "REPLACE_ALL" selected
- [ ] Import button is clickable
- **Result:** ✅ / ❌

**Step 3.2.3 - Execute Destructive Import**
- [ ] Click "Import"
- [ ] **Confirmation dialog appears:**
  - [ ] Dialog title: "Confirm Destructive Import"
  - [ ] Warning message present
  - [ ] Confirmation checkbox required
- [ ] Check the confirmation checkbox
- [ ] Click "Yes, Replace All" button
- [ ] Wait for import to complete
- [ ] Success notification appears
- **Result:** ✅ / ❌

**Step 3.2.4 - Verify Data Replacement**
- [ ] Go to Inventory → Products
- [ ] Search for "TEST_REPLACE_ONLY_PRODUCT"
- [ ] Product found (only one)
- [ ] Search for original products (e.g., first product name)
- [ ] **Original product NOT found** (deleted)
- [ ] Product count = 1
- **Result:** ✅ / ❌

**Step 3.2.5 - Verify Other Entities Cleared**
- [ ] Go to Sales → View Sales
- [ ] Sales list is empty (0 records)
- [ ] Go to Contacts → Customers
- [ ] Customer list is empty (0 records)
- [ ] Go to Procurement → Purchases
- [ ] Purchase list is empty (0 records)
- **Result:** ✅ / ❌

**Step 3.2.6 - Verify REPLACE_ALL Behavior**
```bash
# Verify fixture was applied
curl -X GET http://localhost:17234/api/data/export \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq '.data.products | length'
```
- [ ] Command shows 1 product only
- [ ] Matches test fixture replacement
- **Result:** ✅ / ❌

### Restore from Backup
**Step 3.3.1 - Load Backup**
- [ ] Go to Settings → Data Management
- [ ] Click "Choose File"
- [ ] Select `backup-before-replace.json`
- [ ] Preview displays: "Backup loaded from ..."
- **Result:** ✅ / ❌

**Step 3.3.2 - Set Mode to REPLACE_ALL**
- [ ] Mode dropdown shows "REPLACE_ALL"
- **Result:** ✅ / ❌

**Step 3.3.3 - Execute Restore**
- [ ] Click "Import"
- [ ] Confirmation dialog appears
- [ ] Check confirmation checkbox
- [ ] Click "Yes, Replace All"
- [ ] Wait for import
- [ ] Success notification appears
- **Result:** ✅ / ❌

**Step 3.3.4 - Verify Restoration**
- [ ] Go to Inventory → Products
- [ ] Original product count restored: **___ products**
- [ ] Search for original product names → ALL found
- [ ] Search for "TEST_REPLACE_ONLY_PRODUCT" → NOT found
- [ ] Go to Sales → View Sales
- [ ] Original sales count restored: **___ sales**
- [ ] Go to Contacts → Customers
- [ ] Original customers restored: **___ customers**
- **Result:** ✅ / ❌

**Phase 3 Summary:**
| Test | Result |
|------|--------|
| 3.1.1 - Export Backup | ✅ / ❌ |
| 3.1.2 - Record Counts | ✅ / ❌ |
| 3.2.1 - Load Fixture | ✅ / ❌ |
| 3.2.2 - Mode Selection | ✅ / ❌ |
| 3.2.3 - Execute & Confirm | ✅ / ❌ |
| 3.2.4 - Verify Replacement | ✅ / ❌ |
| 3.2.5 - Verify Deletion | ✅ / ❌ |
| 3.2.6 - Verify Behavior | ✅ / ❌ |
| 3.3.1 - Load Backup | ✅ / ❌ |
| 3.3.2 - Mode Selection | ✅ / ❌ |
| 3.3.3 - Execute Restore | ✅ / ❌ |
| 3.3.4 - Verify Restoration | ✅ / ❌ |

---

## Test Phase 4: API Testing ⏱️ ~5 min

### Export API Test
**Step 4.1 - Test Export Endpoint**
```bash
curl -X GET http://localhost:17234/api/data/export \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -o api-export-test.json
```
- [ ] HTTP 200 OK
- [ ] File `api-export-test.json` created
- [ ] File size > 1KB
- [ ] Valid JSON structure
- **Result:** ✅ / ❌

### Import API Test (MERGE)
**Step 4.2 - Test Import MERGE**
```bash
curl -X POST "http://localhost:17234/api/data/import?mode=MERGE" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d @api-export-test.json
```
- [ ] HTTP 200 OK
- [ ] Response contains `"success": true`
- [ ] Response contains `"counts"` object
- **Result:** ✅ / ❌

### Import API Test (REPLACE_ALL)
**Step 4.3 - Test Import REPLACE_ALL**
```bash
curl -X POST "http://localhost:17234/api/data/import?mode=REPLACE_ALL" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d @api-export-test.json
```
- [ ] HTTP 200 OK
- [ ] Response contains `"success": true`
- **Result:** ✅ / ❌

**Phase 4 Summary:**
| Test | Result |
|------|--------|
| 4.1 - Export API | ✅ / ❌ |
| 4.2 - Import API MERGE | ✅ / ❌ |
| 4.3 - Import API REPLACE_ALL | ✅ / ❌ |

---

## Test Phase 5: Security & Error Handling ⏱️ ~5 min

### Authorization Test
**Step 5.1 - Verify Admin-Only Access**
- [ ] Logout from admin account
- [ ] Login as non-Admin user (Cashier if available)
- [ ] Go to Settings
- [ ] Data Management section hidden or buttons disabled
- **Result:** ✅ / ❌

### Error Handling Test
**Step 5.2 - Invalid JSON**
- [ ] Create file `bad.json` with content: `{ invalid }`
- [ ] Try to import via UI
- [ ] Error: "Invalid backup file"
- [ ] No data imported
- **Result:** ✅ / ❌

**Step 5.3 - Missing Data Object**
- [ ] Create file with: `{ "exportedAt": "..." }`
- [ ] Try to import
- [ ] Error: "Invalid backup file"
- **Result:** ✅ / ❌

**Phase 5 Summary:**
| Test | Result |
|------|--------|
| 5.1 - Admin-Only Access | ✅ / ❌ |
| 5.2 - Invalid JSON | ✅ / ❌ |
| 5.3 - Missing Fields | ✅ / ❌ |

---

## Final Validation Summary

### Overall Results
- **Total Tests:** 28
- **Passed:** ___
- **Failed:** ___
- **Pass Rate:** ___% (Target: 100%)

### Sign-Off
- [ ] All critical tests passed
- [ ] No data loss observed
- [ ] No errors during workflow
- [ ] Ready for shop deployment

**Tester Name:** _________________  
**Date:** _________________  
**Notes:**
```
[Add any issues or observations here]


```

---

## Troubleshooting Quick Reference

| Issue | Solution |
|-------|----------|
| HTTP 401 Unauthorized | Verify admin JWT token is valid |
| JSON parsing error | Ensure file is valid JSON with `jq` |
| Import hangs | Check backend logs, may be processing large file |
| Data not visible after import | Refresh page (Ctrl+R) or clear browser cache |
| REPLACE_ALL didn't clear data | Verify database connection and permissions |
| Test fixtures not found | Check `.test-plans/` directory exists |

---

**Last Updated:** 2026-08-29  
**Validation Version:** 1.0
