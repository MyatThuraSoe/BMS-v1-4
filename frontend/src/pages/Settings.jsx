import { useState, useEffect } from 'react';
import {
  Box,
  Typography,
  TextField,
  Button,
  Grid,
  Alert,
  Switch,
  FormControlLabel,
  Paper,
  CircularProgress,
} from '@mui/material';
import { Backup as BackupIcon } from '@mui/icons-material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { systemSettingService, backupService } from '../api/services';
import { notifySuccess, notifyError } from '../utils/notify';

const Settings = () => {
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [backupLoading, setBackupLoading] = useState(false);
  const queryClient = useQueryClient();

  const { data: settingsData, isLoading } = useQuery({
    queryKey: ['settings'],
    queryFn: () => systemSettingService.getAll(),
  });

  const [settings, setSettings] = useState({});

  useEffect(() => {
    if (settingsData?.data) {
      const settingsMap = {};
      settingsData.data.forEach((setting) => {
        settingsMap[setting.settingKey] = setting;
      });
      setSettings(settingsMap);
    }
  }, [settingsData]);

  const updateMutation = useMutation({
    mutationFn: ({ key, value }) => systemSettingService.update(key, { settingValue: value }),
    onSuccess: () => {
      queryClient.invalidateQueries(['settings']);
      setSuccess('Settings updated successfully');
      setTimeout(() => setSuccess(''), 3000);
    },
    onError: (err) => {
      setError(err.response?.data?.message || 'Failed to update setting');
    },
  });

  const handleSettingChange = (key, value) => {
    setSettings((prev) => ({
      ...prev,
      [key]: { ...prev[key], settingValue: value },
    }));
  };

  const handleSave = (key) => {
    const setting = settings[key];
    if (setting) {
      updateMutation.mutate({ key, value: setting.settingValue });
    }
  };

  const handleDownloadBackup = async () => {
    setBackupLoading(true);
    try {
      const blob = await backupService.downloadFullBackup();
      const url = window.URL.createObjectURL(new Blob([blob]));
      const link = document.createElement('a');
      link.href = url;
      link.download = `bms-backup-${new Date().toISOString().slice(0, 10)}.xlsx`;
      link.click();
      window.URL.revokeObjectURL(url);
      notifySuccess('Backup downloaded successfully');
    } catch (err) {
      notifyError(err.friendlyMessage || 'Failed to download backup');
    } finally {
      setBackupLoading(false);
    }
  };

  const getSettingValue = (key) => {
    return settings[key]?.settingValue || '';
  };

  const renderSettingField = (setting) => {
    const { settingKey, settingValue, dataType, description } = setting;

    switch (dataType) {
      case 'BOOLEAN':
        return (
          <FormControlLabel
            control={
              <Switch
                checked={settingValue === 'true'}
                onChange={(e) => handleSettingChange(settingKey, e.target.checked ? 'true' : 'false')}
              />
            }
            label={settingValue === 'true' ? 'Enabled' : 'Disabled'}
          />
        );
      case 'INTEGER':
        return (
          <TextField
            type="number"
            value={settingValue}
            onChange={(e) => handleSettingChange(settingKey, e.target.value)}
            size="small"
            fullWidth
          />
        );
      case 'DECIMAL':
        return (
          <TextField
            type="number"
            value={settingValue}
            onChange={(e) => handleSettingChange(settingKey, e.target.value)}
            size="small"
            fullWidth
            inputProps={{ step: '0.01' }}
          />
        );
      default:
        return (
          <TextField
            value={settingValue}
            onChange={(e) => handleSettingChange(settingKey, e.target.value)}
            size="small"
            fullWidth
          />
        );
    }
  };

  if (isLoading) {
    return <Typography>Loading...</Typography>;
  }

  return (
    <Box>
      <Typography variant="h4" gutterBottom>
        System Settings
      </Typography>

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError('')}>
          {error}
        </Alert>
      )}

      {success && (
        <Alert severity="success" sx={{ mb: 2 }} onClose={() => setSuccess('')}>
          {success}
        </Alert>
      )}

      <Paper sx={{ p: 3, mb: 3 }}>
        <Typography variant="h6" gutterBottom>
          Data Backup
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          Download a complete snapshot of your business data as an Excel file. Store it somewhere
          safe (e.g. Google Drive, external drive) for disaster recovery.
        </Typography>
        <Button
          variant="contained"
          startIcon={backupLoading ? <CircularProgress size={18} color="inherit" /> : <BackupIcon />}
          onClick={handleDownloadBackup}
          disabled={backupLoading}
        >
          Download Full Backup
        </Button>
      </Paper>

      <Grid container spacing={3}>
        {Object.values(settings).map((setting) => (
          <Grid item xs={12} md={6} key={setting.id}>
            <Paper sx={{ p: 2 }}>
              <Typography variant="subtitle1" gutterBottom>
                {setting.description || setting.settingKey}
              </Typography>
              <Box sx={{ mb: 2 }}>
                {renderSettingField(setting)}
              </Box>
              <Button
                variant="contained"
                size="small"
                onClick={() => handleSave(setting.settingKey)}
                disabled={updateMutation.isPending}
              >
                Save
              </Button>
            </Paper>
          </Grid>
        ))}
      </Grid>
    </Box>
  );
};

export default Settings;
