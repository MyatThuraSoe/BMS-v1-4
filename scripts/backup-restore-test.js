#!/usr/bin/env node

/**
 * Backup/Restore API Test Suite
 * Tests export, import (MERGE/REPLACE_ALL), and error handling
 * 
 * Usage: node backup-restore-test.js <API_URL> <ADMIN_TOKEN>
 * Example: node backup-restore-test.js http://localhost:17234 eyJhbGc...
 */

const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');

const API_URL = process.argv[2] || 'http://localhost:17234';
const ADMIN_TOKEN = process.argv[3] || '';

const RED = '\x1b[31m';
const GREEN = '\x1b[32m';
const YELLOW = '\x1b[33m';
const CYAN = '\x1b[36m';
const RESET = '\x1b[0m';

let testsPassed = 0;
let testsFailed = 0;

// ============ HELPERS ============

function log(msg, color = RESET) {
    console.log(`${color}${msg}${RESET}`);
}

function httpRequest(method, pathname, body = null, headers = {}) {
    return new Promise((resolve, reject) => {
        const url = new URL(pathname, API_URL);
        const isHttps = url.protocol === 'https:';
        const client = isHttps ? https : http;

        const defaultHeaders = {
            'Content-Type': 'application/json',
            ...headers,
        };

        if (ADMIN_TOKEN) {
            defaultHeaders['Authorization'] = `Bearer ${ADMIN_TOKEN}`;
        }

        const options = {
            method,
            headers: defaultHeaders,
        };

        const req = client.request(url, options, (res) => {
            let data = '';
            res.on('data', (chunk) => { data += chunk; });
            res.on('end', () => {
                try {
                    const json = data ? JSON.parse(data) : {};
                    resolve({ status: res.statusCode, body: json, headers: res.headers });
                } catch (e) {
                    resolve({ status: res.statusCode, body: data, headers: res.headers });
                }
            });
        });

        req.on('error', reject);

        if (body) {
            const jsonBody = typeof body === 'string' ? body : JSON.stringify(body);
            req.write(jsonBody);
        }
        req.end();
    });
}

async function test(name, fn) {
    try {
        log(`\n[TEST] ${name}`, CYAN);
        await fn();
        log(`✅ PASSED`, GREEN);
        testsPassed++;
    } catch (err) {
        log(`❌ FAILED: ${err.message}`, RED);
        testsFailed++;
    }
}

function assert(condition, message) {
    if (!condition) throw new Error(message);
}

// ============ TEST SUITE ============

async function runTests() {
    log(`\n${'='.repeat(60)}`, YELLOW);
    log('Backup/Restore API Test Suite', YELLOW);
    log(`API URL: ${API_URL}`, YELLOW);
    log(`${'='.repeat(60)}\n`, YELLOW);

    // Test 1: Export
    let exportedData = null;
    await test('1.1 - Export data via GET /api/data/export', async () => {
        const res = await httpRequest('GET', '/api/data/export');
        assert(res.status === 200, `Expected 200, got ${res.status}`);
        assert(res.body && res.body.data, 'Response should contain data object');
        assert(Array.isArray(res.body.data.products), 'Should have products array');
        exportedData = res.body;
        log(`  ✓ Exported ${Object.keys(res.body.data).length} entity types`);
    });

    // Test 2: Import MERGE mode
    await test('1.2 - Import data with MERGE mode', async () => {
        assert(exportedData, 'Export test must pass first');
        const res = await httpRequest(
            'POST',
            '/api/data/import?mode=MERGE',
            exportedData
        );
        assert(res.status === 200, `Expected 200, got ${res.status}`);
        assert(res.body.success === true, 'Response should indicate success');
        log(`  ✓ Import completed`);
    });

    // Test 3: Invalid JSON handling
    await test('1.3 - Handle invalid JSON gracefully', async () => {
        const res = await httpRequest('POST', '/api/data/import?mode=MERGE', { invalid: 'data' });
        assert(res.status !== 200, 'Should reject invalid backup structure');
        log(`  ✓ Invalid data rejected with status ${res.status}`);
    });

    // Test 4: REPLACE_ALL mode (destructive)
    await test('1.4 - Import with REPLACE_ALL mode', async () => {
        assert(exportedData, 'Export test must pass first');
        const res = await httpRequest(
            'POST',
            '/api/data/import?mode=REPLACE_ALL',
            exportedData
        );
        assert(res.status === 200, `Expected 200, got ${res.status}`);
        log(`  ✓ REPLACE_ALL completed`);
    });

    // Test 5: Missing Authorization
    await test('1.5 - Reject request without authorization', async () => {
        const oldToken = ADMIN_TOKEN;
        process.argv[3] = ''; // Clear token
        const res = await httpRequest('GET', '/api/data/export', null, { 'Authorization': '' });
        assert(res.status === 401 || res.status === 403, `Expected 401/403, got ${res.status}`);
        process.argv[3] = oldToken; // Restore token
        log(`  ✓ Unauthorized access rejected with status ${res.status}`);
    });

    // Test 6: Mode validation
    await test('1.6 - Validate import mode parameter', async () => {
        assert(exportedData, 'Export test must pass first');
        const res = await httpRequest(
            'POST',
            '/api/data/import?mode=INVALID_MODE',
            exportedData
        );
        assert(res.status !== 200, 'Should reject invalid mode');
        log(`  ✓ Invalid mode rejected with status ${res.status}`);
    });

    // Print summary
    log(`\n${'='.repeat(60)}`, YELLOW);
    log(`Tests Passed: ${testsPassed}`, GREEN);
    log(`Tests Failed: ${testsFailed}`, testsFailed > 0 ? RED : GREEN);
    log(`${'='.repeat(60)}\n`, YELLOW);

    process.exit(testsFailed > 0 ? 1 : 0);
}

// Run tests
runTests().catch(err => {
    log(`Fatal error: ${err.message}`, RED);
    process.exit(1);
});
