import { useState, useRef, useEffect, useCallback } from 'react';
import { useParams } from 'react-router-dom';
import {
  Box, Typography, Button, Dialog, DialogTitle, DialogContent,
  DialogActions, CircularProgress, TextField, Divider,
  FormControl, InputLabel, Select, MenuItem,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  receiptService, saleService, shopInfoService,
  receiptCustomizationService,
} from '../api/services';
import { formatCurrency } from '../utils/helpers';
import {
  AssignmentReturn as RefundIcon,
  Print as PrintIcon,
  Download as DownloadIcon,
  LocalPrintshop as PrinterIcon,
  FlashOn as DirectPrintIcon,
} from '@mui/icons-material';
import { notifySuccess, notifyError } from '../utils/notify';
import { useAuth } from '../context/AuthContext';
import { useTranslation } from 'react-i18next';
import {
  connectQZ, printReceiptViaQZ, isQZSupported,
  getAvailablePrinters, getReceiptPreviewWidth,
} from '../utils/bluetoothPrinter';
import directPrint from '../services/directPrintService';
import ReceiptDocument, { generatePrintHtml, generateQRDataUrl } from '../components/ReceiptDocument';
import ShopLogo from '../components/ShopLogo';

// ─── Helpers ─────────────────────────────────────────────────────────────────

/** Fetch the shop logo as a base64 data URL for embedding in print HTML */
async function fetchLogoDataUrl(shopInfoService) {
  try {
    const blob = await shopInfoService.getLogo();
    return await new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload  = () => resolve(reader.result);
      reader.onerror = () => resolve(null);
      reader.readAsDataURL(blob);
    });
  } catch {
    return null;
  }
}

// ─── Component ───────────────────────────────────────────────────────────────

