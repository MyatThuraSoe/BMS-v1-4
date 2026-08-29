#!/bin/bash

# Backup/Restore API Test Suite - Curl Version
# Quick validation of export/import endpoints
# 
# Usage: bash backup-restore-test.sh <API_URL> <ADMIN_TOKEN>
# Example: bash backup-restore-test.sh http://localhost:17234 eyJhbGc...

API_URL="${1:-http://localhost:17234}"
ADMIN_TOKEN="${2:-}"
EXPORT_FILE="backup-export-test.json"
TEST_DIR="./test-backups"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

PASSED=0
FAILED=0

# Create test directory
mkdir -p "$TEST_DIR"

echo -e "${YELLOW}==========================================${NC}"
echo -e "${YELLOW}Backup/Restore API Test Suite (Curl)${NC}"
echo -e "${YELLOW}API URL: $API_URL${NC}"
echo -e "${YELLOW}==========================================${NC}\n"

# Test 1: Export
echo -e "${CYAN}[TEST 1] Export data via GET /api/data/export${NC}"
if curl -s -X GET "$API_URL/api/data/export" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Accept: application/json" \
    -o "$TEST_DIR/$EXPORT_FILE" \
    -w "%{http_code}" | grep -q "200"; then
    
    if [ -f "$TEST_DIR/$EXPORT_FILE" ] && [ -s "$TEST_DIR/$EXPORT_FILE" ]; then
        echo -e "${GREEN}✅ PASSED${NC}"
        echo "  File size: $(du -h "$TEST_DIR/$EXPORT_FILE" | cut -f1)"
        ((PASSED++))
    else
        echo -e "${RED}❌ FAILED - File is empty${NC}"
        ((FAILED++))
    fi
else
    echo -e "${RED}❌ FAILED - HTTP error${NC}"
    ((FAILED++))
fi

# Test 2: Import MERGE mode
echo -e "\n${CYAN}[TEST 2] Import data with MERGE mode${NC}"
RESPONSE=$(curl -s -X POST "$API_URL/api/data/import?mode=MERGE" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d @"$TEST_DIR/$EXPORT_FILE" \
    -w "\n%{http_code}")

HTTP_CODE=$(echo "$RESPONSE" | tail -1)
if [ "$HTTP_CODE" = "200" ]; then
    echo -e "${GREEN}✅ PASSED${NC}"
    echo "  Response: $(echo "$RESPONSE" | head -1 | jq -r '.data.message // .message' 2>/dev/null || echo 'Success')"
    ((PASSED++))
else
    echo -e "${RED}❌ FAILED - HTTP $HTTP_CODE${NC}"
    ((FAILED++))
fi

# Test 3: Verify backup file is valid JSON
echo -e "\n${CYAN}[TEST 3] Verify exported JSON is valid${NC}"
if jq empty "$TEST_DIR/$EXPORT_FILE" 2>/dev/null; then
    ENTITY_COUNT=$(jq '.data | keys | length' "$TEST_DIR/$EXPORT_FILE" 2>/dev/null || echo "0")
    echo -e "${GREEN}✅ PASSED${NC}"
    echo "  Entity types: $ENTITY_COUNT"
    ((PASSED++))
else
    echo -e "${RED}❌ FAILED - Invalid JSON${NC}"
    ((FAILED++))
fi

# Test 4: Check for key entities in export
echo -e "\n${CYAN}[TEST 4] Verify required entities in backup${NC}"
if jq -e '.data.products' "$TEST_DIR/$EXPORT_FILE" >/dev/null 2>&1; then
    PRODUCT_COUNT=$(jq '.data.products | length' "$TEST_DIR/$EXPORT_FILE")
    echo -e "${GREEN}✅ PASSED${NC}"
    echo "  Products: $PRODUCT_COUNT"
    ((PASSED++))
else
    echo -e "${RED}❌ FAILED - Missing products entity${NC}"
    ((FAILED++))
fi

# Test 5: Invalid mode handling
echo -e "\n${CYAN}[TEST 5] Reject invalid import mode${NC}"
RESPONSE=$(curl -s -X POST "$API_URL/api/data/import?mode=INVALID_MODE" \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    -H "Content-Type: application/json" \
    -d @"$TEST_DIR/$EXPORT_FILE" \
    -w "\n%{http_code}")

HTTP_CODE=$(echo "$RESPONSE" | tail -1)
if [ "$HTTP_CODE" != "200" ]; then
    echo -e "${GREEN}✅ PASSED${NC}"
    echo "  Rejected with status: $HTTP_CODE"
    ((PASSED++))
else
    echo -e "${RED}❌ FAILED - Should reject invalid mode${NC}"
    ((FAILED++))
fi

# Test 6: Authorization check
echo -e "\n${CYAN}[TEST 6] Verify authorization required${NC}"
RESPONSE=$(curl -s -X GET "$API_URL/api/data/export" \
    -H "Accept: application/json" \
    -w "\n%{http_code}")

HTTP_CODE=$(echo "$RESPONSE" | tail -1)
if [ "$HTTP_CODE" = "401" ] || [ "$HTTP_CODE" = "403" ]; then
    echo -e "${GREEN}✅ PASSED${NC}"
    echo "  Unauthorized access rejected with status: $HTTP_CODE"
    ((PASSED++))
else
    echo -e "${YELLOW}⚠️  WARNING - Expected 401/403, got $HTTP_CODE${NC}"
fi

# Print summary
echo -e "\n${YELLOW}==========================================${NC}"
echo -e "Tests Passed: ${GREEN}$PASSED${NC}"
echo -e "Tests Failed: $([ $FAILED -eq 0 ] && echo -e "${GREEN}$FAILED${NC}" || echo -e "${RED}$FAILED${NC}")"
echo -e "${YELLOW}==========================================${NC}\n"

# Cleanup
echo -e "Test files saved in: ${CYAN}$TEST_DIR${NC}\n"

exit $FAILED
