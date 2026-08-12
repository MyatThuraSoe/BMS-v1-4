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
import { Backup as BackupIcon, DeleteSweep as DeleteSweepIcon } from '@mui/icons-material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { systemSettingService, backupService, saleService } from '../api/services';
import { notifySuccess, notifyError } from '../utils/notify';
import ShutdownButton from '../components/ShutdownButton';
import LanguageSwitcher from '../components/LanguageSwitcher';

const Settings = () => {
  const { t } = useTranslation('settings');
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [backupLoading, setBackupLoading] = useState(false);
  const [deleteOldSalesLoading, setDeleteOldSalesLoading] = useState(false);
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
      queryClient.invalidateQueries({ queryKey: ['settings'] });
      setSuccess(t('settings_updated'));
      setTimeout(() => setSuccess(''), 3000);
    },
    onError: (err) => {
      setError(err.response?.data?.message || t('update_setting_failed'));
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
      notifySuccess(t('backup_downloaded'));
    } catch (err) {
      notifyError(err.friendlyMessage || t('backup_download_failed'));
    } finally {
      setBackupLoading(false);
    }
  };

  const handleDeleteOldSales = async () => {
    if (!window.confirm(t('delete_old_sales_confirm'))) {
      return;
    }

    setDeleteOldSalesLoading(true);
    try {
      const response = await saleService.deleteOld(1);
      const deletedSales = response?.data?.deletedSales ?? 0;
      const cutoffDate = response?.data?.cutoffDate;
      notifySuccess(t('old_sales_deleted', { count: deletedSales, date: cutoffDate }));
      queryClient.invalidateQueries({ queryKey: ['sales'] });
    } catch (err) {
      notifyError(err.friendlyMessage || t('delete_old_sales_failed'));
    } finally {
      setDeleteOldSalesLoading(false);
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
            label={settingValue === 'true' ? t('enabled') : t('disabled')}
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
    return <Typography>{t('loading')}</Typography>;
  }

  return (
    <Box>
      <Typography variant="h4" gutterBottom>
        {t('system_settings')}
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
          {t('data_backup')}
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          {t('data_backup_description')}
        </Typography>
        <Button
          variant="contained"
          startIcon={backupLoading ? <CircularProgress size={18} color="inherit" /> : <BackupIcon />}
          onClick={handleDownloadBackup}
          disabled={backupLoading}
        >
          {t('download_full_backup')}
        </Button>
      </Paper>

      <Paper sx={{ p: 3, mb: 3 }}>
        <Typography variant="h6" gutterBottom color="error.main">
          {t('old_sales_cleanup')}
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          {t('old_sales_cleanup_description')}
        </Typography>
        <Button
          variant="contained"
          color="error"
          startIcon={deleteOldSalesLoading ? <CircularProgress size={18} color="inherit" /> : <DeleteSweepIcon />}
          onClick={handleDeleteOldSales}
          disabled={deleteOldSalesLoading}
        >
          {deleteOldSalesLoading ? t('deleting_old_sales') : t('delete_sales_older_than_one_year')}
        </Button>
      </Paper>

      <Paper sx={{ p: 3, mb: 3 }}>
        <Typography variant="h6" gutterBottom>
          {t('language_preferences')}
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          {t('language_preferences_description')}
        </Typography>
        <LanguageSwitcher />
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
                {t('save')}
              </Button>
            </Paper>
          </Grid>
        ))}
      </Grid>

        <Paper sx={{ p: 3, mt: 3 }}>
          <Typography variant="h6" gutterBottom>
            {t('system_shutdown')}
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            {t('system_shutdown_description')}
          </Typography>
          <ShutdownButton />
        </Paper>
      
    </Box>
  );
};

export default Settings;
