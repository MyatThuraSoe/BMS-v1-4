const { app, BrowserWindow, Tray, Menu, dialog, nativeImage, ipcMain } = require('electron');
const { spawn } = require('child_process');
const path = require('path');
const http = require('http');

let mainWindow = null;
let tray = null;
let serverProcess = null;
let isQuitting = false;

const APP_PORT = 8080;
const APP_URL = `http://127.0.0.1:${APP_PORT}`;

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
    
    serverProcess = spawn(javaPath, [
        '-Dspring.profiles.active=electron',
        '-Dspring.main.banner-mode=off',
        '-Dserver.port=' + APP_PORT,
        '-jar',
        jarPath
    ], {
        stdio: ['ignore', 'pipe', 'pipe'],
        detached: false,
        windowsHide: true
    });

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

    // Prevent window from being destroyed on close - hide to tray instead
    mainWindow.on('close', (event) => {
        if (!isQuitting) {
            event.preventDefault();
            mainWindow.hide();
            
            // Show tray notification on first hide
            if (tray && !tray.hasShownNotification) {
                tray.displayBalloon({
                    title: 'LumiPOS',
                    content: 'LumiPOS is still running in the background. Right-click the tray icon to quit.'
                });
                tray.hasShownNotification = true;
            }
        }
    });

    mainWindow.on('closed', () => {
        mainWindow = null;
    });
}

// Get icon path
function getIconPath() {
    const iconPath = path.join(__dirname, 'icon.ico');
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
        { type: 'separator' },
        {
            label: 'Quit LumiPOS',
            click: () => {
                quitApp();
            }
        }
    ]);

    tray.setToolTip('LumiPOS - Business Management System');
    tray.setContextMenu(contextMenu);

    // Double-click tray icon to show window
    tray.on('double-click', () => {
        if (mainWindow) {
            mainWindow.show();
            mainWindow.focus();
        }
    });
}

// Quit the application gracefully
function quitApp() {
    isQuitting = true;
    
    // Kill the Java server process
    if (serverProcess) {
        try {
            // On Windows, we need to kill the process tree
            if (process.platform === 'win32') {
                spawn('taskkill', ['/pid', serverProcess.pid, '/f', '/t'], {
                    stdio: 'ignore',
                    windowsHide: true
                });
            } else {
                serverProcess.kill('SIGTERM');
            }
        } catch (e) {
            console.error('Error killing server process:', e);
        }
        serverProcess = null;
    }
    
    // Destroy tray and window
    if (tray) {
        tray.destroy();
        tray = null;
    }
    
    if (mainWindow) {
        mainWindow.destroy();
        mainWindow = null;
    }
    
    app.quit();
}


// Listen for quit request from the renderer (React app)
ipcMain.on('quit-app', () => {
    console.log('[Electron] Quit requested from renderer');
    quitApp();
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
            nodeIntegration: true,
            contextIsolation: false
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

// App lifecycle
app.whenReady().then(async () => {
    // Show splash screen immediately
    createSplashWindow();
    updateSplash(5, 'Initializing LumiPOS...');

    // Small delay so splash renders before heavy work
    await new Promise(resolve => setTimeout(resolve, 500));

    updateSplash(15, 'Starting server engine...');

    // Start the Spring Boot server
    startServer();

    updateSplash(30, 'Loading database...');

    try {
        // Wait for server with progress updates
        await waitForServerWithProgress(30, 1000);
        
        updateSplash(95, 'Preparing interface...');
        await new Promise(resolve => setTimeout(resolve, 300));

        // Create the main window and tray
        createWindow();
        createTray();

        updateSplash(100, 'Ready!');
        await new Promise(resolve => setTimeout(resolve, 500));

        // Close splash and show main window
        closeSplashWindow();
    } catch (error) {
        closeSplashWindow();
        dialog.showErrorBox(
            'Startup Error',
            'The BMS server failed to start.\n\nPlease check that:\n1. Java is installed\n2. Port 8080 is not in use\n3. The application files are not corrupted'
        );
        quitApp();
    }
});

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

// Handle app quit
app.on('before-quit', () => {
    isQuitting = true;
});

app.on('will-quit', () => {
    if (serverProcess) {
        try {
            if (process.platform === 'win32') {
                spawn('taskkill', ['/pid', serverProcess.pid, '/f', '/t'], {
                    stdio: 'ignore',
                    windowsHide: true
                });
            } else {
                serverProcess.kill('SIGTERM');
            }
        } catch (e) {
            // Ignore
        }
    }
});

// macOS specific (not needed for Windows but good practice)
app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') {
        // Don't quit - keep running in tray
    }
});

app.on('activate', () => {
    if (mainWindow === null) {
        createWindow();
    } else {
        mainWindow.show();
    }
});