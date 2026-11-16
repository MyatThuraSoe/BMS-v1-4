import { useEffect, useState } from 'react';
import {
  Box,
  Typography,
  Button,
  Paper,
  CircularProgress,
  Switch,
  FormControlLabel,
  Stack,
  TextField,
} from '@mui/material';
import { Backup as BackupIcon, DeleteSweep as DeleteSweepIcon } from '@mui/icons-material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { backupService, saleService, systemSettingService } from '../api/services';
import { notifySuccess, notifyError } from '../utils/notify';
import { useAuth } from '../context/AuthContext';
import ShutdownButton from '../components/ShutdownButton';
import LanguageSwitcher from '../components/LanguageSwitcher';
import HelpTip from '../components/HelpTip';

const CASH_DRAWER_SETTINGS = [
  {
    key: 'allow_shared_cash_drawer',
    labelKey: 'allow_shared_cash_drawer',
    helpKey: 'allow_shared_cash_drawer_help',
  },
  {
    key: 'require_manager_approval_shared_drawer',
    labelKey: 'require_manager_approval_shared_drawer',
    helpKey: 'require_manager_approval_shared_drawer_help',
  },
  {
    key: 'blind_drawer_close',
    labelKey: 'blind_drawer_close',
    helpKey: 'blind_drawer_close_help',
  },
];

const COUNT_AUTO_APPROVE_SETTINGS = [
  { key: 'stockCountAutoApproveLineValue', labelKey: 'count_auto_approve_line_value', helpKey: 'count_auto_approve_line_value_help', step: '0.01' },
  { key: 'stockCountAutoApproveSheetValue', labelKey: 'count_auto_approve_sheet_value', helpKey: 'count_auto_approve_sheet_value_help', step: '0.01' },
  { key: 'stockCountAutoApproveMaxLines', labelKey: 'count_auto_approve_max_lines', helpKey: 'count_auto_approve_max_lines_help', step: '1' },
];

// O5: how far a cashier and a manager may discount a sale, as a percent of it.
const DISCOUNT_LIMIT_SETTINGS = [
  { key: 'shop.discount.maxPercentCashier', labelKey: 'discount_max_percent_cashier', helpKey: 'discount_max_percent_cashier_help', step: '0.01', max: 100 },
  { key: 'shop.discount.maxPercentManager', labelKey: 'discount_max_percent_manager', helpKey: 'discount_max_percent_manager_help', step: '0.01', max: 100 },
];

const NUMERIC_SETTINGS = [...COUNT_AUTO_APPROVE_SETTINGS, ...DISCOUNT_LIMIT_SETTINGS];

