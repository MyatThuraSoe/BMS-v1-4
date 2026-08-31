const { app, BrowserWindow, Tray, Menu, dialog, nativeImage, ipcMain, shell } = require('electron');
const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs');
const http = require('http');
const net = require('net');
const os = require('os');

let mainWindow = null;
let tray = null;
let serverProcess = null;
let isQuitting = false;
let serverPid = null;
let activePrintWindow = null;

// Load secrets from a gitignored .env file (real credentials live here, not in
// source or the JAR). Values are exported into process.env so the spawned Java
// server inherits them and Spring's ${...} placeholders resolve at runtime.
function loadDotEnv() {
    const candidates = [
        path.join(__dirname, '..', '.env'),                // dev: project root
        path.join(process.resourcesPath || '', '.env'),    // packaged: resources/
        path.join(path.dirname(process.execPath), '.env')  // packaged: exe directory
    ];
    for (const envPath of candidates) {
        if (!envPath || !fs.existsSync(envPath)) continue;
        try {
            const raw = fs.readFileSync(envPath, 'utf8');
            for (const line of raw.split(/\r?\n/)) {
                const match = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/);
                if (match && !(match[1] in process.env)) {
                    process.env[match[1]] = match[2];
                }
            }
            console.log(`[Electron] Loaded .env from ${envPath}`);
            return;
        } catch (err) {
            console.log(`[Electron] Could not load ${envPath}: ${err.message}`);
        }
    }
    console.log('[Electron] No .env file found - using system/registry environment variables.');
}

loadDotEnv();

const APP_PORT = 17234;
const APP_URL = `http://127.0.0.1:${APP_PORT}`;

// DB engine per launch: production default is MySQL (shop-server mode,
// application-mysql.yml). Opt back into the built-in H2 for dev/testing
// with LUMIPOS_DB=electron or LUMIPOS_DB=h2 (see scripts/start-electron.js).
const requestedDb = (process.env.LUMIPOS_DB || '').toLowerCase();
const DB_PROFILE = (requestedDb === 'electron' || requestedDb === 'h2') ? 'electron' : 'mysql';

// First usable LAN IPv4, preferring real private ranges over virtual
// adapters (VMware/Hyper-V/WSL often register first).
function getLanIp() {
    const nets = os.networkInterfaces();
    const candidates = [];
    for (const name of Object.keys(nets)) {
        for (const net of nets[name] || []) {
            if (net.family === 'IPv4' && !net.internal) candidates.push(net.address);
        }
    }
    return (
        candidates.find((ip) => ip.startsWith('192.168.')) ||
        candidates.find((ip) => ip.startsWith('10.')) ||
        candidates.find((ip) => /^172\.(1[6-9]|2\d|3[01])\./.test(ip)) ||
        candidates[0] ||
        null
    );
}
const LAN_IP = getLanIp();
const LAN_URL = LAN_IP ? `http://${LAN_IP}:${APP_PORT}` : null;

// Find Java executable - bundled JRE first, then system Java as fallback
function getJavaPath() {
    const fs = require('fs');
    
    // 1. Check for bundled JRE
    let bundledJava;
    if (app.isPackaged) {
        // Production: JRE is in resources/bundled-jre/
        bundledJava = path.join(process.resourcesPath, 'bundled-jre', 'bin', 'java.exe');
    } else {
        // Development: JRE is in project root
        bundledJava = path.join(__dirname, '..', 'bundled-jre', 'bin', 'java.exe');
    }
    
    try {
        fs.accessSync(bundledJava);
        console.log(`[Electron] Using bundled JRE: ${bundledJava}`);
        return bundledJava;
    } catch (e) {
        console.log('[Electron] Bundled JRE not found, falling back to system Java');
    }
    
    // 2. Fallback: system Java locations
    const systemPaths = [
        'C:\\Program Files\\Java\\jdk-23\\bin\\java.exe',
        'C:\\Program Files\\Java\\jdk-21\\bin\\java.exe',
        path.join(process.env.JAVA_HOME || '', 'bin', 'java.exe')
    ];
    
    for (const jp of systemPaths) {
        if (jp && jp !== '') {
            try {
                fs.accessSync(jp);
                console.log(`[Electron] Using system Java: ${jp}`);
                return jp;
            } catch (e) {
                continue;
            }
        }
    }
    
    // 3. Last resort: system PATH
    console.log('[Electron] Falling back to system PATH java');
    return 'java';
}

