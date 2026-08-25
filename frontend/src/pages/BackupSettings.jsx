import { useState, useEffect, useRef } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  Box, Typography, Card, CardContent, FormControl, FormControlLabel, Switch,
  Select, MenuItem, Button, TextField, CircularProgress, Alert, Divider, Chip,
  Dialog, DialogTitle, DialogContent, DialogActions
} from '@mui/material';
import { 
  CloudUpload as CloudUploadIcon, 
  Refresh as RefreshIcon, 
  Save as SaveIcon,
  LinkOff as LinkOffIcon,
  Google as GoogleIcon,
} from '@mui/icons-material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { backupService, googleDriveService } from '../api/services';
import DataManagement from './DataManagement';



// Helper to format date (replace with your existing helper if you have one)
const formatDateTime = (dateString, fallback = 'Never') => {
  if (!dateString) return fallback;
  return new Date(dateString).toLocaleString();
};

const BackupSettings = () => {
  const { t } = useTranslation('settings');
  const [searchParams] = useSearchParams();
  const queryClient = useQueryClient();
  
  const [isRunning, setIsRunning] = useState(false);
  const [message, setMessage] = useState(null);
  const [connecting, setConnecting] = useState(false);
  const pollRef = useRef(null);
  const pollStopRef = useRef(null);

  // Inside the BackupSettings component, add state for dates:
  const [dateRange, setDateRange] = useState({ startDate: '', endDate: '' });

  
  const [settings, setSettings] = useState({
    isEnabled: false,
    frequency: 'WEEKLY',
    customCronExpression: '',
  });

  // 1. Fetch current settings
  const { data: settingsData, isLoading } = useQuery({
    queryKey: ['backupSettings'],
    queryFn: backupService.getSettings,
  });

  
  // 2. Sync local state with fetched data
  useEffect(() => {
    if (settingsData?.data) {
      setSettings({
        // Jackson serializes 'boolean isEnabled' as 'enabled' by default. 
        // We check both and use ?? to guarantee it never becomes undefined.
        isEnabled: settingsData.data.isEnabled ?? settingsData.data.enabled ?? false,
        frequency: settingsData.data.frequency ?? 'WEEKLY',
        customCronExpression: settingsData.data.customCronExpression ?? '',
      });
    }
  }, [settingsData]);

  // 3. Check for OAuth callback success/error in URL
  useEffect(() => {
    const status = searchParams.get('status');
    if (status === 'success') {
      setMessage({ type: 'success', text: t('drive_connected') });
      queryClient.invalidateQueries({ queryKey: ['backupSettings'] });
      window.history.replaceState({}, document.title, window.location.pathname); // Clean URL
    } else if (status === 'error') {
      setMessage({ type: 'error', text: t('drive_connect_failed') });
      window.history.replaceState({}, document.title, window.location.pathname);
    }
  }, [searchParams, queryClient, t]);

  // 4. Mutations
  const updateMutation = useMutation({
    mutationFn: backupService.updateSettings,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['backupSettings'] });
      setMessage({ type: 'success', text: t('settings_saved') });
    },
    onError: () => {
      setMessage({ type: 'error', text: t('settings_save_failed') });
    }
  });

  const handleSave = () => {
    updateMutation.mutate(settings);
  };

  const [remoteAuth, setRemoteAuth] = useState(null); // { authUrl } when admin is NOT on the server PC

  const handleConnect = async () => {
    setConnecting(true);
    try {
      const res = await googleDriveService.getAuthUrl();
      const authUrl = res.data.data.authUrl;
      const callerIsServer = res.data.data.callerIsServer === 'true';

      if (!callerIsServer && !window.electronAPI?.openExternal) {
        // Admin is on a phone/LAN browser. Google will return to the SERVER
        // computer (127.0.0.1) — opening here would dead-end, so guide instead.
        setRemoteAuth({ authUrl });
        setConnecting(false);
        return;
      }

      if (window.electronAPI?.openExternal) {
        // Electron → open the real browser (on this same PC = the server)
        await window.electronAPI.openExternal(authUrl);
      } else {
        // Plain browser fallback → new tab (has a back button)
        window.open(authUrl, '_blank');
      }

      startPolling(); // watch for the connection to complete
    } catch (err) {
      setMessage({ type: 'error', text: t('connection_url_failed') });
      setConnecting(false);
    }
  };

  const copyRemoteAuth = async () => {
    try {
      await navigator.clipboard.writeText(remoteAuth.authUrl);
      setMessage({ type: 'info', text: t('remote_auth_copied') });
    } catch { /* clipboard unavailable */ }
  };

    // Poll the status endpoint until Google Drive is connected
  const stopPolling = () => {
    clearInterval(pollRef.current);
    clearTimeout(pollStopRef.current);
    pollRef.current = null;
    pollStopRef.current = null;
  };

  const checkConnected = async () => {
    try {
      // getStatus() returns the raw axios response: res.data = ApiResponse, res.data.data = { connected }
      const res = await googleDriveService.getStatus();
      if (res.data?.data?.connected) {
        stopPolling();
        setConnecting(false);
        setMessage({ type: 'success', text: t('drive_connected') });
        queryClient.invalidateQueries({ queryKey: ['backupSettings'] });
        return true;
      }
    } catch { /* ignore transient errors */ }
    return false;
  };

  function startPolling() {
    stopPolling();
    pollRef.current = setInterval(checkConnected, 2000);

    // Safety: give up after 5 minutes and re-enable the button
    pollStopRef.current = setTimeout(() => {
      stopPolling();
      setConnecting(false);
    }, 5 * 60 * 1000);
  }

  // Instant check when the user returns to this window from the Google tab
  useEffect(() => {
    if (!connecting) return undefined;
    const onFocus = () => { checkConnected(); };
    window.addEventListener('focus', onFocus);
    return () => window.removeEventListener('focus', onFocus);
  }, [connecting]);

  // Clean up polling when the page unmounts
  useEffect(() => () => stopPolling(), []);

  const handleDisconnect = async () => {
    if (window.confirm(t('disconnect_confirm'))) {
      try {
        await backupService.disconnect();
        queryClient.invalidateQueries({ queryKey: ['backupSettings'] });
        setMessage({ type: 'info', text: t('drive_disconnected') });
      } catch (error) {
        setMessage({ type: 'error', text: t('disconnect_failed') });
      }
    }
  };

  // Update the handleRunNow function:
  const handleRunNow = async () => {
    setIsRunning(true);
    setMessage(null);
    try {
      const res = await backupService.runNow(dateRange.startDate || null, dateRange.endDate || null);
      setMessage({ type: 'success', text: `${res.message} ${t('backup_saved_to', { location: res.data })}` });
      queryClient.invalidateQueries({ queryKey: ['backupSettings'] });
    } catch (error) {
      setMessage({ type: 'error', text: error.response?.data?.message || t('backup_failed') });
    } finally {
      setIsRunning(false);
    }
  };

  if (isLoading) {
    return <Box sx={{ p: 3, display: 'flex', justifyContent: 'center' }}><CircularProgress /></Box>;
  }
  

  const isConnected = !!settingsData?.data?.googleRefreshToken;
  const lastBackup = settingsData?.data?.lastBackupDate;
  const nextBackup = settingsData?.data?.nextBackupDate;

  return (
    <Box sx={{ p: 3, maxWidth: 800, mx: 'auto' }}>
      <Typography variant="h4" gutterBottom sx={{ fontWeight: 'bold', mb: 1 }}>
        {t('backup_and_restore')}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        {t('backup_description')}
      </Typography>

      {message && (
        <Alert severity={message.type} sx={{ mb: 3 }} onClose={() => setMessage(null)}>
          {message.text}
        </Alert>
      )}

      {/* Google Drive Connection Card */}
      <Card sx={{ mb: 3, border: isConnected ? '2px solid #4caf50' : '1px solid #e0e0e0' }}>
        <CardContent>
          <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
            <Typography variant="h6">{t('google_drive_connection')}</Typography>
            {isConnected && <Chip label={t('connected')} color="success" size="small" />}
          </Box>
          
          <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
            {isConnected 
              ? t('drive_connected_description')
              : t('drive_disconnected_description')}
          </Typography>

          {isConnected ? (
            <Button variant="outlined" color="error" startIcon={<LinkOffIcon />} onClick={handleDisconnect}>
              {t('disconnect_google_drive')}
            </Button>
          ) : (
            <Button
              variant="contained"
              color="primary"
              startIcon={connecting ? <CircularProgress size={18} sx={{ color: 'white' }} /> : <GoogleIcon />}
              onClick={handleConnect}
              disabled={connecting}
            >
              {connecting ? t('waiting_google_signin') : t('connect_google_drive')}
            </Button>
          )}
        </CardContent>
      </Card>

      {/* Backup Settings Card */}
      <Card sx={{ mb: 3 }}>
        <CardContent>
          <Typography variant="h6" gutterBottom>{t('automation_settings')}</Typography>
          <Divider sx={{ mb: 3 }} />

          <FormControlLabel
            control={
              <Switch
                checked={settings.isEnabled}
                onChange={(e) => setSettings({ ...settings, isEnabled: e.target.checked })}
                disabled={!isConnected}
              />
            }
            label={<Typography variant="subtitle1">{t('enable_automated_backups')}</Typography>}
            sx={{ mb: 3 }}
          />

          <FormControl fullWidth sx={{ mb: 3 }}>
            <Typography variant="subtitle2" sx={{ mb: 1, color: 'text.secondary' }}>{t('backup_frequency')}</Typography>
            <Select
              value={settings.frequency}
              onChange={(e) => setSettings({ ...settings, frequency: e.target.value })}
              disabled={!settings.isEnabled || !isConnected}
            >
              <MenuItem value="DAILY">{t('frequency_daily')}</MenuItem>
              <MenuItem value="WEEKLY">{t('frequency_weekly')}</MenuItem>
              <MenuItem value="MONTHLY">{t('frequency_monthly')}</MenuItem>
              <MenuItem value="YEARLY">{t('frequency_yearly')}</MenuItem>
              <MenuItem value="CUSTOM">{t('frequency_custom')}</MenuItem>
            </Select>
          </FormControl>

          {settings.frequency === 'CUSTOM' && (
            <TextField
              fullWidth
              label={t('custom_cron_expression')}
              placeholder={t('cron_placeholder')}
              value={settings.customCronExpression}
              onChange={(e) => setSettings({ ...settings, customCronExpression: e.target.value })}
              disabled={!settings.isEnabled || !isConnected}
              sx={{ mb: 3 }}
              helperText={t('cron_format_helper')}
            />
          )}

          <Divider sx={{ my: 3 }} />
            <Typography variant="subtitle1" gutterBottom>{t('custom_date_range')}</Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
              {t('custom_date_range_helper')}
            </Typography>

            <Box sx={{ display: 'flex', gap: 2, mb: 3, flexWrap: 'wrap' }}>
              <TextField
                label={t('start_date')}
                type="date"
                value={dateRange.startDate}
                onChange={(e) => setDateRange({ ...dateRange, startDate: e.target.value })}
                InputLabelProps={{ shrink: true }}
                size="small"
                sx={{ flex: 1, minWidth: '150px' }}
              />
              <TextField
                label={t('end_date')}
                type="date"
                value={dateRange.endDate}
                onChange={(e) => setDateRange({ ...dateRange, endDate: e.target.value })}
                InputLabelProps={{ shrink: true }}
                size="small"
                sx={{ flex: 1, minWidth: '150px' }}
              />
              {(dateRange.startDate || dateRange.endDate) && (
                <Button 
                  size="small" 
                  variant="text" 
                  color="secondary" 
                  onClick={() => setDateRange({ startDate: '', endDate: '' })}
                  sx={{ alignSelf: 'center' }}
                >
                  {t('clear_range')}
                </Button>
              )}
            </Box>

          <Box sx={{ display: 'flex', gap: 2, alignItems: 'center', flexWrap: 'wrap' }}>
            <Button
              variant="contained"
              startIcon={<SaveIcon />}
              onClick={handleSave}
              disabled={updateMutation.isPending || !isConnected}
            >
              {updateMutation.isPending ? t('saving') : t('save_settings')}
            </Button>
            
            <Button
              variant="outlined"
              startIcon={isRunning ? <CircularProgress size={20} /> : <RefreshIcon />}
              onClick={handleRunNow}
              disabled={isRunning || !isConnected}
            >
              {isRunning ? t('running_backup') : t('run_backup_now')}
            </Button>
          </Box>
        </CardContent>
      </Card>

      {/* Status Card */}
      <Card>
        <CardContent>
          <Typography variant="h6" gutterBottom>{t('backup_status')}</Typography>
          <Divider sx={{ mb: 2 }} />
          <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 1 }}>
            <Typography variant="body2" color="text.secondary">{t('last_successful_backup')}</Typography>
            <Typography variant="body2" fontWeight="medium">
              {formatDateTime(lastBackup, t('never'))}
            </Typography>
          </Box>
          <Box sx={{ display: 'flex', justifyContent: 'space-between' }}>
            <Typography variant="body2" color="text.secondary">{t('next_scheduled_backup')}</Typography>
            <Typography variant="body2" fontWeight="medium" color={settings.isEnabled && isConnected ? 'success.main' : 'text.disabled'}>
              {settings.isEnabled && isConnected ? formatDateTime(nextBackup, t('never')) : t('not_scheduled')}
            </Typography>
          </Box>
        </CardContent>
      </Card>

      {/* Local Export & Import (former Data Management) */}
      <Card sx={{ mt: 3 }}>
        <CardContent>
          <Typography variant="h6" gutterBottom>{t('local_export_import')}</Typography>
          <Divider sx={{ mb: 2 }} />
          <DataManagement />
        </CardContent>
      </Card>

      {/* Remote-admin guidance: Google returns to the server PC, not this device */}
      <Dialog open={Boolean(remoteAuth)} onClose={() => setRemoteAuth(null)} maxWidth="sm" fullWidth>
        <DialogTitle>{t('remote_auth_title')}</DialogTitle>
        <DialogContent>
          <Alert severity="info" sx={{ mb: 2 }}>{t('remote_auth_desc')}</Alert>
          <TextField
            fullWidth
            multiline
            minRows={3}
            readOnly
            value={remoteAuth?.authUrl || ''}
            onFocus={(e) => e.target.select()}
            sx={{ mb: 2 }}
          />
          <Button variant="outlined" onClick={copyRemoteAuth}>{t('remote_auth_copy')}</Button>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setRemoteAuth(null)}>{t('cancel')}</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default BackupSettings;