import { useState } from 'react';
import {
  Box,
  Typography,
  Button,
  Paper,
  CircularProgress,
} from '@mui/material';
import { Backup as BackupIcon, DeleteSweep as DeleteSweepIcon } from '@mui/icons-material';
import { useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { backupService, saleService } from '../api/services';
import { notifySuccess, notifyError } from '../utils/notify';
import ShutdownButton from '../components/ShutdownButton';
import LanguageSwitcher from '../components/LanguageSwitcher';

const Settings = () => {
  const { t } = useTranslation('settings');
  const [backupLoading, setBackupLoading] = useState(false);
  const [deleteOldSalesLoading, setDeleteOldSalesLoading] = useState(false);
  const queryClient = useQueryClient();

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

  return (
    <Box>
      <Typography variant="h4" gutterBottom>
        {t('system_settings')}
      </Typography>

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