const ReceiptPreview = () => {
  const { t } = useTranslation('sales');
  const { invoiceNumber } = useParams();
  const [refundDialogOpen, setRefundDialogOpen] = useState(false);
  const [refundReason,     setRefundReason]     = useState('');
  const [refundQuantities, setRefundQuantities] = useState({});
  const [printers,         setPrinters]         = useState([]);
  const [selectedPrinter,  setSelectedPrinter]  = useState('');
  const [isPrinting,       setIsPrinting]       = useState(false);
  const queryClient = useQueryClient();
  const { isManager } = useAuth();

  const receiptRef = useRef();

  // ── Queries ────────────────────────────────────────────────────────────────

  const { data, isLoading } = useQuery({
    queryKey: ['receipt', invoiceNumber],
    queryFn:  () => receiptService.getByInvoiceNumber(invoiceNumber),
  });

  const { data: shopInfoData } = useQuery({
    queryKey: ['shopInfo-preview'],
    queryFn:  () => shopInfoService.get(),
  });

  const { data: customizationData } = useQuery({
    queryKey: ['receipt-customization-preview'],
    queryFn:  () => receiptCustomizationService.get(),
  });

  // ── Printer detection ──────────────────────────────────────────────────────

  useEffect(() => {
    if (isQZSupported()) {
      connectQZ()
        .then(() => getAvailablePrinters())
        .then(setPrinters)
        .catch(() => {});
    } else if (directPrint.isAvailable()) {
      directPrint.getPrinters().then((list) => {
        setPrinters(list.map((p) => p.name));
        if (list.length > 0) {
          const def = list.find((p) => p.isDefault) || list[0];
          setSelectedPrinter(def.name);
        }
      });
    }
  }, []);

  // ── Refund mutation ────────────────────────────────────────────────────────

  const refundMutation = useMutation({
    mutationFn: ({ saleId, payload }) => saleService.refundSale(saleId, payload),
    onSuccess: () => {
      notifySuccess(t('refunded_success'));
      setRefundDialogOpen(false);
      setRefundReason('');
      setRefundQuantities({});
      queryClient.invalidateQueries({ queryKey: ['receipt', invoiceNumber] });
      queryClient.invalidateQueries({ queryKey: ['sales'] });
      queryClient.invalidateQueries({ queryKey: ['products'] });
      queryClient.invalidateQueries({ queryKey: ['low-stock'] });
      queryClient.invalidateQueries({ queryKey: ['inventoryReport'] });
    },
    onError: (err) => notifyError(err.friendlyMessage || t('refund_failed')),
  });

  // ── Guards ─────────────────────────────────────────────────────────────────

  if (isLoading) return <CircularProgress />;
  if (!data?.data) return <Typography>{t('receipt_not_found')}</Typography>;

  const receipt        = data.data;
  const shopInfo       = shopInfoData?.data || {};
  const customization  = customizationData?.data || {};
  const paperSize      = customization.paperSize || '58';
  const paperWidthMm   = Math.max(20, parseInt(String(paperSize).replace(/\D/g, ''), 10) || 58);
  const previewWidth   = getReceiptPreviewWidth(paperSize);
  const receiptTimeFormat = customization.timeFormat || '12';

  const refundableItems = receipt.items?.filter(
    (item) => (item.quantity || 0) - (item.quantityRefunded || 0) > 0
  ) || [];
  const refundTotal = refundableItems.reduce((sum, item) => {
    const qty = Number(refundQuantities[item.saleItemId] || 0);
    return sum + qty * Number(item.unitPrice || 0);
  }, 0);

  // ── Print handlers ─────────────────────────────────────────────────────────

  const handlePrint = () => window.print();

  const handleDownload = async (format) => {
    try {
      const blob = await receiptService.downloadReceipt(invoiceNumber, format);
      const url  = window.URL.createObjectURL(new Blob([blob]));
      const link = document.createElement('a');
      link.href     = url;
      link.download = `receipt-${invoiceNumber}.${format}`;
      link.click();
      window.URL.revokeObjectURL(url);
      notifySuccess(t('downloaded_as', { format: format.toUpperCase() }));
    } catch (err) {
      notifyError(err.friendlyMessage || 'Failed to download receipt');
    }
  };

  const handleQZPrint = async () => {
    setIsPrinting(true);
    try {
      await connectQZ();
      await printReceiptViaQZ(receipt, shopInfo, selectedPrinter || null, receiptTimeFormat, customization.paperSize);
    } catch {
      // handled inside printReceiptViaQZ
    } finally {
      setIsPrinting(false);
    }
  };

  /**
   * Direct print: generate the same HTML as the preview and send it
   * to the silent Electron printer, so the paper matches the screen.
   */
  const handleDirectPrint = async () => {
    setIsPrinting(true);
    try {
      if (directPrint.isAvailable()) {
        // Fetch logo as base64 so it embeds in the offline HTML
        const logoDataUrl = shopInfo.hasLogo ? await fetchLogoDataUrl(shopInfoService) : null;
        let qrDataUrl = null;
        if (customization?.showQRCode) {
          qrDataUrl = await generateQRDataUrl(receipt.invoiceNumber);
        }
        const html = generatePrintHtml(receipt, shopInfo, customization, logoDataUrl, qrDataUrl);
        const result = await directPrint.print(html, selectedPrinter || null, paperWidthMm);
        if (result.success) {
          notifySuccess('Receipt sent to printer');
        } else {
          notifyError(result.error || 'Print failed');
        }
      } else if (isQZSupported()) {
        await connectQZ();
        await printReceiptViaQZ(receipt, shopInfo, selectedPrinter || null, receiptTimeFormat, customization.paperSize);
      } else {
        window.print();
      }
    } catch (err) {
      notifyError(err.message || 'Print failed');
    } finally {
      setIsPrinting(false);
    }
  };

  // ── Refund helpers ─────────────────────────────────────────────────────────

  const setRefundQuantity = (item, value) => {
    const max = (item.quantity || 0) - (item.quantityRefunded || 0);
    const qty = Math.max(0, Math.min(max, Number(value) || 0));
    setRefundQuantities((cur) => ({ ...cur, [item.saleItemId]: qty }));
  };

  const handleRefundSubmit = () => {
    const items = refundableItems
      .map((item) => ({ saleItemId: item.saleItemId, quantity: Number(refundQuantities[item.saleItemId] || 0) }))
      .filter((item) => item.quantity > 0);
    if (!receipt.saleId || items.length === 0 || !refundReason.trim()) return;
    refundMutation.mutate({ saleId: receipt.saleId, payload: { reason: refundReason.trim(), items } });
  };

  // ── Render ─────────────────────────────────────────────────────────────────

  return (
    <Box sx={{ p: 3, maxWidth: previewWidth, mx: 'auto' }}>
      {/* ===== The actual receipt — shared ReceiptDocument ===== */}
      <Box
        ref={receiptRef}
        sx={{
          background: '#fff',
          color: '#111',
          p: '12px',
          border: '1px solid #ddd',
          borderRadius: '2px',
          boxShadow: '0 2px 12px rgba(0,0,0,0.08)',
        }}
      >
        <ReceiptDocument
          receipt={receipt}
          shopInfo={shopInfo}
          customization={customization}
          isMockPreview={false}
        />
      </Box>

      {/* ===== Printer Selection ===== */}
      {(isQZSupported() || directPrint.isAvailable()) && printers.length > 0 && (
        <FormControl fullWidth size="small" sx={{ mt: 2 }}>
          <InputLabel>{t('common:printer')}</InputLabel>
          <Select
            value={selectedPrinter}
            label={t('common:printer')}
            onChange={(e) => setSelectedPrinter(e.target.value)}
          >
            {printers.map((p, idx) => (
              <MenuItem key={idx} value={p}>{p}</MenuItem>
            ))}
          </Select>
        </FormControl>
      )}

      {/* ===== Direct Print (always visible) ===== */}
      <Box sx={{ mt: 2 }}>
        <Button
          fullWidth
          variant="contained"
          color="primary"
          size="large"
          startIcon={isPrinting
            ? <CircularProgress size={20} sx={{ color: 'white' }} />
            : <DirectPrintIcon />}
          onClick={handleDirectPrint}
          disabled={isPrinting}
          sx={{ py: 1.2, fontSize: '1rem' }}
        >
          {isPrinting
            ? t('printing')
            : directPrint.isAvailable()
              ? t('direct_print_silent')
              : t('direct_print')}
        </Button>
      </Box>

      {/* ===== Browser Print & Downloads ===== */}
      <Box sx={{ mt: 1, display: 'flex', gap: 1, flexWrap: 'wrap' }}>
        {!directPrint.isAvailable() && (
          <Button
            fullWidth
            sx={{ flex: { xs: '1 1 100%', sm: '1 1 0' } }}
            variant="outlined"
            startIcon={<PrintIcon />}
            onClick={handlePrint}
          >
            {t('print_browser')}
          </Button>
        )}
        <Button
          fullWidth
          sx={{ flex: { xs: '1 1 100%', sm: '1 1 0' } }}
          variant="outlined"
          startIcon={<DownloadIcon />}
          onClick={() => handleDownload('pdf')}
        >
          PDF
        </Button>
        <Button
          fullWidth
          sx={{ flex: { xs: '1 1 100%', sm: '1 1 0' } }}
          variant="outlined"
          startIcon={<DownloadIcon />}
          onClick={() => handleDownload('png')}
        >
          PNG
        </Button>
      </Box>

      {/* ===== QZ Tray Print ===== */}
      {isQZSupported() && (
        <Box sx={{ mt: 1 }}>
          <Button
            fullWidth
            variant="outlined"
            startIcon={<PrinterIcon />}
            onClick={handleQZPrint}
            disabled={isPrinting}
          >
            {isPrinting ? t('printing') : t('qz_print')}
          </Button>
        </Box>
      )}

      {/* ===== Refund ===== */}
      {isManager() && refundableItems.length > 0 && (
        <Box sx={{ mt: 1 }}>
          <Button
            fullWidth
            variant="outlined"
            color="warning"
            startIcon={<RefundIcon />}
            onClick={() => setRefundDialogOpen(true)}
          >
            {t('refund')}
          </Button>
        </Box>
      )}

      <Box sx={{ mt: 1 }}>
        <Button fullWidth variant="text" onClick={() => window.close()}>{t('close')}</Button>
      </Box>

      {/* ===== Refund Dialog ===== */}
      <Dialog open={refundDialogOpen} onClose={() => setRefundDialogOpen(false)} fullWidth maxWidth="sm">
        <DialogTitle>{t('refund_items')}</DialogTitle>
        <DialogContent>
          <Typography variant="body2" sx={{ mb: 2 }}>{t('invoice_no')} {receipt.invoiceNumber}</Typography>
          {refundableItems.map((item) => {
            const max = (item.quantity || 0) - (item.quantityRefunded || 0);
            return (
              <Box
                key={item.saleItemId}
                sx={{ display: 'grid', gridTemplateColumns: '1fr 96px', gap: 2, alignItems: 'center', mb: 2 }}
              >
                <Box>
                  <Typography variant="body2" fontWeight={600}>{item.productName}</Typography>
                  <Typography variant="caption" color="text.secondary">
                    {t('available_at', { qty: max, price: formatCurrency(item.unitPrice) })}
                  </Typography>
                </Box>
                <TextField
                  size="small"
                  type="number"
                  label={t('qty')}
                  inputProps={{ min: 0, max }}
                  value={refundQuantities[item.saleItemId] || ''}
                  onChange={(e) => setRefundQuantity(item, e.target.value)}
                />
              </Box>
            );
          })}
          <Divider sx={{ my: 2 }} />
          <TextField
            fullWidth
            required
            multiline
            rows={3}
            label={t('reason')}
            value={refundReason}
            onChange={(e) => setRefundReason(e.target.value)}
          />
          <Box sx={{ display: 'flex', justifyContent: 'space-between', mt: 2 }}>
            <Typography fontWeight={600}>{t('refund_total')}</Typography>
            <Typography fontWeight={600}>{formatCurrency(refundTotal)}</Typography>
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setRefundDialogOpen(false)}>{t('cancel')}</Button>
          <Button
            onClick={handleRefundSubmit}
            color="warning"
            variant="contained"
            disabled={refundMutation.isPending || refundTotal <= 0 || !refundReason.trim()}
          >
            {t('refund')}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default ReceiptPreview;