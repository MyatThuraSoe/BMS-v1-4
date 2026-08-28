import { useEffect, useMemo, useState } from 'react';
import { Box, Typography, Paper, TextField, MenuItem, Button, Alert, CircularProgress, FormControlLabel, Switch } from '@mui/material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { shopInfoService } from '../api/services';
import { setCurrencyCode } from '../utils/helpers';

import { CloudUpload as UploadIcon, Delete as DeleteIcon, Save as SaveIcon } from '@mui/icons-material';
import { useAuth } from '../context/AuthContext';
import ShopLogo, { clearShopLogoCache } from '../components/ShopLogo';
import { notifyInfo } from '../utils/notify';
const SHOP_TYPES = ['MINI_MART','GROCERY','PHARMACY','FURNITURE_SHOP','ELECTRONICS','CLOTHING','RESTAURANT','OTHER'];

const CURRENCIES = [
  { code: 'USD', label: 'US Dollar ($)' },
  { code: 'EUR', label: 'Euro (€)' },
  { code: 'GBP', label: 'British Pound (£)' },
  { code: 'THB', label: 'Thai Baht (฿)' },
  { code: 'MMK', label: 'Myanmar Kyat (K)' },
  { code: 'SGD', label: 'Singapore Dollar (S$)' },
  { code: 'INR', label: 'Indian Rupee (₹)' },
];

const parseTaxPercentage = (v) => {
  if (v == null || v === '') return '';
  const num = Number(v);
  return Number.isFinite(num) ? String(num) : '';
};

// Focused number inputs change value when scrolling the page — blur them on
// wheel so scrolling never mutates a typed amount.
const preventWheelChange = (e) => {
  if (e.target === document.activeElement) e.target.blur();
};