const Settings = () => {
  const { t } = useTranslation(['settings', 'common']);
  const { isAdmin } = useAuth();
  const [backupLoading, setBackupLoading] = useState(false);
  const [deleteOldSalesLoading, setDeleteOldSalesLoading] = useState(false);
  const [cashVarianceTolerance, setCashVarianceTolerance] = useState('');
  const [countAutoApproveValues, setCountAutoApproveValues] = useState({});
  const queryClient = useQueryClient();

  const { data: settingsData } = useQuery({
    queryKey: ['system-settings'],
    queryFn: () => systemSettingService.getAll(),
    enabled: isAdmin(),
  });

  const settingValue = (key) => {
    const setting = (settingsData?.data || []).find((row) => row.settingKey === key);
    return String(setting?.settingValue ?? '').toLowerCase() === 'true';
  };

  const cashToleranceSetting = (settingsData?.data || [])
    .find((row) => row.settingKey === 'shop.cashVarianceTolerance');
  useEffect(() => {
    if (cashToleranceSetting && cashVarianceTolerance === '') {
      setCashVarianceTolerance(cashToleranceSetting.settingValue);
    }
  }, [cashToleranceSetting, cashVarianceTolerance]);

  useEffect(() => {
    const values = {};
    NUMERIC_SETTINGS.forEach(({ key }) => {
      const setting = (settingsData?.data || []).find((row) => row.settingKey === key);
      if (setting && countAutoApproveValues[key] === undefined) {
        values[key] = setting.settingValue;
      }
    });
    if (Object.keys(values).length) {
      setCountAutoApproveValues((current) => ({ ...current, ...values }));
    }
  }, [settingsData, countAutoApproveValues]);

  const updateSettingMutation = useMutation({
    mutationFn: ({ key, value }) => systemSettingService.update(key, {
      settingValue: String(value),
    }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['system-settings'] });
      notifySuccess(t('setting_updated'));
    },
    onError: (err) => notifyError(err.friendlyMessage || t('setting_update_failed')),
  });

  // One admin-editable number field; saved when the operator leaves the box.
  const numericField = (setting) => {
    const stored = (settingsData?.data || [])
      .find((row) => row.settingKey === setting.key)?.settingValue;
    return (
      <TextField
        key={setting.key}
        label={t(setting.labelKey)}
        type="number"
        size="small"
        value={countAutoApproveValues[setting.key] ?? ''}
        onChange={(e) => setCountAutoApproveValues((current) => ({
          ...current,
          [setting.key]: e.target.value,
        }))}
        onBlur={() => {
          const value = Number(countAutoApproveValues[setting.key]);
          if (Number.isFinite(value) && value >= 0
              && (setting.step !== '1' || Number.isInteger(value))
              && String(value) !== String(stored ?? '')) {
            updateSettingMutation.mutate({
              key: setting.key,
              value: countAutoApproveValues[setting.key],
            });
          }
        }}
        inputProps={{ min: 0, max: setting.max, step: setting.step }}
        helperText={t(setting.helpKey)}
        disabled={updateSettingMutation.isPending}
      />
    );
  };

  const handleDownloadBackup = async () => {    setBackupLoading(true);
    try {
      const blob = await backupService.downloadFullBackup();
      const url = window.URL.createObjectURL(new Blob([blob]));
      const link = document.createElement('a');
      link.href = url;
      link.download = `lumipos-backup-${new Date().toISOString().slice(0, 10)}.json`;
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
          <HelpTip helpKey="help_system_data_backup" t={t} />
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
          <HelpTip helpKey="help_old_sales_cleanup" t={t} />
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

      {isAdmin() && (
        <Paper sx={{ p: 3, mb: 3 }}>
          <Typography variant="h6" gutterBottom>
            {t('cash_drawer_security')}
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            {t('cash_drawer_security_description')}
          </Typography>
          <Stack spacing={1.5}>
            {CASH_DRAWER_SETTINGS.map((setting) => (
              <Box key={setting.key}>
                <FormControlLabel
                  control={(
                    <Switch
                      checked={settingValue(setting.key)}
                      onChange={(e) => updateSettingMutation.mutate({
                        key: setting.key,
                        value: e.target.checked,
                      })}
                      disabled={updateSettingMutation.isPending}
                    />
                  )}
                  label={t(setting.labelKey)}
                />
                <Typography variant="caption" color="text.secondary" sx={{ display: 'block', ml: 6 }}>
                  {t(setting.helpKey)}
                </Typography>
              </Box>
            ))}
            <TextField
              label={t('cash_variance_tolerance')}
              type="number"
              size="small"
              value={cashVarianceTolerance}
              onChange={(e) => setCashVarianceTolerance(e.target.value)}
              onBlur={() => {
                const value = Number(cashVarianceTolerance);
                if (Number.isFinite(value) && value >= 0
                    && String(value) !== String(cashToleranceSetting?.settingValue ?? '10.00')) {
                  updateSettingMutation.mutate({
                    key: 'shop.cashVarianceTolerance',
                    value: cashVarianceTolerance,
                  });
                }
              }}
              inputProps={{ min: 0, step: '0.01' }}
              helperText={t('cash_variance_tolerance_help')}
              disabled={updateSettingMutation.isPending}
            />
          </Stack>
        </Paper>
      )}

      {isAdmin() && (
        <Paper sx={{ p: 3, mb: 3 }}>
          <Typography variant="h6" gutterBottom>
            {t('stock_count_auto_approval')}
          </Typography>
          <Stack spacing={1.5}>
            {COUNT_AUTO_APPROVE_SETTINGS.map(numericField)}
          </Stack>
        </Paper>
      )}

      {isAdmin() && (
        <Paper sx={{ p: 3, mb: 3 }}>
          <Typography variant="h6" gutterBottom>
            {t('discount_limits')}
            <HelpTip helpKey="help_system_discount_limits" t={t} />
          </Typography>
          <Stack spacing={1.5}>
            {DISCOUNT_LIMIT_SETTINGS.map(numericField)}
          </Stack>
        </Paper>
      )}

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
