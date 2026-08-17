import { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  Divider,
  Grid,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { Save as SaveIcon, Preview as PreviewIcon } from '@mui/icons-material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '../context/AuthContext';
import { receiptCustomizationService, shopInfoService } from '../api/services';
import { formatReceiptDateTime } from '../utils/helpers';

const PAPER_SIZES = ['58', '80', '100'];
const TIME_FORMATS = [
  { value: '12', label: '12-hour (1:30 pm)' },
  { value: '24', label: '24-hour (13:30)' },
];

const defaultCustomization = {
  headerText: 'Thank you for shopping with us',
  mainMessage: 'Please keep this receipt for your records.',
  footerText: 'Thank you for your business!',
  paperSize: '58',
  timeFormat: '12',
};

const ReceiptCustomization = () => {
  const { isAdmin } = useAuth();
  const queryClient = useQueryClient();

  const { data: customizationData, isLoading: loadingCustomization } = useQuery({
    queryKey: ['receipt-customization'],
    queryFn: () => receiptCustomizationService.get(),
    enabled: isAdmin(),
  });

  const { data: shopInfoData, isLoading: loadingShopInfo } = useQuery({
    queryKey: ['shopInfo-preview'],
    queryFn: () => shopInfoService.get(),
    enabled: isAdmin(),
  });

  const [form, setForm] = useState(defaultCustomization);

  useEffect(() => {
    if (customizationData?.data) {
      setForm({
        headerText: customizationData.data.headerText ?? defaultCustomization.headerText,
        mainMessage: customizationData.data.mainMessage || defaultCustomization.mainMessage,
        footerText: customizationData.data.footerText || defaultCustomization.footerText,
        paperSize: customizationData.data.paperSize || defaultCustomization.paperSize,
        timeFormat: customizationData.data.timeFormat || defaultCustomization.timeFormat,
      });
    }
  }, [customizationData]);

  const upsertMutation = useMutation({
    mutationFn: (payload) => receiptCustomizationService.upsert(payload),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['receipt-customization'] });
    },
  });

  const shopInfo = shopInfoData?.data || {};
  const bannerText = form.headerText?.trim() ?? '';
  const mainText = form.mainMessage?.trim() || defaultCustomization.mainMessage;
  const footerText = form.footerText?.trim() || defaultCustomization.footerText;
  const paperWidth = Number.parseInt(String(form.paperSize || '58').replace(/\D/g, ''), 10) || 58;

  const receiptPreview = useMemo(() => ({
    shopName: shopInfo.shopName || 'Your Shop',
    address: shopInfo.address || '123 Market Street',
    phone: shopInfo.phone || '+1 234 567 890',
    headerText: bannerText,
    mainMessage: mainText,
    footerText: footerText,
  }), [shopInfo, bannerText, mainText, footerText]);

  if (!isAdmin()) {
    return <Typography color="text.secondary">Not authorized</Typography>;
  }

  if (loadingCustomization || loadingShopInfo) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', mt: 4 }}>
        <CircularProgress />
      </Box>
    );
  }

  return (
    <Box sx={{ maxWidth: 1200, mx: 'auto' }}>
      <Typography variant="h4" gutterBottom>
        Receipt Customization
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        Customize the text that appears on printed invoices and preview it before saving.
      </Typography>

      <Grid container spacing={3}>
        <Grid item xs={12} md={6}>
          <Paper sx={{ p: 3 }}>
            <Stack spacing={2}>
              <TextField
                label="Header text"
                value={form.headerText}
                onChange={(e) => setForm((prev) => ({ ...prev, headerText: e.target.value }))}
                fullWidth
                helperText="Appears above the invoice details. Leave blank to hide."
              />

              <TextField
                label="Main message"
                value={form.mainMessage}
                onChange={(e) => setForm((prev) => ({ ...prev, mainMessage: e.target.value }))}
                fullWidth
                multiline
                minRows={2}
                helperText="Added near the middle of the receipt"
              />

              <TextField
                label="Footer text"
                value={form.footerText}
                onChange={(e) => setForm((prev) => ({ ...prev, footerText: e.target.value }))}
                fullWidth
                helperText="Shown at the bottom of the receipt"
              />

              <Box>
                <Typography variant="subtitle2" sx={{ mb: 1 }}>
                  Paper size
                </Typography>
                <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
                  {PAPER_SIZES.map((size) => (
                    <Button
                      key={size}
                      variant={form.paperSize === size ? 'contained' : 'outlined'}
                      onClick={() => setForm((prev) => ({ ...prev, paperSize: size }))}
                    >
                      {size} mm
                    </Button>
                  ))}
                </Stack>
              </Box>

              <Box>
                <Typography variant="subtitle2" sx={{ mb: 1 }}>
                  Time format
                </Typography>
                <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
                  {TIME_FORMATS.map((fmt) => (
                    <Button
                      key={fmt.value}
                      variant={form.timeFormat === fmt.value ? 'contained' : 'outlined'}
                      onClick={() => setForm((prev) => ({ ...prev, timeFormat: fmt.value }))}
                    >
                      {fmt.label}
                    </Button>
                  ))}
                </Stack>
              </Box>

              <Divider />

              <Button
                variant="contained"
                startIcon={upsertMutation.isPending ? <CircularProgress size={18} color="inherit" /> : <SaveIcon />}
                onClick={() => upsertMutation.mutate(form)}
                disabled={upsertMutation.isPending}
                sx={{ alignSelf: 'flex-start' }}
              >
                {upsertMutation.isPending ? 'Saving...' : 'Save customization'}
              </Button>

              {upsertMutation.isSuccess && (
                <Alert severity="success">Receipt customization saved.</Alert>
              )}
              {upsertMutation.isError && (
                <Alert severity="error">Failed to save receipt customization.</Alert>
              )}
            </Stack>
          </Paper>
        </Grid>

        <Grid item xs={12} md={6}>
          <Paper sx={{ p: 3 }}>
            <Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ mb: 2 }}>
              <Typography variant="h6">Preview</Typography>
              <Typography variant="caption" color="text.secondary">{form.paperSize} mm</Typography>
            </Stack>

            <Box
              sx={{
                mx: 'auto',
                width: '100%',
                maxWidth: `${Math.min(420, (paperWidth / 100) * 300)}px`,
                border: '1px solid',
                borderColor: 'divider',
                borderRadius: 2,
                background: '#fff',
                color: '#111',
                p: 2,
                fontFamily: 'monospace',
                fontSize: '0.8rem',
                boxShadow: 1,
              }}
            >
              <Box sx={{ textAlign: 'center', mb: 2 }}>
                <Typography variant="h6" sx={{ fontWeight: 700, fontFamily: 'inherit' }}>
                  {receiptPreview.shopName}
                </Typography>
                <Typography sx={{ fontFamily: 'inherit' }}>{receiptPreview.address}</Typography>
                <Typography sx={{ fontFamily: 'inherit' }}>{receiptPreview.phone}</Typography>
                <Typography sx={{ fontFamily: 'inherit', mt: 1, fontWeight: 700 }}>RECEIPT</Typography>
              </Box>

              <Divider sx={{ borderStyle: 'dashed', my: 1 }} />

              {receiptPreview.headerText && (
                <Typography sx={{ my: 1, fontFamily: 'inherit', textAlign: 'center', fontWeight: 700 }}>
                  {receiptPreview.headerText}
                </Typography>
              )}

              <Typography sx={{ my: 1, fontFamily: 'inherit', textAlign: 'center' }}>
                {receiptPreview.mainMessage}
              </Typography>

              <Divider sx={{ borderStyle: 'dashed', my: 1 }} />

              <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 0.5 }}>
                <Typography sx={{ fontFamily: 'inherit' }}>Invoice No:</Typography>
                <Typography sx={{ fontFamily: 'inherit' }}>INV-1001</Typography>
              </Box>
              <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 0.5 }}>
                <Typography sx={{ fontFamily: 'inherit' }}>Date</Typography>
                <Typography sx={{ fontFamily: 'inherit' }}>{formatReceiptDateTime(new Date(), form.timeFormat)}</Typography>
              </Box>

              <Box sx={{ my: 2 }}>
                <Box sx={{ display: 'flex', justifyContent: 'space-between' }}>
                  <Typography sx={{ fontFamily: 'inherit' }}>Item</Typography>
                  <Typography sx={{ fontFamily: 'inherit' }}>Price</Typography>
                </Box>
                <Box sx={{ display: 'flex', justifyContent: 'space-between' }}>
                  <Typography sx={{ fontFamily: 'inherit' }}>Coffee</Typography>
                  <Typography sx={{ fontFamily: 'inherit' }}>$10.00</Typography>
                </Box>
                <Box sx={{ display: 'flex', justifyContent: 'space-between' }}>
                  <Typography sx={{ fontFamily: 'inherit' }}>Tea</Typography>
                  <Typography sx={{ fontFamily: 'inherit' }}>$8.00</Typography>
                </Box>
              </Box>

              <Divider sx={{ borderStyle: 'dashed', my: 1 }} />
              <Box sx={{ display: 'flex', justifyContent: 'space-between' }}>
                <Typography sx={{ fontFamily: 'inherit', fontWeight: 700 }}>TOTAL</Typography>
                <Typography sx={{ fontFamily: 'inherit', fontWeight: 700 }}>$18.00</Typography>
              </Box>

              <Box sx={{ textAlign: 'center', mt: 2 }}>
                <Typography sx={{ fontFamily: 'inherit', fontWeight: 700 }}>{receiptPreview.footerText}</Typography>
              </Box>
            </Box>
          </Paper>
        </Grid>
      </Grid>
    </Box>
  );
};

export default ReceiptCustomization;