// Determine JAR path based on environment
function getJarPath() {
    if (app.isPackaged) {
        return path.join(process.resourcesPath, 'app', 'bms-backend-1.0.0.jar');
    } else {
        return path.join(__dirname, '..', 'bms-backend', 'target', 'bms-backend-1.0.0.jar');
    }
}

// Start the Spring Boot server
// Start the Spring Boot server
function startServer() {
    const jarPath = getJarPath();
    const javaPath = getJavaPath();
    
    console.log(`Starting server with Java: ${javaPath}`);
    console.log(`JAR path: ${jarPath}`);
    
    // Optional override only — if GOOGLE_CLIENT_SECRET isn't set, the value
    // from application-electron.yml is used instead.
    // DB engine: production default MySQL; H2 via LUMIPOS_DB=electron/h2 (see DB_PROFILE)
    console.log('[LumiPOS] Database profile: ' + DB_PROFILE);
    const spawnArgs = [
        '-Dspring.profiles.active=' + DB_PROFILE,
        '-Dspring.main.banner-mode=off',
        '-Dserver.port=' + APP_PORT
    ];
    if (process.env.GOOGLE_CLIENT_SECRET) {
        spawnArgs.push('-Dgoogle.oauth.client-secret=' + process.env.GOOGLE_CLIENT_SECRET);
    }
    spawnArgs.push('-jar', jarPath);

    serverProcess = spawn(javaPath, spawnArgs, {
        stdio: ['ignore', 'pipe', 'pipe'],
        detached: false,
        windowsHide: true
    });
    // ✅ Store the PID for later
    serverPid = serverProcess.pid;
    console.log(`[Server] Started Java process with PID: ${serverPid}`);

    // Log server output (useful for debugging)
    serverProcess.stdout.on('data', (data) => {
        console.log(`[Server]: ${data.toString().trim()}`);
    });

    serverProcess.stderr.on('data', (data) => {
        console.error(`[Server Error]: ${data.toString().trim()}`);
    });

    serverProcess.on('error', (err) => {
        dialog.showErrorBox(
            'Server Error',
            `Failed to start the BMS server.\n\nError: ${err.message}\n\nPlease make sure Java is installed on your computer.`
        );
        app.quit();
    });

    serverProcess.on('exit', (code) => {
        if (!isQuitting) {
            console.log(`Server exited with code ${code}`);
        }
    });
}

// Hard requirement for the MySQL profile: verify the database accepts TCP
// connections BEFORE launching Java, so users get a clear message instead
// of a cryptic Spring Boot stack trace.
function checkMysqlReady(host = '127.0.0.1', port = 3306, timeoutMs = 3000) {
    return new Promise((resolve) => {
        const socket = new net.Socket();
        const finish = (ok) => {
            try { socket.destroy(); } catch (e) { /* ignore */ }
            resolve(ok);
        };
        socket.setTimeout(timeoutMs);
        socket.once('connect', () => finish(true));
        socket.once('timeout', () => finish(false));
        socket.once('error', () => finish(false));
        socket.connect(port, host);
    });
}

// Wait for server to be ready by polling
function waitForServer(retries = 30, interval = 1000) {
    return new Promise((resolve, reject) => {
        let attempts = 0;
        
        const check = () => {
            attempts++;
            
            const req = http.get(APP_URL, (res) => {
                resolve();
            });
            
            req.on('error', () => {
                if (attempts >= retries) {
                    reject(new Error('Server did not start in time'));
                } else {
                    setTimeout(check, interval);
                }
            });
            
            req.setTimeout(2000, () => {
                req.destroy();
                if (attempts >= retries) {
                    reject(new Error('Server did not start in time'));
                } else {
                    setTimeout(check, interval);
                }
            });
        };
        
        check();
    });
}

