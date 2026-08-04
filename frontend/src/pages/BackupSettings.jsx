import { useState, useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  Box, Typography, Card, CardContent, FormControl, FormControlLabel, Switch,
  Select, MenuItem, Button, TextField, CircularProgress, Alert, Divider, Chip
} from '@mui/material';
import { 
  CloudUpload as CloudUploadIcon, 
  Refresh as RefreshIcon, 
  Save as SaveIcon,
  Link as LinkIcon,
  LinkOff as LinkOffIcon,

} from '@mui/icons-material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { backupService } from '../api/services';



// Helper to format date (replace with your existing helper if you have one)
const formatDateTime = (dateString) => {
  if (!dateString) return 'Never';
  return new Date(dateString).toLocaleString();
};

const BackupSettings = () => {
  const [searchParams] = useSearchParams();
  const queryClient = useQueryClient();
  
  const [isRunning, setIsRunning] = useState(false);
  const [message, setMessage] = useState(null);

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
      setMessage({ type: 'success', text: 'Google Drive connected successfully!' });
      queryClient.invalidateQueries(['backupSettings']);
      window.history.replaceState({}, document.title, window.location.pathname); // Clean URL
    } else if (status === 'error') {
      setMessage({ type: 'error', text: 'Failed to connect Google Drive. Please try again.' });
      window.history.replaceState({}, document.title, window.location.pathname);
    }
  }, [searchParams, queryClient]);

  // 4. Mutations
  const updateMutation = useMutation({
    mutationFn: backupService.updateSettings,
    onSuccess: () => {
      queryClient.invalidateQueries(['backupSettings']);
      setMessage({ type: 'success', text: 'Backup settings saved successfully.' });
    },
    onError: () => {
      setMessage({ type: 'error', text: 'Failed to save settings.' });
    }
  });

  const handleSave = () => {
    updateMutation.mutate(settings);
  };

  const handleConnect = async () => {
    try {
      const authUrl = await backupService.getConnectUrl();
      // Redirect user to Google's consent screen
      window.location.href = authUrl;
    } catch (error) {
      setMessage({ type: 'error', text: 'Failed to get connection URL.' });
    }
  };

  const handleDisconnect = async () => {
    if (window.confirm("Are you sure you want to disconnect Google Drive? Automated backups will stop.")) {
      try {
        await backupService.disconnect();
        queryClient.invalidateQueries(['backupSettings']);
        setMessage({ type: 'info', text: 'Google Drive disconnected.' });
      } catch (error) {
        setMessage({ type: 'error', text: 'Failed to disconnect.' });
      }
    }
  };

  // Update the handleRunNow function:
  const handleRunNow = async () => {
    setIsRunning(true);
    setMessage(null);
    try {
      const res = await backupService.runNow(dateRange.startDate || null, dateRange.endDate || null);
      setMessage({ type: 'success', text: `${res.message} Saved to: ${res.data}` });
      queryClient.invalidateQueries(['backupSettings']);
    } catch (error) {
      setMessage({ type: 'error', text: error.response?.data?.message || 'Backup failed. Check backend logs.' });
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
        Backup & Restore
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        Automatically back up your entire database to your personal Google Drive as an Excel file.
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
            <Typography variant="h6">Google Drive Connection</Typography>
            {isConnected && <Chip label="Connected" color="success" size="small" />}
          </Box>
          
          <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
            {isConnected 
              ? "Your database backups will be automatically saved to your connected Google Drive account." 
              : "Connect your Google account to enable automated cloud backups. (You will be asked to grant permission to create files)."}
          </Typography>

          {isConnected ? (
            <Button variant="outlined" color="error" startIcon={<LinkOffIcon />} onClick={handleDisconnect}>
              Disconnect Google Drive
            </Button>
          ) : (
            <Button variant="contained" color="primary" startIcon={<LinkIcon />} onClick={handleConnect}>
              Connect Google Drive
            </Button>
          )}
        </CardContent>
      </Card>

      {/* Backup Settings Card */}
      <Card sx={{ mb: 3 }}>
        <CardContent>
          <Typography variant="h6" gutterBottom>Automation Settings</Typography>
          <Divider sx={{ mb: 3 }} />

          <FormControlLabel
            control={
              <Switch
                checked={settings.isEnabled}
                onChange={(e) => setSettings({ ...settings, isEnabled: e.target.checked })}
                disabled={!isConnected}
              />
            }
            label={<Typography variant="subtitle1">Enable Automated Backups</Typography>}
            sx={{ mb: 3 }}
          />

          <FormControl fullWidth sx={{ mb: 3 }}>
            <Typography variant="subtitle2" sx={{ mb: 1, color: 'text.secondary' }}>Backup Frequency</Typography>
            <Select
              value={settings.frequency}
              onChange={(e) => setSettings({ ...settings, frequency: e.target.value })}
              disabled={!settings.isEnabled || !isConnected}
            >
              <MenuItem value="DAILY">Daily</MenuItem>
              <MenuItem value="WEEKLY">Weekly</MenuItem>
              <MenuItem value="MONTHLY">Monthly</MenuItem>
              <MenuItem value="YEARLY">Yearly</MenuItem>
              <MenuItem value="CUSTOM">Custom (Cron Expression)</MenuItem>
            </Select>
          </FormControl>

          {settings.frequency === 'CUSTOM' && (
            <TextField
              fullWidth
              label="Custom Cron Expression"
              placeholder="e.g., 0 0 2 * * ? (Every day at 2 AM)"
              value={settings.customCronExpression}
              onChange={(e) => setSettings({ ...settings, customCronExpression: e.target.value })}
              disabled={!settings.isEnabled || !isConnected}
              sx={{ mb: 3 }}
              helperText="Format: Seconds Minutes Hours Day-of-Month Month Day-of-Week"
            />
          )}

          <Divider sx={{ my: 3 }} />
            <Typography variant="subtitle1" gutterBottom>Custom Date Range (Optional)</Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
              Leave blank to backup ALL data. Select a range to backup only transactions within these dates.
            </Typography>

            <Box sx={{ display: 'flex', gap: 2, mb: 3, flexWrap: 'wrap' }}>
              <TextField
                label="Start Date"
                type="date"
                value={dateRange.startDate}
                onChange={(e) => setDateRange({ ...dateRange, startDate: e.target.value })}
                InputLabelProps={{ shrink: true }}
                size="small"
                sx={{ flex: 1, minWidth: '150px' }}
              />
              <TextField
                label="End Date"
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
                  Clear Range
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
              {updateMutation.isPending ? 'Saving...' : 'Save Settings'}
            </Button>
            
            <Button
              variant="outlined"
              startIcon={isRunning ? <CircularProgress size={20} /> : <RefreshIcon />}
              onClick={handleRunNow}
              disabled={isRunning || !isConnected}
            >
              {isRunning ? 'Running Backup...' : 'Run Backup Now'}
            </Button>
          </Box>
        </CardContent>
      </Card>

      {/* Status Card */}
      <Card>
        <CardContent>
          <Typography variant="h6" gutterBottom>Backup Status</Typography>
          <Divider sx={{ mb: 2 }} />
          <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 1 }}>
            <Typography variant="body2" color="text.secondary">Last Successful Backup:</Typography>
            <Typography variant="body2" fontWeight="medium">
              {formatDateTime(lastBackup)}
            </Typography>
          </Box>
          <Box sx={{ display: 'flex', justifyContent: 'space-between' }}>
            <Typography variant="body2" color="text.secondary">Next Scheduled Backup:</Typography>
            <Typography variant="body2" fontWeight="medium" color={settings.isEnabled && isConnected ? 'success.main' : 'text.disabled'}>
              {settings.isEnabled && isConnected ? formatDateTime(nextBackup) : 'Not scheduled'}
            </Typography>
          </Box>
        </CardContent>
      </Card>
    </Box>
  );
};

export default BackupSettings;