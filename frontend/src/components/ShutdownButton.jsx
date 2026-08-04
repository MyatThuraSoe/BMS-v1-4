import { useState } from 'react';

export default function ShutdownButton() {
    const [shuttingDown, setShuttingDown] = useState(false);

    const handleShutdown = async () => {
        if (!window.confirm('Are you sure you want to shut down LumiPOS?')) {
            return;
        }

        setShuttingDown(true);

        try {
            // 1. Tell Spring Boot to shut down
            await fetch('/api/system/shutdown', { method: 'POST' });

            // 2. Wait a moment for the server to stop
            await new Promise(resolve => setTimeout(resolve, 1500));

            // 3. Tell Electron to quit
            if (window.electronAPI && window.electronAPI.quitApp) {
                window.electronAPI.quitApp();
            } else {
                // Fallback: not running in Electron, just close the tab
                alert('Server has been stopped. You can close this window.');
                window.close();
            }
        } catch (error) {
            // Server already stopped or unreachable
            if (window.electronAPI && window.electronAPI.quitApp) {
                window.electronAPI.quitApp();
            } else {
                alert('Server has been stopped. You can close this window.');
            }
        }
    };

    return (
        <button
            onClick={handleShutdown}
            disabled={shuttingDown}
            style={{
                backgroundColor: shuttingDown ? '#9ca3af' : '#dc2626',
                color: 'white',
                border: 'none',
                padding: '10px 24px',
                borderRadius: '8px',
                cursor: shuttingDown ? 'not-allowed' : 'pointer',
                fontSize: '14px',
                fontWeight: '600',
                display: 'flex',
                alignItems: 'center',
                gap: '8px'
            }}
        >
            {shuttingDown ? '⏳ Shutting Down...' : '🔴 Shut Down POS'}
        </button>
    );
}