// Create the main application window
function createWindow() {
    mainWindow = new BrowserWindow({
        width: 1366,
        height: 800,
        minWidth: 1024,
        minHeight: 600,
        title: 'LumiPOS - Business Management System',
        icon: getIconPath(),
        show: false,
        backgroundColor: '#F3F5F1',
        webPreferences: {
            preload: path.join(__dirname, 'preload.js'),
            nodeIntegration: false,
            contextIsolation: true,
            spellcheck: false
        }
    });

    // Remove default menu bar
    mainWindow.setMenuBarVisibility(false);

    // Load the Spring Boot app
    mainWindow.loadURL(APP_URL);

    // Show window when content is ready
    mainWindow.once('ready-to-show', () => {
        mainWindow.show();
        mainWindow.focus();
    });

    // Handle navigation errors (e.g., server not ready)
    mainWindow.webContents.on('did-fail-load', (event, errorCode, errorDescription) => {
        console.error(`Failed to load: ${errorDescription}`);
        // Retry loading after a short delay
        setTimeout(() => {
            mainWindow.loadURL(APP_URL);
        }, 2000);
    });

    // Clicking the window close (X) button shuts the whole app down,
    // including the background Java server, so reopening never hits a busy port.
    mainWindow.on('close', () => {
        if (!isQuitting) {
            quitApp();
        }
    });

    mainWindow.on('closed', () => {
        mainWindow = null;
    });
}

// Get icon path
function getIconPath() {
    const iconPath = path.join(__dirname, 'LumiPOS.ico');
    try {
        require('fs').accessSync(iconPath);
        return iconPath;
    } catch (e) {
        return undefined; // Use default icon if custom icon not found
    }
}

// Create system tray icon
function createTray() {
    const iconPath = getIconPath();
    const trayIcon = iconPath ? nativeImage.createFromPath(iconPath) : nativeImage.createEmpty();
    
    tray = new Tray(trayIcon.resize({ width: 16, height: 16 }));
    tray.hasShownNotification = false;

    const contextMenu = Menu.buildFromTemplate([
        {
            label: 'Open LumiPOS',
            click: () => {
                if (mainWindow) {
                    mainWindow.show();
                    mainWindow.focus();
                }
            }
        },
        { type: 'separator' },
        {
            label: 'Open in Browser',
            click: () => {
                require('electron').shell.openExternal(APP_URL);
            }
        },
        {
            type: 'separator'
        },
        {
            label: 'Phone / Tablet Access',
            click: async () => {
                const message = LAN_URL
                    ? `Open this address in your phone's browser:\n\n${LAN_URL}\n\nBoth devices must be on the same Wi-Fi. You can also scan the QR code on the About page inside LumiPOS.`
                    : 'No Wi-Fi/LAN network detected on this computer.\nConnect to your shop Wi-Fi and restart LumiPOS.';
                const { response } = await dialog.showMessageBox({
                    type: 'info',
                    title: 'LumiPOS on your phone / tablet',
                    message,
                    buttons: LAN_URL ? ['Close', 'Copy Address'] : ['Close'],
                    defaultId: 0,
                    cancelId: 0,
                });
                if (response === 1 && LAN_URL) {
                    require('electron').clipboard.writeText(LAN_URL);
                }
            }
        },
        { type: 'separator' },
        {
            label: 'Quit LumiPOS',
            click: () => {
                quitApp();
            }
        }
    ]);

    tray.setToolTip('LumiPOS - Business Management System' + (LAN_URL ? ` | Phone/tablet: ${LAN_URL}` : ''));
    tray.setContextMenu(contextMenu);

    // One gentle heads-up per launch so staff learn the address exists
    if (LAN_URL) {
        try {
            tray.displayBalloon({
                iconType: 'info',
                title: 'LumiPOS is on your Wi-Fi',
                content: `Open on your phone: ${LAN_URL}  (or scan the QR code on the About page)`,
            });
        } catch { /* balloon unsupported */ }
    }

    // Double-click tray icon to show window
    tray.on('double-click', () => {
        if (mainWindow) {
            mainWindow.show();
            mainWindow.focus();
        }
    });
}

function cleanupZombieProcesses() {
    console.log('[Startup] Cleaning up any zombie processes...');
    
    if (process.platform === 'win32') {
        try {
            // Find any process LISTENING on our port. We only ever kill the
            // LISTENING owner of :17234 (our own server from a crashed run).
            // ESTABLISHED peers (e.g. a browser tab pointed at the API) are
            // never touched, so we can't kill an unrelated app.
            const result = require('child_process').spawnSync(
                'cmd',
                ['/c', `netstat -ano | findstr :${APP_PORT}`],
                { encoding: 'utf8', windowsHide: true }
            );
            
            const pids = new Set();
            const lines = result.stdout.trim().split('\n');
            for (const line of lines) {
                const parts = line.trim().split(/\s+/);
                const state = parts.length >= 4 ? parts[3] : '';
                const pid = parts[parts.length - 1];
                // Only kill the process that actually LISTENS on the port.
                // 'LISTENING' is Windows' state label; some platforms show '*' or the raw number.
                if (state === 'LISTENING' && pid && pid !== '0') {
                    pids.add(pid);
                }
            }

            for (const pid of pids) {
                console.log(`[Startup] Killing zombie process on port ${APP_PORT} (PID: ${pid})`);
                require('child_process').spawnSync(
                    'taskkill',
                    ['/F', '/T', '/PID', pid],
                    { stdio: 'ignore', windowsHide: true }
                );
            }
        } catch (err) {
            console.warn('[Startup] Cleanup warning:', err.message);
        }
    }
}