const ShopInfo = () => {

  const { t } = useTranslation('settings');

  const [logoPreview, setLogoPreview] = useState(null);
  const [logoRefresh, setLogoRefresh] = useState(0);

  const queryClient = useQueryClient();
  const { isAdmin } = useAuth();

  const { data, isLoading, error } = useQuery({
    queryKey: ['shopInfo'],
    queryFn: () => shopInfoService.get(),
    enabled: isAdmin(),
  });

  const [form, setForm] = useState({
    shopName: '',
    shopType: 'OTHER',
    address: '',
    phone: '',
    email: '',
    currency: 'USD',
    taxPercentage: '0',
    discountEnabled: false,
    discountType: 'PERCENTAGE',
    discountValue: '0',
  });

  useEffect(() => {
    if (!data?.data) return;
    const d = data.data;
    setForm({
      shopName: d.shopName || '',
      shopType: d.shopType || 'OTHER',
      address: d.address || '',
      phone: d.phone || '',
      email: d.email || '',
      currency: d.currency || 'USD',
      taxPercentage: parseTaxPercentage(d.taxPercentage) || '0',
      discountEnabled: Boolean(d.discountEnabled),
      discountType: ['AMOUNT', 'FIXED'].includes(d.discountType) ? d.discountType : 'PERCENTAGE',
      discountValue: parseTaxPercentage(d.discountValue) || '0',
    });
  }, [data]);

  const [logoFile, setLogoFile] = useState(null);

  const updateMutation = useMutation({
    mutationFn: (payload) => shopInfoService.update(payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['shopInfo'] });
      notifyInfo(t('restart_app_to_apply_changes'));
    },
  });

  const updateError = updateMutation.error?.response?.status === 409
    ? t('shop_info_conflict')
    : updateMutation.error?.response?.data?.message || null;

  const uploadLogoMutation = useMutation({
      mutationFn: (file) => shopInfoService.uploadLogo(file),

      onSuccess: async () => {

          if (logoPreview) {
              URL.revokeObjectURL(logoPreview);
          }
          clearShopLogoCache();
          setLogoRefresh(v => v + 1);

          setLogoPreview(null);
          setLogoFile(null);

          await queryClient.invalidateQueries({
              queryKey: ['shopInfo'],
          });

          setLogoRefresh(v => v + 1);
          setLogoFile(null);
            notifyInfo(t('restart_app_to_apply_changes'));
      }
    });

  const deleteLogoMutation = useMutation({
      mutationFn: () => shopInfoService.deleteLogo(),

      onSuccess: async () => {

        if (logoPreview) {
            URL.revokeObjectURL(logoPreview);
        }
        clearShopLogoCache();
        setLogoRefresh(v => v + 1);
        setLogoPreview(null);
        setLogoFile(null);

        await queryClient.invalidateQueries({
            queryKey: ['shopInfo'],
        });

        setLogoRefresh(v => v + 1);
        notifyInfo(t('restart_app_to_apply_changes'));
    }
    });

  const handleSave = () => {
    if (!form.shopName?.trim()) return;
    updateMutation.mutate({
      shopName: form.shopName,
      shopType: form.shopType,
      address: form.address,
      phone: form.phone,
      email: form.email,
      currency: form.currency,
      taxPercentage: parseTaxPercentage(form.taxPercentage) || '0',
      discountEnabled: form.discountEnabled,
      discountType: form.discountType,
      discountValue: parseTaxPercentage(form.discountValue) || '0',
    });
    setCurrencyCode(form.currency);
  };

  if (!isAdmin()) {
    return <Typography color="text.secondary">{t('not_authorized')}</Typography>;
  }

  if (isLoading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', mt: 4 }}>
        <CircularProgress />
      </Box>
    );
  }

  if (error) {
    return <Alert severity="error">{t('load_shop_info_failed')}</Alert>;
  }

  return (
    <Box>
      {/* // banner appear telling shop info has been changed  */}
      {updateMutation.isSuccess && (
        <Alert severity="success" sx={{ mb: 2 }}>
          {t('shop_info_updated')}
        </Alert>
      )}
      <Typography variant="h4" gutterBottom>
        {t('edit_shop_info')}
      </Typography>

      <GridLayout>
        <Paper sx={{ p: 3 }}>
          <Typography variant="h6" gutterBottom>
            {t('shop_details')}
          </Typography>

          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            <TextField
              required
              label={t('shop_name')}
              value={form.shopName}
              onChange={(e) => setForm((p) => ({ ...p, shopName: e.target.value }))}
              fullWidth
            />

            <TextField
              select
              label={t('shop_type')}
              value={form.shopType}
              onChange={(e) => setForm((p) => ({ ...p, shopType: e.target.value }))}
              fullWidth
            >
              {SHOP_TYPES.map((t) => (
                <MenuItem key={t} value={t}>
                  {t}
                </MenuItem>
              ))}
            </TextField>

            <TextField
              select
              label={t('currency')}
              value={form.currency}
              onChange={(e) => setForm((p) => ({ ...p, currency: e.target.value }))}
              fullWidth
              helperText={t('currency_helper')}
            >
              {CURRENCIES.map((c) => (
                <MenuItem key={c.code} value={c.code}>
                  {c.label}
                </MenuItem>
              ))}
            </TextField>

            <TextField
              label={t('tax_percentage')}
              type="number"
              value={form.taxPercentage}
              onChange={(e) => setForm((p) => ({ ...p, taxPercentage: parseTaxPercentage(e.target.value) }))}
              onWheel={preventWheelChange}
              fullWidth
              helperText={t('tax_percentage_helper')}
              inputProps={{ inputMode: 'decimal', min: 0, max: 100, step: '0.0001' }}
              InputProps={{
                endAdornment: <span>%</span>,
              }}
            />

            <Paper variant="outlined" sx={{ p: 2 }}>
              <FormControlLabel
                control={
                  <Switch
                    checked={form.discountEnabled}
                    onChange={(e) => setForm((p) => ({ ...p, discountEnabled: e.target.checked }))}
                  />
                }
                label={t('discount_enabled')}
              />
              {form.discountEnabled && (
                <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, mt: 1 }}>
                  <TextField
                    select
                    label={t('discount_type')}
                    value={form.discountType}
                    onChange={(e) => setForm((p) => ({ ...p, discountType: e.target.value }))}
                    fullWidth
                    helperText={t('discount_type_helper')}
                  >
                    <MenuItem value="PERCENTAGE">{t('discount_type_percentage')}</MenuItem>
                    <MenuItem value="FIXED">{t('discount_type_fixed')}</MenuItem>
                    <MenuItem value="AMOUNT">{t('discount_type_amount')}</MenuItem>
                  </TextField>

                  {form.discountType === 'PERCENTAGE' && (
                    <TextField
                      label={t('discount_value')}
                      type="number"
                      value={form.discountValue}
                      onChange={(e) => setForm((p) => ({ ...p, discountValue: parseTaxPercentage(e.target.value) }))}
                      onWheel={preventWheelChange}
                      fullWidth
                      helperText={t('discount_percentage_helper')}
                      inputProps={{ inputMode: 'decimal', min: 0, max: 100, step: '0.01' }}
                      InputProps={{ endAdornment: <span>%</span> }}
                    />
                  )}
                  {form.discountType === 'FIXED' && (
                    <TextField
                      label={t('discount_fixed_value')}
                      type="number"
                      value={form.discountValue}
                      onChange={(e) => setForm((p) => ({ ...p, discountValue: parseTaxPercentage(e.target.value) }))}
                      onWheel={preventWheelChange}
                      fullWidth
                      helperText={t('discount_fixed_helper')}
                      inputProps={{ inputMode: 'decimal', min: 0, step: '0.01' }}
                    />
                  )}
                  {form.discountType === 'AMOUNT' && (
                    <Alert severity="info">{t('discount_amount_mode_hint')}</Alert>
                  )}
                </Box>
              )}
            </Paper>

            <TextField
              label={t('address')}
              value={form.address}
              onChange={(e) => setForm((p) => ({ ...p, address: e.target.value }))}
              fullWidth
              multiline
              minRows={3}
            />

            <TextField
              label={t('phone')}
              value={form.phone}
              onChange={(e) => setForm((p) => ({ ...p, phone: e.target.value }))}
              fullWidth
            />

            <TextField
              label={t('email')}
              value={form.email}
              onChange={(e) => setForm((p) => ({ ...p, email: e.target.value }))}
              fullWidth
            />

            <Box sx={{ display: 'flex', gap: 1, mt: 1 }}>
              <Button
                variant="contained"
                startIcon={<SaveIcon />}
                onClick={handleSave}
                disabled={updateMutation.isPending || !form.shopName?.trim()}
              >
                {t('save')}
              </Button>
            </Box>

            {updateError && (
              <Alert severity="error">{updateError}</Alert>
            )}
          </Box>
        </Paper>

        <Paper sx={{ p: 3 }}>
          <Typography variant="h6" gutterBottom>
            {t('logo')}
          </Typography>

          <Box sx={{ display: 'flex', gap: 2, alignItems: 'center', flexWrap: 'wrap' }}>
            <ShopLogo
                size={110}
                hasLogo={data?.data?.hasLogo}
                preview={logoPreview}
                refreshTrigger={logoRefresh}
            />
            

            <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1, flex: 1, minWidth: 220 }}>
              <Button
                variant="outlined"
                component="label"
                startIcon={<UploadIcon />}
                disabled={uploadLogoMutation.isPending}
              >
                {t('upload_logo')}
                <input
                    hidden
                    type="file"
                    accept="image/*"
                    onChange={(e) => {

                        const file = e.target.files?.[0];

                        setLogoFile(file || null);

                        if (logoPreview) {
                            URL.revokeObjectURL(logoPreview);
                        }

                        if (file) {
                            setLogoPreview(URL.createObjectURL(file));
                        } else {
                            setLogoPreview(null);
                        }

                    }}
                />
              </Button>

              <Button
                variant="contained"
                disabled={!logoFile || uploadLogoMutation.isPending}
                onClick={() => logoFile && uploadLogoMutation.mutate(logoFile)}
              >
                {uploadLogoMutation.isPending ? t('uploading') : t('replace_logo')}
              </Button>

              <Button
                variant="text"
                color="error"
                startIcon={<DeleteIcon />}
                disabled={deleteLogoMutation.isPending}
                onClick={() => deleteLogoMutation.mutate()}
              >
                {t('delete_logo')}
              </Button>

              {uploadLogoMutation.isError && (
                <Alert severity="error">{t('upload_logo_failed')}</Alert>
              )}
              {deleteLogoMutation.isError && (
                <Alert severity="error">{t('delete_logo_failed')}</Alert>
              )}
            </Box>
          </Box>
        </Paper>
      </GridLayout>

      
    </Box>
  );
};

const GridLayout = ({ children }) => {
  return <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' }, gap: 2 }}>{children}</Box>;
};

export default ShopInfo;

