import { useEffect, useState } from 'react';
import { Alert, Box, Button, Typography } from '@mui/material';
import { WifiOff as WifiOffIcon, Refresh as RefreshIcon } from '@mui/icons-material';
import { useTranslation } from 'react-i18next';
import useServerHeartbeat from '../hooks/useServerHeartbeat';

function formatDownFor(downSince) {
  if (!downSince) return null;
  const seconds = Math.max(0, Math.round((Date.now() - downSince.getTime()) / 1000));
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  const rest = seconds % 60;
  if (minutes < 60) return `${minutes}m ${rest}s`;
  return `${Math.floor(minutes / 60)}h ${minutes % 60}m`;
}

// Shown whenever the browser cannot reach the LumiPOS server. There is no close
// button on purpose: a cashier must not be able to dismiss the one warning
// that explains why the sale screen is not working.
//
// variant="floating" renders a fixed overlay at the app root (used on
// Login / Setup / Activation, which sit outside DashboardLayout and have no
// content area to push down). Default renders inline above page content.
export default function ServerOfflineBanner({ variant = 'inline' }) {
  const { t } = useTranslation();
  const { isOnline, checking, downSince } = useServerHeartbeat();
  const [elapsed, setElapsed] = useState(null);

  useEffect(() => {
    if (isOnline) {
      setElapsed(null);
      return undefined;
    }
    setElapsed(formatDownFor(downSince));
    const id = setInterval(() => setElapsed(formatDownFor(downSince)), 1000);
    return () => clearInterval(id);
  }, [isOnline, downSince]);

  if (isOnline) return null;

  const floating = variant === 'floating';

  return (
    <Alert
      severity="error"
      icon={<WifiOffIcon />}
      sx={
        floating
          ? {
              position: 'fixed',
              top: 0,
              left: 0,
              right: 0,
              zIndex: (theme) => theme.zIndex.snackbar + 1,
              borderRadius: 0,
              borderBottom: 2,
              borderColor: 'error.main',
              justifyContent: 'center',
              '& .MuiAlert-message': { width: '100%', maxWidth: 900 }
            }
          : { mb: 2, alignItems: 'center', border: 2, borderColor: 'error.main', '& .MuiAlert-message': { width: '100%' } }
      }
    >
      <Box sx={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 1.5 }}>
        <Box sx={{ flexGrow: 1, minWidth: 220 }}>
          <Typography variant="subtitle2" sx={{ fontWeight: 700 }}>
            {t('server_offline_title')}
          </Typography>
          <Typography variant="body2">
            {t('server_offline_message')}
            {elapsed ? ` (${t('server_offline_elapsed', { elapsed })})` : ''}
          </Typography>
          <Typography variant="caption" sx={{ display: 'block', mt: 0.5, opacity: 0.85 }}>
            {t('server_offline_hint')}
          </Typography>
        </Box>
        <Button
          size="small"
          variant="outlined"
          color="inherit"
          disabled={checking}
          onClick={() => window.dispatchEvent(new Event('online'))}
          startIcon={<RefreshIcon sx={{ animation: checking ? 'spin 1s linear infinite' : 'none' }} />}
          sx={{ '@keyframes spin': { from: { transform: 'rotate(0deg)' }, to: { transform: 'rotate(360deg)' } } }}
        >
          {checking ? t('server_offline_checking') : t('server_offline_retry')}
        </Button>
      </Box>
    </Alert>
  );
}