function killServerProcess() {
    const pid = serverPid || (serverProcess && serverProcess.pid);
    if (!pid) {
        console.log('[Shutdown] No server PID to kill');
        return;
    }

    if (serverProcess && serverProcess.exitCode !== null) {
        console.log('[Shutdown] Server already exited cleanly');
        serverProcess = null;
        serverPid = null;
        return;
    }

    console.log(`[Shutdown] Killing Java process (PID: ${pid})...`);

    try {
        if (process.platform === 'win32') {
            // ✅ /F = Force kill, /T = Kill entire process tree (including child processes)
            const result = require('child_process').spawnSync(
                'taskkill',
                ['/F', '/T', '/PID', String(pid)],
                { stdio: 'ignore', windowsHide: true }
            );
            console.log(`[Shutdown] taskkill exit code: ${result.status}`);
        } else if (serverProcess) {
            serverProcess.kill('SIGTERM');
            setTimeout(() => {
                if (serverProcess && !serverProcess.killed) {
                    try { serverProcess.kill('SIGKILL'); } catch (e) { /* ignore */ }
                }
            }, 1000);
        }
    } catch (err) {
        console.error('[Shutdown] Error killing process:', err.message);
    }

    serverProcess = null;
    serverPid = null;
}

function quitApp() {
    if (isQuitting) {
        console.log('[Shutdown] Quit already in progress, ignoring duplicate request');
        return;
    }
    isQuitting = true;

    console.log('[Shutdown] Quitting LumiPOS...');

    // ✅ Kill Java server FIRST (most important)
    killServerProcess();

    // ✅ Small delay to ensure process is dead before destroying windows
    setTimeout(() => {
        if (tray) {
            tray.destroy();
            tray = null;
        }
        if (mainWindow && !mainWindow.isDestroyed()) {
            mainWindow.destroy();
            mainWindow = null;
        }
        if (splashWindow && !splashWindow.isDestroyed()) {
            splashWindow.destroy();
            splashWindow = null;
        }
        app.quit();
    }, 300);
}


// Listen for quit request from the renderer (React app)
ipcMain.on('quit-app', () => {
    console.log('[Electron] Quit requested from renderer');
    quitApp();
});

// =====================================================
// DIRECT SILENT PRINTING (Technique #4 - webContents.print)
// Offline, no QZ Tray, no print dialog, works with any installed Windows printer
// =====================================================

ipcMain.handle('get-printers', async () => {
    try {
        // Use an existing window or create a temporary one to access getPrintersAsync
        let win = BrowserWindow.getAllWindows().find(w => !w.isDestroyed() && w !== splashWindow);
        let tempWin = null;
        if (!win) {
            tempWin = new BrowserWindow({ show: false });
            win = tempWin;
        }
        const printers = await win.webContents.getPrintersAsync();
        if (tempWin) tempWin.close();
        return printers.map(p => ({
            name: p.name,
            displayName: p.displayName || p.name,
            isDefault: p.isDefault
        }));
    } catch (e) {
        console.error('[Print] Failed to list printers:', e);
        return [];
    }
});

