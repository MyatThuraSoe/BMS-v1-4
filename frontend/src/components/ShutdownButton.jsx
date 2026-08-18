import { useState } from 'react';
import { useTranslation } from 'react-i18next';

export default function ShutdownButton() {
    const { t } = useTranslation('settings');
    const [shuttingDown, setShuttingDown] = useState(false);

    const handleShutdown = async () => {
        if (!window.confirm(t('shutdown_confirm'))) {
            return;
        }

        setShuttingDown(true);

        try {
            await fetch('/api/system/shutdown', { method: 'POST' });

            await new Promise(resolve => setTimeout(resolve, 1500));

            if (window.electronAPI && window.electronAPI.quitApp) {
                window.electronAPI.quitApp();
            } else {
                alert(t('server_stopped'));
                window.close();
            }
        } catch (error) {
            if (window.electronAPI && window.electronAPI.quitApp) {
                window.electronAPI.quitApp();
            } else {
                alert(t('server_stopped'));
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
            {shuttingDown ? t('shutting_down') : t('shutdown_label')}
        </button>
    );
}