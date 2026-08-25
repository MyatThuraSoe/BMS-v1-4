// Launcher: runs Electron with a chosen DB profile.
//   node scripts/start-electron.js          -> built-in H2 (default)
//   node scripts/start-electron.js mysql    -> MySQL shop-server mode
const { spawn } = require('child_process');

const profile = process.argv[2] === 'mysql' ? 'mysql' : 'electron';
console.log(`[LumiPOS] Starting desktop app — database profile: ${profile}`);

const child = spawn('npx', ['electron', '.'], {
  stdio: 'inherit',
  env: { ...process.env, LUMIPOS_DB: profile },
  // Windows: npx resolves to npx.cmd — Node >=18.20 requires shell:true
  shell: process.platform === 'win32',
});

child.on('exit', (code) => process.exit(code ?? 0));