ipcMain.handle('print-receipt', async (event, html, printerName, paperSizeMm) => {
    if (activePrintWindow && !activePrintWindow.isDestroyed()) {
        return { success: false, error: 'Another receipt is already printing' };
    }

    return new Promise((resolve) => {
        let settled = false;
        const printWindow = new BrowserWindow({
            show: false,
            width: 400,
            height: 600,
            webPreferences: { contextIsolation: true, nodeIntegration: false }
        });
        activePrintWindow = printWindow;
        const tempFile = path.join(app.getPath('temp'), `lumipos-receipt-${Date.now()}-${Math.random().toString(36).slice(2)}.html`);

        const finish = (result) => {
            if (settled) return;
            settled = true;
            if (activePrintWindow === printWindow) activePrintWindow = null;
            if (!printWindow.isDestroyed()) printWindow.close();
            try { fs.unlinkSync(tempFile); } catch (e) { /* already removed */ }
            resolve(result);
        };

        console.log(`[Print] Preparing receipt: printer=${printerName || 'default'}, width=${paperSizeMm || 'A4'}mm`);

        const timeout = setTimeout(() => {
            console.error('[Print] Timed out while preparing or sending receipt');
            finish({ success: false, error: 'Printer timed out' });
        }, 15000);

        try {
            // Avoid a large data: URL; Chromium can reject it as ERR_INVALID_URL
            // when the receipt contains embedded logo/QR data.
            fs.writeFileSync(tempFile, html, 'utf8');
        } catch (error) {
            clearTimeout(timeout);
            finish({ success: false, error: `Failed to load receipt HTML: ${error.message}` });
            return;
        }

        printWindow.loadFile(tempFile).catch((error) => {
            clearTimeout(timeout);
            finish({ success: false, error: `Failed to load receipt HTML: ${error.message}` });
        });

        printWindow.webContents.on('did-finish-load', () => {
            const print = (pageSize) => {
                const options = {
                    silent: true,            // No print dialog
                    printBackground: true,   // Keep colors and backgrounds
                    margins: { marginType: 'none' },
                    pageSize
                };
                if (printerName) options.deviceName = printerName;

                printWindow.webContents.print(options, (success, failureReason) => {
                    clearTimeout(timeout);
                    if (success) {
                        finish({ success: true });
                    } else {
                        console.error('[Print] Failed:', failureReason);
                        finish({ success: false, error: failureReason || 'Unknown error' });
                    }
                });
            };

            if (!paperSizeMm) {
                print('A4');
                return;
            }

            // Match the backend counter printer: configured width and an
            // 80mm minimum roll height, growing only to fit the receipt.
            printWindow.webContents.executeJavaScript('document.body.scrollHeight')
                .then((scrollHeight) => {
                    const contentHeightMm = Number(scrollHeight) * 25.4 / 96;
                    const heightMm = Math.max(80, Math.ceil(contentHeightMm));
                    console.log(`[Print] Sending receipt to printer: width=${paperSizeMm}mm, height=${heightMm}mm`);
                    print({ width: paperSizeMm * 1000, height: heightMm * 1000 });
                })
                .catch(() => {
                    console.log(`[Print] Sending receipt with fallback height: width=${paperSizeMm}mm, height=80mm`);
                    print({ width: paperSizeMm * 1000, height: 80000 });
                });
        });

        printWindow.webContents.on('did-fail-load', () => {
            clearTimeout(timeout);
            finish({ success: false, error: 'Failed to load receipt HTML' });
        });
    });
});

// Create splash/loading window
let splashWindow = null;

function createSplashWindow() {
    splashWindow = new BrowserWindow({
        width: 500,
        height: 380,
        frame: false,
        transparent: true,
        resizable: false,
        alwaysOnTop: true,
        skipTaskbar: true,
        webPreferences: {
            nodeIntegration: false,
            contextIsolation: true,
            preload: path.join(__dirname, 'splash-preload.js')
        }
    });

    splashWindow.loadFile(path.join(__dirname, 'splash.html'));
    splashWindow.center();
    splashWindow.show();
}

function updateSplash(percent, message) {
    if (splashWindow && !splashWindow.isDestroyed()) {
        splashWindow.webContents.send('splash-progress', { percent, message });
    }
}

function closeSplashWindow() {
    if (splashWindow && !splashWindow.isDestroyed()) {
        splashWindow.close();
        splashWindow = null;
    }
}

// Ensure only ONE LumiPOS instance runs. The second launch focuses the
// existing window instead of starting a second Java server on the same port.
const gotTheLock = app.requestSingleInstanceLock();

