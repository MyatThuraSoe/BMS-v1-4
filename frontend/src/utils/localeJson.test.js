import test from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
const localesDir = join(projectRoot, 'frontend/public/locales');

function localeFiles(directory = localesDir) {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const fullPath = join(directory, entry.name);
    if (entry.isDirectory()) return localeFiles(fullPath);
    return entry.name.endsWith('.json') ? [fullPath] : [];
  });
}

test('every locale file i18next fetches is valid JSON', () => {
  const files = localeFiles();
  assert.ok(files.length > 0, 'locale files were found');

  for (const file of files) {
    // A UTF-8 BOM is stripped by the browser decoder; trailing text is not.
    const source = readFileSync(file, 'utf8').replace(/^\uFEFF/, '');
    assert.doesNotThrow(() => JSON.parse(source), `${file} must parse as JSON`);
  }
});
