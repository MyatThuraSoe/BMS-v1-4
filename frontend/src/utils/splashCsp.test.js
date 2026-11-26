import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
const splashHtml = readFileSync(resolve(projectRoot, 'electron/splash.html'), 'utf8');

test('the splash uses an external script so the strict CSP does not block progress updates', () => {
  const inlineScripts = splashHtml.match(/<script(?![^>]*\bsrc=)[^>]*>[\s\S]*?<\/script>/gi) || [];
  assert.deepEqual(inlineScripts, [], 'splash.html must not contain an inline script');

  assert.match(splashHtml, /<script\s+src=["']\.\/splash\.js["']\s*>\s*<\/script>/i,
    'splash.html must load the external splash.js bridge');

  const splashJs = readFileSync(resolve(projectRoot, 'electron/splash.js'), 'utf8');
  assert.match(splashJs, /window\.splashAPI\.onProgress\(/,
    'splash.js must keep listening for main-process progress updates');
});
