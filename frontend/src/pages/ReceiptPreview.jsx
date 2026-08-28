import { useState, useRef, useEffect } from 'react';
import { useParams } from 'react-router-dom';
import {
  Box, Typography, Button, CircularProgress,
  FormControl, InputLabel, Select, MenuItem,
} from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import {
  receiptService, shopInfoService,
  receiptCustomizationService,
} from '../api/services';
import {
  AssignmentReturn as RefundIcon,
  Print as PrintIcon,
  Download as DownloadIcon,
  FlashOn as DirectPrintIcon,
} from '@mui/icons-material';
import { notifySuccess, notifyError } from '../utils/notify';
import { useAuth } from '../context/AuthContext';
import { useTranslation } from 'react-i18next';
import { getReceiptPreviewWidth } from '../utils/helpers';
import directPrint from '../services/directPrintService';
import ReceiptDocument, { generatePrintHtml, generateQRDataUrl } from '../components/ReceiptDocument';
import ShopLogo from '../components/ShopLogo';
import SaleReturnDialog from '../components/SaleReturnDialog';

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
  const [returnDialogOpen, setReturnDialogOpen] = useState(false);
  const [printers,         setPrinters]         = useState([]);
  const [selectedPrinter,  setSelectedPrinter]  = useState('');
  const [isPrinting,       setIsPrinting]       = useState(false);
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
    if (directPrint.isAvailable()) {
      directPrint.getPrinters().then((list) => {
        setPrinters(list.map((p) => p.name));
        if (list.length > 0) {
          const def = list.find((p) => p.isDefault) || list[0];
          setSelectedPrinter(def.name);
        }
      });
    }
  }, []);

  // ── Guards ─────────────────────────────────────────────────────────────────

  if (isLoading) return <CircularProgress />;
  if (!data?.data) return <Typography>{t('receipt_not_found')}</Typography>;

  const receipt        = data.data;
  const shopInfo       = shopInfoData?.data || {};
  const customization  = customizationData?.data || {};
  const paperSize      = customization.paperSize || '58';
  const paperWidthMm   = Math.max(20, parseInt(String(paperSize).replace(/\D/g, ''), 10) || 58);
  const previewWidth   = getReceiptPreviewWidth(paperSize);

  const refundableItems = receipt.items?.filter(
    (item) => (item.quantity || 0) - (item.quantityRefunded || 0) > 0
  ) || [];

  // ── Print handlers ─────────────────────────────────────────────────────────

  const handlePrint = async () => {
    try {
      const logoDataUrl = shopInfo.hasLogo ? await fetchLogoDataUrl(shopInfoService) : null;
      const qrDataUrl = customization?.showQRCode
        ? await generateQRDataUrl(receipt.invoiceNumber)
        : null;
      const html = generatePrintHtml(receipt, shopInfo, customization, logoDataUrl, qrDataUrl);
      const printWindow = window.open('', '_blank');
      if (!printWindow) {
        throw new Error('Unable to open print window');
      }
      printWindow.document.write(html);
      printWindow.document.close();
      printWindow.onload = () => printWindow.print();
    } catch (err) {
      notifyError(err.message || 'Print failed');
    }
  };

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
      } else {
        window.print();
      }
    } catch (err) {
      notifyError(err.message || 'Print failed');
    } finally {
      setIsPrinting(false);
    }
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
      {directPrint.isAvailable() && printers.length > 0 && (
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

      {/* ===== Sale Return ===== */}
      {isManager() && receipt.saleId && refundableItems.length > 0 && (
        <Box sx={{ mt: 1 }}>
          <Button
            fullWidth
            variant="outlined"
            color="warning"
            startIcon={<RefundIcon />}
            onClick={() => setReturnDialogOpen(true)}
          >
            {t('return')}
          </Button>
        </Box>
      )}

      <Box sx={{ mt: 1 }}>
        <Button fullWidth variant="text" onClick={() => window.close()}>{t('close')}</Button>
      </Box>

      {/* ===== Sale Return Dialog ===== */}
      <SaleReturnDialog
        open={returnDialogOpen}
        onClose={() => setReturnDialogOpen(false)}
        saleId={receipt.saleId}
      />
    </Box>
  );
};

export default ReceiptPreview;