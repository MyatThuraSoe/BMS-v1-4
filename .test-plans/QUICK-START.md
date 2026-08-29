# Test Execution Quick-Start

Copy & paste commands to run validation tests.

## Prerequisites
```bash
# Verify backend is running
curl http://localhost:17234/api/health

# Get your admin JWT token (from browser Network tab or app)
export ADMIN_TOKEN="your-jwt-token-here"
```

---

## Option 1: Run Automated Node.js Test Suite (Recommended)

```bash
cd d:\Projects\Spring\RMS-Offline\BMS-v1-4-main
node scripts/backup-restore-test.js http://localhost:17234 $ADMIN_TOKEN
```

**What it tests:**
- ✅ Export endpoint returns valid JSON
- ✅ Import MERGE mode adds data
- ✅ Import REPLACE_ALL mode replaces data
- ✅ Invalid JSON handling
- ✅ Authorization checks

**Time:** ~30 seconds  
**Output:** Color-coded pass/fail results

---

## Option 2: Run Bash/Curl Test Suite

```bash
cd d:\Projects\Spring\RMS-Offline\BMS-v1-4-main
bash scripts/backup-restore-test.sh http://localhost:17234 $ADMIN_TOKEN
```

**What it tests:**
- ✅ Export downloads file
- ✅ Import MERGE completes
- ✅ JSON syntax validation
- ✅ Entity presence check
- ✅ Invalid mode rejection

**Time:** ~45 seconds  
**Output Files:** `test-backups/backup-export-test.json`

---

## Option 3: Manual UI Testing (Comprehensive)

```bash
# Print the checklist
cat .test-plans/VALIDATION-CHECKLIST.md

# Follow all 5 phases:
# Phase 1: Export (5 min)
# Phase 2: Import MERGE (5 min)
# Phase 3: Import REPLACE_ALL (10 min)
# Phase 4: API Testing (5 min)
# Phase 5: Security (5 min)
```

**What it tests:**
- ✅ UI export functionality
- ✅ UI import MERGE mode
- ✅ UI import REPLACE_ALL mode (destructive)
- ✅ API endpoints
- ✅ Permission controls
- ✅ Error handling

**Time:** ~30 minutes  
**Completeness:** Full validation

---

## Option 4: Individual API Tests (curl)

### Export
```bash
curl -X GET http://localhost:17234/api/data/export \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Accept: application/json" \
  -o backup-manual-test.json

# Verify
jq '.data | keys' backup-manual-test.json
```

### Import MERGE
```bash
curl -X POST "http://localhost:17234/api/data/import?mode=MERGE" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d @backup-manual-test.json
```

### Import REPLACE_ALL
```bash
curl -X POST "http://localhost:17234/api/data/import?mode=REPLACE_ALL" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d @backup-manual-test.json
```

---

## Test Fixtures

### For MERGE Mode Testing
- **File:** `.test-plans/test-fixture-merge.json`
- **Contains:** 2 test products, 1 category, 1 customer
- **Use:** Verify MERGE adds data without deleting existing records

### For REPLACE_ALL Mode Testing
- **File:** `.test-plans/test-fixture-replace-all.json`
- **Contains:** 1 test product, 1 category (minimal)
- **Use:** Verify REPLACE_ALL clears and replaces all data

### Upload Test Fixture via UI
```bash
# Go to Settings > Data Management
# Click "Choose File"
# Select: .test-plans/test-fixture-merge.json
# Set Mode: MERGE
# Click Import
```

---

## Verification Results

### All Tests Should Return:

#### Export
```json
{
  "exportedAt": "2026-08-29T...",
  "data": {
    "products": [...],
    "sales": [...],
    "customers": [...],
    // ... other entities
  }
}
```

#### Import Success
```json
{
  "success": true,
  "data": {
    "counts": {
      "products": 25,
      "sales": 100,
      // ... counts of imported entities
    }
  }
}
```

#### Import Failure
```json
{
  "success": false,
  "message": "Reason for failure",
  "errors": [...]
}
```

---

## Documentation Files

| File | Purpose |
|------|---------|
| [README.md](.test-plans/README.md) | Complete guide with troubleshooting |
| [backup-restore-validation.md](.test-plans/backup-restore-validation.md) | Detailed 8-phase test plan |
| [VALIDATION-CHECKLIST.md](.test-plans/VALIDATION-CHECKLIST.md) | Step-by-step manual validation |
| [test-fixture-merge.json](.test-plans/test-fixture-merge.json) | Sample data for MERGE mode |
| [test-fixture-replace-all.json](.test-plans/test-fixture-replace-all.json) | Sample data for REPLACE_ALL mode |

---

## Quick Validation (3 Minutes)

```bash
# 1. Check export works
curl -X GET http://localhost:17234/api/data/export \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq '.data | keys' && echo "✅ Export OK"

# 2. Check import MERGE works
curl -X POST "http://localhost:17234/api/data/import?mode=MERGE" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"exportedAt":"2026-08-29T00:00:00Z","data":{"products":[],"sales":[]}}' \
  | jq '.success' && echo "✅ Import MERGE OK"

# 3. Check import REPLACE_ALL works
curl -X POST "http://localhost:17234/api/data/import?mode=REPLACE_ALL" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"exportedAt":"2026-08-29T00:00:00Z","data":{"products":[],"sales":[]}}' \
  | jq '.success' && echo "✅ Import REPLACE_ALL OK"
```

---

## Success Criteria

✅ **All tests pass when:**
- Export returns 200 with valid JSON
- Import MERGE returns 200 and adds data
- Import REPLACE_ALL returns 200 and replaces data
- Invalid JSON rejected with 400
- Authorization required (401 without token)
- MERGE mode preserves existing data
- REPLACE_ALL mode clears before importing

---

## Next Steps

1. ✅ Run automated tests: `node scripts/backup-restore-test.js ...`
2. ✅ Run manual checklist: `.test-plans/VALIDATION-CHECKLIST.md`
3. ✅ Verify all test cases pass
4. ✅ Document any issues found
5. ✅ Approve for shop deployment

**Status:** Ready for validation  
**Estimated Time:** 5-30 minutes (depending on test option)