if (!gotTheLock) {
    console.log('[Startup] Another LumiPOS instance is already running. Exiting.');
    app.quit();
} else {
    app.on('second-instance', () => {
        if (mainWindow) {
            if (mainWindow.isMinimized()) mainWindow.restore();
            mainWindow.show();
            mainWindow.focus();
        }
    });

    // App lifecycle
    app.whenReady().then(async () => {

    cleanupZombieProcesses();
    // Show splash screen immediately
    createSplashWindow();
    updateSplash(5, 'Initializing LumiPOS...');

    // Small delay so splash renders before heavy work
    await new Promise(resolve => setTimeout(resolve, 500));

    updateSplash(15, 'Starting server engine...');

    // MySQL is a hard requirement in production — fail fast with guidance
    if (DB_PROFILE === 'mysql') {
        const mysqlUp = await checkMysqlReady();
        if (!mysqlUp) {
            closeSplashWindow();
            dialog.showErrorBox(
                'LumiPOS needs MySQL',
                'LumiPOS stores its data in MySQL, which is not reachable on this computer.\n\n' +
                'Expected: MySQL running on localhost:3306 (database: lumipos, user: lumi)\n\n' +
                'Please make sure:\n' +
                '1. MySQL Server is installed and the service is started\n' +
                '2. It is listening on port 3306\n' +
                '3. The database "lumipos" exists (created automatically is NOT supported — run the one-time setup script)\n\n' +
                'Then start LumiPOS again.'
            );
            quitApp();
            return;
        }
    }

    // Start the Spring Boot server
    startServer();

    updateSplash(30, 'Loading database...');

    try {
        // Wait for server with progress updates
        await waitForServerWithProgress(120, 1000);
        
        updateSplash(95, 'Preparing interface...');
        await new Promise(resolve => setTimeout(resolve, 300));

        // Create the main window and tray
        createWindow();
        createTray();

        // Tell the user how to reach LumiPOS from other devices on the Wi-Fi
        if (LAN_URL) {
            console.log(`[LumiPOS] On the same Wi-Fi, open: ${LAN_URL}  (login with your LumiPOS account)`);
        } else {
            console.log('[LumiPOS] No LAN IP detected — phone access unavailable.');
        }

        updateSplash(100, 'Ready!');
        await new Promise(resolve => setTimeout(resolve, 500));

        // Close splash and show main window
        closeSplashWindow();
    } catch (error) {
        closeSplashWindow();
        dialog.showErrorBox(
            'Startup Error',
            'The BMS server failed to start.\n\nPlease check that:\n1. Java is installed\n2. MySQL is running and reachable\n3. Port 17234 is not in use\n4. The application files are not corrupted'
        );
        quitApp();
    }
    });
}

// Enhanced wait with progress updates
function waitForServerWithProgress(retries = 30, interval = 1000) {
    return new Promise((resolve, reject) => {
        let attempts = 0;

        const check = () => {
            attempts++;

            // Update progress based on attempts
            const progress = Math.min(30 + (attempts / retries) * 60, 90);
            const messages = [
                'Connecting to database...',
                'Loading product catalog...',
                'Initializing services...',
                'Preparing user interface...',
                'Almost ready...'
            ];
            const msgIndex = Math.min(Math.floor(attempts / 6), messages.length - 1);
            updateSplash(progress, messages[msgIndex]);

            const req = http.get(APP_URL, (res) => {
                resolve();
            });

            req.on('error', () => {
                if (attempts >= retries) {
                    reject(new Error('Server did not start in time'));
                } else {
                    setTimeout(check, interval);
                }
            });

            req.setTimeout(2000, () => {
                req.destroy();
                if (attempts >= retries) {
                    reject(new Error('Server did not start in time'));
                } else {
                    setTimeout(check, interval);
                }
            });
        };

        check();
    });
}

function registerShutdownHandlers() {
    app.on('before-quit', () => {
        console.log('[Shutdown] before-quit triggered');
        if (!isQuitting) {
            isQuitting = true;
        }
        killServerProcess();
    });

    app.on('will-quit', () => {
        console.log('[Shutdown] will-quit triggered');
        killServerProcess();
    });

    app.on('window-all-closed', () => {
        console.log('[Shutdown] All windows closed');
        if (process.platform !== 'darwin') {
            quitApp();
        }
    });

    app.on('activate', () => {
        if (mainWindow === null) {
            createWindow();
        } else {
            mainWindow.show();
        }
    });
}

registerShutdownHandlers();



// ✅ NEW: Open URLs in the system's default browser
ipcMain.handle('open-external', async (event, url) => {
    // Security: only allow http/https URLs
    if (url.startsWith('http://') || url.startsWith('https://')) {
        await shell.openExternal(url);
        return true;
    }
    return false;
});

process.on('exit', () => {
    console.log('[Shutdown] process.exit triggered');
    killServerProcess();
});

process.on('uncaughtException', (err) => {
    console.error('[Shutdown] Uncaught exception:', err);
    killServerProcess();
    app.quit();
});

process.on('unhandledRejection', (reason, promise) => {
    console.error('[Shutdown] Unhandled rejection:', reason);
    killServerProcess();
    app.quit();
});