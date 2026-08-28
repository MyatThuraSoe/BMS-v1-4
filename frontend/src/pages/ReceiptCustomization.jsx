import { useEffect, useMemo, useState, useRef, useCallback } from 'react';
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  Divider,
  FormControlLabel,
  Grid,
  MenuItem,
  Paper,
  Slider,
  Stack,
  Switch,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Tooltip,
  Typography,
} from '@mui/material';
import {
  Save as SaveIcon,
  RestartAlt as RestartAltIcon,
  FormatAlignLeft,
  FormatAlignCenter,
  FormatAlignRight,
  TextFields as FontSizeIcon,
  Image as LogoIcon,
  Tune as TuneIcon,
  ReceiptLong as ReceiptIcon,
} from '@mui/icons-material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../context/AuthContext';
import { receiptCustomizationService, shopInfoService, counterPrintService } from '../api/services';
import { notifySuccess, notifyError, notifyInfo } from '../utils/notify';
import ReceiptDocument from '../components/ReceiptDocument';
import { getReceiptPreviewWidth } from '../utils/helpers';

const PAPER_SIZES = ['58', '80', '100'];
const TIME_FORMATS = [
  { value: '12', label: '12h' },
  { value: '24', label: '24h' },
];
const FONT_SIZES = [
  { value: 'small',  label: 'S' },
  { value: 'normal', label: 'M' },
  { value: 'large',  label: 'L' },
];
const DIVIDER_STYLES = [
  { value: 'dashed', label: 'rc_divider_dashed' },
  { value: 'solid',  label: 'rc_divider_solid'  },
  { value: 'dotted', label: 'rc_divider_dotted' },
  { value: 'none',   label: 'rc_divider_none'   },
];

const defaultCustomization = {
  headerText:   'Thank you for shopping with us',
  mainMessage:  'Please keep this receipt for your records.',
  footerText:   'Thank you for your business!',
  paperSize:    '58',
  timeFormat:   '12',
  // advanced
  logoSize:     80,
  showLogo:     true,
  showShopName: true,
  showAddress:  true,
  showPhone:    true,
  headerAlign:  'center',
  fontSize:     'normal',
  dividerStyle: 'dashed',
  boldShopName: true,
  showQRCode:   false,
  showCreditInfo: true,
  showTax:      true,
  showDiscount: true,
};

// Values applied by the "Reset to Default" button
const RESET_DEFAULTS = {
  headerText:   '',
  mainMessage:  '',
  footerText:   'Thank you!',
  paperSize:    '80',
  timeFormat:   '12',
  logoSize:     60,
  showLogo:     true,
  showShopName: true,
  showAddress:  true,
  showPhone:    true,
  headerAlign:  'center',
  fontSize:     'small',
  dividerStyle: 'solid',
  boldShopName: true,
  showQRCode:   false,
  showCreditInfo: true,
  showTax:      true,
  showDiscount: true,
};

// ─── Section wrapper ─────────────────────────────────────────────────────────
const Section = ({ icon, title, children }) => (
  <Box>
    <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1.5 }}>
      <Box sx={{ color: 'primary.main', display: 'flex' }}>{icon}</Box>
      <Typography variant="subtitle1" fontWeight={600}>{title}</Typography>
    </Stack>
    {children}
  </Box>
);

// ─── Main component ───────────────────────────────────────────────────────────
const ReceiptCustomization = () => {
  const { t } = useTranslation('settings');
  const { isAdmin } = useAuth();
  const queryClient = useQueryClient();

  const { data: customizationData, isLoading: loadingCustomization } = useQuery({
    queryKey: ['receipt-customization'],
    queryFn:  () => receiptCustomizationService.get(),
    enabled:  isAdmin(),
  });

  const { data: shopInfoData, isLoading: loadingShopInfo } = useQuery({
    queryKey: ['shopInfo-preview'],
    queryFn:  () => shopInfoService.get(),
    enabled:  isAdmin(),
  });

  const [form, setForm] = useState(defaultCustomization);

  // --- Counter printer (server-side printing) ---
  const [counterPrinter, setCounterPrinter] = useState('');
  const [printerList, setPrinterList] = useState([]);
  const [defaultPrinter, setDefaultPrinter] = useState('');

  const printersQuery = useQuery({
    queryKey: ['counter-printers'],
    queryFn: async () => {
      const cfgRes = await counterPrintService.getConfig();
      const cfg = cfgRes?.data || {};
      setCounterPrinter(cfg.printerName || '');
      setDefaultPrinter(cfg.default || '');
      const listRes = await counterPrintService.listPrinters();
      setPrinterList(listRes?.data?.printers || []);
      return true;
    },
    enabled: isAdmin(),
  });
  const printersLoading = printersQuery.isLoading;

  const savePrinter = useMutation({
    mutationFn: counterPrintService.saveConfig,
    onSuccess: () => notifySuccess(t('counter_printer_saved')),
    onError: (err) => notifyError(err.friendlyMessage || err.response?.data?.message || t('counter_printer_failed')),
  });

  const testPrint = useMutation({
    mutationFn: counterPrintService.testPrint,
    onSuccess: () => notifySuccess(t('counter_printer_test_ok')),
    onError: (err) => notifyError(err.friendlyMessage || err.response?.data?.message || t('counter_printer_failed')),
  });

  // populate from server
  useEffect(() => {
    if (customizationData?.data) {
      const d = customizationData.data;
      setForm({
        headerText:   d.headerText   ?? defaultCustomization.headerText,
        mainMessage:  d.mainMessage  ?? defaultCustomization.mainMessage,
        footerText:   d.footerText   ?? defaultCustomization.footerText,
        paperSize:    d.paperSize    || defaultCustomization.paperSize,
        timeFormat:   d.timeFormat   || defaultCustomization.timeFormat,
        logoSize:     d.logoSize     ?? defaultCustomization.logoSize,
        showLogo:     d.showLogo     ?? defaultCustomization.showLogo,
        showShopName: d.showShopName ?? defaultCustomization.showShopName,
        showAddress:  d.showAddress  ?? defaultCustomization.showAddress,
        showPhone:    d.showPhone    ?? defaultCustomization.showPhone,
        headerAlign:  d.headerAlign  || defaultCustomization.headerAlign,
        fontSize:     d.fontSize     || defaultCustomization.fontSize,
        dividerStyle: d.dividerStyle || defaultCustomization.dividerStyle,
        boldShopName: d.boldShopName ?? defaultCustomization.boldShopName,
        showQRCode:   d.showQRCode   ?? defaultCustomization.showQRCode,
        showCreditInfo: d.showCreditInfo ?? defaultCustomization.showCreditInfo,
        showTax:      d.showTax      ?? defaultCustomization.showTax,
        showDiscount: d.showDiscount ?? defaultCustomization.showDiscount,
      });
    }
  }, [customizationData]);

  const upsertMutation = useMutation({
    mutationFn: (payload) => receiptCustomizationService.upsert(payload),
    onSuccess:  async () => {
      await queryClient.invalidateQueries({ queryKey: ['receipt-customization'] });
      await queryClient.invalidateQueries({ queryKey: ['receipt-customization-preview'] });
      await queryClient.invalidateQueries({ queryKey: ['receipt-customization-pos'] });
      notifyInfo(t('restart_app_to_apply_changes'));
    },
  });

  const set = useCallback((key, value) => setForm((prev) => ({ ...prev, [key]: value })), []);

  const shopInfo = useMemo(() => shopInfoData?.data || {}, [shopInfoData]);

  // Mock QR for the preview so admins can see exactly where it will print
  // when "Show QR Code" is enabled (real receipts encode the invoice number).
  const [previewQr, setPreviewQr] = useState(null);
  useEffect(() => {
    let cancelled = false;
    if (!form.showQRCode) { setPreviewQr(null); return undefined; }
    import('qrcode').then((mod) =>
      mod.default.toDataURL(
        shopInfo?.shopName || 'LumiPOS',
        { width: 320, margin: 2, color: { dark: '#111111', light: '#ffffff' } }
      )
    ).then((url) => { if (!cancelled) setPreviewQr(url); })
     .catch(() => { if (!cancelled) setPreviewQr(null); });
    return () => { cancelled = true; };
  }, [form.showQRCode, shopInfo]);

  // Compute preview container width based on paper size
  const paperWidthMm  = Math.max(40, parseInt(String(form.paperSize || '58').replace(/\D/g, ''), 10) || 58);
  const previewPxFull = getReceiptPreviewWidth(form.paperSize);   // full pixel width at 1:1 scale
  // Clamp to max 360px on screen; use CSS scale transform for accurate sizing
  const MAX_PREVIEW   = 360;
  const previewScale  = previewPxFull > MAX_PREVIEW ? MAX_PREVIEW / previewPxFull : 1;
  const previewContainerW = Math.min(previewPxFull, MAX_PREVIEW);

  if (!isAdmin()) {
    return <Typography color="text.secondary">{t('not_authorized')}</Typography>;
  }

  if (loadingCustomization || loadingShopInfo) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', mt: 4 }}>
        <CircularProgress />
      </Box>
    );
  }

  return (
    <Box sx={{ maxWidth: 1280, mx: 'auto' }}>
      <Stack direction="row" alignItems="center" spacing={1.5} sx={{ mb: 0.5 }}>
        <ReceiptIcon sx={{ color: 'primary.main', fontSize: 28 }} />
        <Typography variant="h4" fontWeight={700}>{t('rc_title')}</Typography>
      </Stack>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        {t('rc_subtitle')}
      </Typography>

      <Grid container spacing={3}>
        {/* ===== LEFT: Controls ===== */}
        <Grid item xs={12} md={6}>
          <Paper sx={{ p: 3 }}>
            <Stack spacing={3}>

              {/* --- Text Content --- */}
              <Section icon={<FontSizeIcon fontSize="small" />} title={t('rc_text_section')}>
                <Stack spacing={2}>
                  <TextField
                    label={t('rc_header_label')}
                    value={form.headerText}
                    onChange={(e) => set('headerText', e.target.value)}
                    fullWidth
                    helperText={t('rc_header_helper')}
                  />
                  <TextField
                    label={t('rc_main_label')}
                    value={form.mainMessage}
                    onChange={(e) => set('mainMessage', e.target.value)}
                    fullWidth
                    multiline
                    minRows={2}
                    helperText={t('rc_main_helper')}
                  />
                  <TextField
                    label={t('rc_footer_label')}
                    value={form.footerText}
                    onChange={(e) => set('footerText', e.target.value)}
                    fullWidth
                    helperText={t('rc_footer_helper')}
                  />
                </Stack>
              </Section>

              <Divider />

              {/* --- Logo & Header --- */}
              <Section icon={<LogoIcon fontSize="small" />} title={t('rc_logo_section')}>
                <Stack spacing={2}>
                  <FormControlLabel
                    control={
                      <Switch
                        checked={form.showLogo}
                        onChange={(e) => set('showLogo', e.target.checked)}
                        color="primary"
                      />
                    }
                    label={t('rc_show_logo')}
                  />

                  {form.showLogo && (
                    <Box>
                      <Typography variant="body2" color="text.secondary" gutterBottom>
                        {t('rc_logo_size')} <strong>{form.logoSize}px</strong>
                      </Typography>
                      <Slider
                        value={form.logoSize}
                        min={20}
                        max={160}
                        step={4}
                        onChange={(_, val) => set('logoSize', val)}
                        marks={[
                          { value: 20, label: '20' },
                          { value: 80, label: '80' },
                          { value: 160, label: '160' },
                        ]}
                        valueLabelDisplay="auto"
                      />
                    </Box>
                  )}

                  <FormControlLabel
                    control={<Switch checked={form.showShopName} onChange={(e) => set('showShopName', e.target.checked)} color="primary" />}
                    label={t('rc_show_shop_name')}
                  />

                  <FormControlLabel
                    control={<Switch checked={form.boldShopName} onChange={(e) => set('boldShopName', e.target.checked)} color="primary" />}
                    label={t('rc_bold_shop_name')}
                  />

                  <FormControlLabel
                    control={<Switch checked={form.showQRCode} onChange={(e) => set('showQRCode', e.target.checked)} color="primary" />}
                    label={t('rc_show_qr')}
                  />

                  <FormControlLabel
                    control={<Switch checked={form.showCreditInfo} onChange={(e) => set('showCreditInfo', e.target.checked)} color="primary" />}
                    label={t('rc_show_credit_info')}
                  />

                  <FormControlLabel
                    control={<Switch checked={form.showTax} onChange={(e) => set('showTax', e.target.checked)} color="primary" />}
                    label={t('rc_show_tax')}
                  />

                  <FormControlLabel
                    control={<Switch checked={form.showDiscount} onChange={(e) => set('showDiscount', e.target.checked)} color="primary" />}
                    label={t('rc_show_discount')}
                  />

                  <Box>
                    <Typography variant="body2" color="text.secondary" gutterBottom>{t('rc_header_align')}</Typography>
                    <ToggleButtonGroup
                      exclusive
                      value={form.headerAlign}
                      onChange={(_, val) => val && set('headerAlign', val)}
                      size="small"
                    >
                      <ToggleButton value="left"   aria-label="align left">  <FormatAlignLeft />  </ToggleButton>
                      <ToggleButton value="center" aria-label="align center"><FormatAlignCenter /></ToggleButton>
                      <ToggleButton value="right"  aria-label="align right"> <FormatAlignRight /> </ToggleButton>
                    </ToggleButtonGroup>
                  </Box>

                  <FormControlLabel
                    control={<Switch checked={form.showAddress} onChange={(e) => set('showAddress', e.target.checked)} color="primary" />}
                    label={t('rc_show_address')}
                  />
                  <FormControlLabel
                    control={<Switch checked={form.showPhone} onChange={(e) => set('showPhone', e.target.checked)} color="primary" />}
                    label={t('rc_show_phone')}
                  />
                </Stack>
              </Section>

              <Divider />

              {/* --- Layout & Format --- */}
              <Section icon={<TuneIcon fontSize="small" />} title={t('rc_layout_section')}>
                <Stack spacing={2}>
                  <Box>
                    <Typography variant="body2" color="text.secondary" gutterBottom>{t('rc_paper_size')}</Typography>
                    <Stack direction="row" spacing={1}>
                      {PAPER_SIZES.map((size) => (
                        <Button
                          key={size}
                          variant={form.paperSize === size ? 'contained' : 'outlined'}
                          size="small"
                          onClick={() => set('paperSize', size)}
                        >
                          {size} mm
                        </Button>
                      ))}
                    </Stack>
                  </Box>

                  <Box>
                    <Typography variant="body2" color="text.secondary" gutterBottom>{t('rc_font_size')}</Typography>
                    <ToggleButtonGroup
                      exclusive
                      value={form.fontSize}
                      onChange={(_, val) => val && set('fontSize', val)}
                      size="small"
                    >
                      {FONT_SIZES.map((f) => (
                        <ToggleButton key={f.value} value={f.value} sx={{ px: 2 }}>
                          {f.label}
                        </ToggleButton>
                      ))}
                    </ToggleButtonGroup>
                  </Box>

                  <Box>
                    <Typography variant="body2" color="text.secondary" gutterBottom>{t('rc_divider_style')}</Typography>
                    <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
                      {DIVIDER_STYLES.map((s) => (
                        <Button
                          key={s.value}
                          variant={form.dividerStyle === s.value ? 'contained' : 'outlined'}
                          size="small"
                          onClick={() => set('dividerStyle', s.value)}
                        >
                          {t(s.label)}
                        </Button>
                      ))}
                    </Stack>
                  </Box>

                  <Box>
                    <Typography variant="body2" color="text.secondary" gutterBottom>{t('rc_time_format')}</Typography>
                    <ToggleButtonGroup
                      exclusive
                      value={form.timeFormat}
                      onChange={(_, val) => val && set('timeFormat', val)}
                      size="small"
                    >
                      {TIME_FORMATS.map((fmt) => (
                        <ToggleButton key={fmt.value} value={fmt.value} sx={{ px: 2 }}>
                          {fmt.label}
                        </ToggleButton>
                      ))}
                    </ToggleButtonGroup>
                  </Box>
                </Stack>
              </Section>

              <Divider />

              {/* --- Save / Reset --- */}
              <Stack spacing={1}>
                <Stack direction="row" spacing={1}>
                  <Button
                    variant="contained"
                    size="large"
                    startIcon={upsertMutation.isPending ? <CircularProgress size={18} color="inherit" /> : <SaveIcon />}
                    onClick={() => upsertMutation.mutate(form)}
                    disabled={upsertMutation.isPending}
                    sx={{ px: 3 }}
                  >
                    {upsertMutation.isPending ? t('rc_saving') : t('rc_save')}
                  </Button>
                  <Button
                    variant="outlined"
                    size="large"
                    startIcon={<RestartAltIcon />}
                    onClick={() => {
                      setForm({ ...RESET_DEFAULTS });
                      upsertMutation.mutate({ ...RESET_DEFAULTS });
                    }}
                    disabled={upsertMutation.isPending}
                  >
                    {t('rc_reset')}
                  </Button>
                </Stack>
                {upsertMutation.isSuccess && <Alert severity="success">{t('rc_saved')}</Alert>}
                {upsertMutation.isError   && <Alert severity="error">{t('rc_save_failed')}</Alert>}
              </Stack>

            </Stack>
          </Paper>
        </Grid>

        {/* ===== RIGHT: Live WYSIWYG Preview ===== */}
        <Grid item xs={12} md={6}>
          <Paper sx={{ p: 3 }}>
            <Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ mb: 2 }}>
              <Typography variant="h6" fontWeight={700}>{t('rc_live_preview')}</Typography>
              <Stack direction="row" spacing={1} alignItems="center">
                <Typography variant="caption" color="text.secondary">
                  {t('rc_mm_paper', { size: form.paperSize })}
                </Typography>
                {previewScale < 1 && (
                  <Tooltip title={t('rc_scaled_tooltip')}>
                    <Typography variant="caption" color="warning.main" sx={{ cursor: 'help' }}>
                      {t('rc_scale_percent', { percent: Math.round(previewScale * 100) })}
                    </Typography>
                  </Tooltip>
                )}
              </Stack>
            </Stack>

            {/* Paper shadow container */}
            <Box
              sx={{
                display: 'flex',
                justifyContent: 'center',
                overflowX: 'auto',
                pb: 1,
              }}
            >
              {/* Outer wrapper: controls the visible container width */}
              <Box
                sx={{
                  width:        previewContainerW,
                  flexShrink:   0,
                  position:     'relative',
                }}
              >
                {/* Inner content: rendered at full receipt width, then scaled down */}
                <Box
                  sx={{
                    transformOrigin: 'top left',
                    transform:       `scale(${previewScale})`,
                    width:           previewPxFull,
                    height:          `${1 / previewScale * 100}%`,
                    background:      '#fff',
                    border:          '1px solid #ccc',
                    borderRadius:    '2px',
                    boxShadow:       '0 4px 24px rgba(0,0,0,0.13), 0 1px 4px rgba(0,0,0,0.08)',
                    px:              '12px',
                    py:              '16px',
                    // Simulate receipt paper top/bottom jagged edge via gradient
                    '&::before': {
                      content:    '""',
                      display:    'block',
                      height:     '8px',
                      mx:         '-12px',
                      mb:         '10px',
                      background: 'repeating-linear-gradient(90deg, #fff 0, #fff 6px, #e0e0e0 6px, #e0e0e0 7px)',
                    },
                    '&::after': {
                      content:    '""',
                      display:    'block',
                      height:     '8px',
                      mx:         '-12px',
                      mt:         '10px',
                      background: 'repeating-linear-gradient(90deg, #fff 0, #fff 6px, #e0e0e0 6px, #e0e0e0 7px)',
                    },
                  }}
                >
                  <ReceiptDocument
                    receipt={{}}
                    shopInfo={shopInfo}
                    customization={form}
                    isMockPreview={true}
                    qrDataUrl={previewQr}
                  />
                </Box>
              </Box>
            </Box>

            {/* Scale info footnote */}
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', textAlign: 'center', mt: 1 }}>
              {previewScale >= 1
                ? t('rc_preview_1to1')
                : t('rc_preview_scaled', { width: paperWidthMm })}
            </Typography>
          </Paper>

          {/* ===== Counter Printer (server-side printing) ===== */}
          <Paper sx={{ p: 3, mt: 3 }}>
            <Typography variant="h6" fontWeight={700} gutterBottom>
              🖨 {t('counter_printer_title')}
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
              {t('counter_printer_desc')}
            </Typography>
            {printersLoading ? (
              <CircularProgress size={24} />
            ) : (
              <>
                <TextField
                  select
                  fullWidth
                  label={t('counter_printer_label')}
                  value={counterPrinter}
                  onChange={(e) => setCounterPrinter(e.target.value)}
                  helperText={t('counter_printer_helper')}
                  sx={{ mb: 2 }}
                >
                  {(printerList || []).map((name) => (
                    <MenuItem key={name} value={name}>
                      {name}{name === defaultPrinter ? ' ★' : ''}
                    </MenuItem>
                  ))}
                </TextField>
                <Stack direction="row" spacing={1}>
                  <Button
                    variant="contained"
                    startIcon={<SaveIcon />}
                    onClick={() => savePrinter.mutate(counterPrinter)}
                    disabled={savePrinter.isPending}
                  >
                    {t('rc_save') || t('save')}
                  </Button>
                  <Button
                    variant="outlined"
                    onClick={() => testPrint.mutate(counterPrinter)}
                    disabled={testPrint.isPending}
                  >
                    {t('counter_printer_test')}
                  </Button>
                </Stack>
              </>
            )}
          </Paper>
        </Grid>
      </Grid>
    </Box>
  );
};

export default ReceiptCustomization;
