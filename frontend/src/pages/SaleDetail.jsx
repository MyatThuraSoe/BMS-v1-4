import { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { 
  Box, Typography, Paper, Table, TableHead, TableBody, TableCell, 
  TableContainer, TableRow, Button, CircularProgress, Grid, Chip, 
  Divider, Stack, IconButton, Menu, MenuItem, Tooltip, Avatar, ListItemIcon
} from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { saleService, receiptService, shopInfoService, receiptCustomizationService } from '../api/services';
import ReceiptDocument, { generatePrintHtml, generateQRDataUrl } from '../components/ReceiptDocument';
import { formatDateTime, formatCurrency } from '../utils/helpers';
import { 
  ArrowBack as BackIcon, 
  Person as PersonIcon, 
  AccessTime as TimeIcon, 
  FlashOn as DirectPrintIcon, 
  Download as DownloadIcon,
  ShoppingCart as CartIcon,
  LocalOffer as TagIcon,
  Payments as PaymentsIcon,
  History as HistoryIcon,
  Receipt as ReceiptIcon,
  Info as InfoIcon,
  AssignmentReturn as ReturnIcon
} from '@mui/icons-material';
import { useTranslation } from 'react-i18next';
import { notifySuccess, notifyError } from '../utils/notify';
import directPrint from '../services/directPrintService';
import { useAuth } from '../context/AuthContext';
import SaleReturnDialog from '../components/SaleReturnDialog';

const StatCard = ({ label, value, color, icon, highlight, highlightColor = 'success' }) => (
  <Paper 
    elevation={0} 
    sx={{ 
      p: 2.5, 
      borderRadius: 2, 
      bgcolor: highlight ? `${highlightColor}.50` : 'background.paper', 
      border: '1px solid', 
      borderColor: highlight ? `${highlightColor}.200` : 'divider',
      height: '100%',
      display: 'flex',
      flexDirection: 'column',
      justifyContent: 'space-between',
      transition: 'transform 0.2s',
      '&:hover': { transform: 'translateY(-2px)' }
    }}
  >
    <Stack direction="row" spacing={1.5} alignItems="center" sx={{ mb: 1.5 }}>
      {icon && (
        <Avatar sx={{ bgcolor: `${highlightColor}.100`, color: `${highlightColor}.main`, width: 32, height: 32 }}>
          {icon}
        </Avatar>
      )}
      <Typography variant="caption" color="text.secondary" fontWeight={600} sx={{ textTransform: 'uppercase', letterSpacing: '0.5px' }}>
        {label}
      </Typography>
    </Stack>
    <Typography 
      variant="h5" 
      fontWeight={700} 
      color={color || 'text.primary'} 
      sx={{ fontFamily: '"IBM Plex Mono", monospace', letterSpacing: '-0.5px' }}
    >
      {value}
    </Typography>
  </Paper>
);

const SaleDetail = () => {
  const { id } = useParams();
  const navigate = useNavigate();
  const { t } = useTranslation('sales');
  const { isManager } = useAuth();
  const [isDirectPrinting, setIsDirectPrinting] = useState(false);
  const [anchorEl, setAnchorEl] = useState(null);
  const [returnDialogOpen, setReturnDialogOpen] = useState(false);

  const { data, isLoading } = useQuery({
    queryKey: ['sale', id],
    queryFn: () => saleService.getById(id),
  });

  const { data: shopInfoData } = useQuery({
    queryKey: ['shopInfo'],
    queryFn: () => shopInfoService.get(),
    enabled: true,
  });
  const shopInfo = shopInfoData?.data;

  const { data: customizationData } = useQuery({
    queryKey: ['receipt-customization'],
    queryFn: () => receiptCustomizationService.get(),
  });

  if (isLoading) return <Box sx={{ display: 'flex', justifyContent: 'center', p: 6 }}><CircularProgress /></Box>;
  if (!data?.data) return <Typography>{t('sale_not_found')}</Typography>;

  const sale = data.data;
  const totalReturned = Number(sale.totalReturned || 0);
  const netTotal = sale.totalAmount - totalReturned;
  // Backend truth first (returnStatus); fallback to derived totals for old cached payloads
  const returnStatus = sale.returnStatus
    || (totalReturned > 0 && Math.abs(netTotal) < 0.01 ? 'FULLY_RETURNED'
      : totalReturned > 0 ? 'PARTIALLY_RETURNED' : 'COMPLETED');

  const statusChip = sale.isVoided
    ? <Chip label={t('status_voided')} color="error" variant="filled" sx={{ fontWeight: 600 }} />
    : returnStatus === 'FULLY_RETURNED'
    ? <Chip label={t('status_fully_returned')} color="warning" variant="filled" sx={{ fontWeight: 600 }} />
    : returnStatus === 'PARTIALLY_RETURNED'
    ? <Chip label={t('status_partially_returned')} color="warning" variant="outlined" sx={{ fontWeight: 600 }} />
    : <Chip label={t('status_completed')} color="success" variant="filled" sx={{ fontWeight: 600 }} />;

  const hasReturnableItems = !sale.isVoided && sale.items?.some(
    (item) => (item.quantity || 0) - (item.quantityRefunded || 0) > 0
  );

  const handleViewReceipt = () => window.open(`/receipt/${sale.invoiceNumber}`, '_blank');

  const handleDirectPrint = async () => {
    if (!sale?.invoiceNumber) return;
    setIsDirectPrinting(true);
    try {
      if (directPrint.isAvailable()) {
        // Same pipeline as the POS page: ReceiptDocument HTML → silent print,
        // so the paper is identical no matter where it was printed from.
        const customization = customizationData?.data || {};
        let logoDataUrl = null;
        if (shopInfo?.hasLogo) {
          try {
            const blob = await shopInfoService.getLogo();
            logoDataUrl = await new Promise((resolve) => {
              const reader = new FileReader();
              reader.onload  = () => resolve(reader.result);
              reader.onerror = () => resolve(null);
              reader.readAsDataURL(blob);
            });
          } catch { /* no logo */ }
        }
        const paperWidthMm = Math.max(40, parseInt(String(customization.paperSize || '58').replace(/\D/g, ''), 10) || 58);
        let qrDataUrl = null;
        if (customization?.showQRCode) {
          qrDataUrl = await generateQRDataUrl(sale.invoiceNumber);
        }
        const html = generatePrintHtml(sale, shopInfo || {}, customization, logoDataUrl, qrDataUrl);
        const result = await directPrint.print(html, null, paperWidthMm);
        if (result.success) {
          notifySuccess(t('receipt_sent_printer') || 'Receipt sent to printer');
        } else {
          notifyError(result.error || t('print_failed') || 'Print failed');
        }
      } else {
        handleViewReceipt();
      }
    } catch (err) {
      notifyError(err.message || t('print_failed') || 'Print failed');
    } finally {
      setIsDirectPrinting(false);
    }
  };
  // Helper function to safely convert Base64 strings to Blobs
  const base64ToBlob = (base64, mimeType) => {
    // Remove data URI prefix if it exists (e.g., "data:application/pdf;base64,")
    const base64Data = base64.includes(',') ? base64.split(',')[1] : base64;
    const byteCharacters = atob(base64Data);
    const byteNumbers = new Array(byteCharacters.length);
    for (let i = 0; i < byteCharacters.length; i++) {
      byteNumbers[i] = byteCharacters.charCodeAt(i);
    }
    const byteArray = new Uint8Array(byteNumbers);
    return new Blob([byteArray], { type: mimeType });
  };

  const handleDownload = async (format) => {
    try {
      const response = await receiptService.downloadReceipt(sale.invoiceNumber, format);
      const mimeType = format === 'pdf' ? 'application/pdf' : 'image/png';
      let blob;

      // 1. If the response is already a valid Blob (e.g. Axios with responseType: 'blob')
      if (response instanceof Blob) {
        blob = response;
      } 
      // 2. If the response is an Axios response object containing a Blob
      else if (response?.data instanceof Blob) {
        blob = response.data;
      }
      // 3. If the response is a raw Base64 string
      else if (typeof response === 'string') {
        blob = base64ToBlob(response, mimeType);
      } 
      // 4. If the response is a JSON object containing a Base64 string or file data
      else if (response && typeof response === 'object') {
        // Check common property names where backends hide the file data
        const data = response.data || response.file || response.content || response.base64 || response;
        if (typeof data === 'string') {
          blob = base64ToBlob(data, mimeType);
        } else if (data instanceof Blob) {
          blob = data;
        } else {
          // Fallback
          blob = new Blob([typeof data === 'string' ? data : JSON.stringify(data)], { type: mimeType });
        }
      }

      if (!blob) {
        throw new Error('Could not process file from server.');
      }

      const url = window.URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `receipt-${sale.invoiceNumber}.${format}`;
      
      // Append to body, click, and remove (Required for Firefox and Safari)
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      window.URL.revokeObjectURL(url);
      
      notifySuccess(t('receipt_downloaded', { format: format.toUpperCase() }) || `Downloaded ${format.toUpperCase()}`);
    } catch (err) {
      console.error("Download Error:", err);
      notifyError(err?.friendlyMessage || err?.message || t('download_failed') || 'Download failed');
    }
  };
  const handleMenuOpen = (event) => setAnchorEl(event.currentTarget);
  const handleMenuClose = () => setAnchorEl(null);

  return (
    <Box sx={{ maxWidth: 1200, mx: 'auto' }}>
      <Button 
        startIcon={<BackIcon />} 
        onClick={() => navigate('/sales')} 
        sx={{ mb: 3, color: 'text.secondary', textTransform: 'none', fontWeight: 500 }}
      >
        {t('back_to_sales')}
      </Button>

      <Stack direction={{ xs: 'column', md: 'row' }} justifyContent="space-between" alignItems={{ md: 'flex-start' }} spacing={3} sx={{ mb: 4 }}>
        <Box>
          <Stack direction="row" spacing={2} alignItems="center" sx={{ mb: 1.5 }}>
            <Typography variant="h4" fontWeight={800} sx={{ letterSpacing: '-0.5px', color: 'text.primary' }}>
              {sale.invoiceNumber}
            </Typography>
            {statusChip}
          </Stack>
          <Stack direction="row" spacing={3} flexWrap="wrap">
            <Stack direction="row" spacing={0.5} alignItems="center">
              <TimeIcon fontSize="small" sx={{ color: 'success.main' }} />
              <Typography variant="body2" color="text.secondary" fontWeight={500}>
                {formatDateTime(sale.saleDate)}
              </Typography>
            </Stack>
            <Stack direction="row" spacing={0.5} alignItems="center">
              <PersonIcon fontSize="small" sx={{ color: 'success.main' }} />
              <Typography variant="body2" color="text.secondary" fontWeight={500}>
                {sale.cashierName || t('unknown_cashier')}
              </Typography>
            </Stack>
          </Stack>
        </Box>

        <Stack direction="row" spacing={1.5} alignItems="center">
          {isManager() && hasReturnableItems && (
            <Button
              variant="outlined"
              color="warning"
              startIcon={<ReturnIcon />}
              onClick={() => setReturnDialogOpen(true)}
              sx={{ textTransform: 'none', fontWeight: 600 }}
            >
              {t('return')}
            </Button>
          )}
          <Button 
            variant="contained" 
            color="success"
            startIcon={isDirectPrinting ? <CircularProgress size={20} sx={{ color: 'white' }} /> : <DirectPrintIcon />}
            onClick={handleDirectPrint}
            disabled={isDirectPrinting}
            sx={{ 
              textTransform: 'none', 
              fontWeight: 600, 
              px: 3, 
              boxShadow: '0 4px 12px rgba(76, 175, 80, 0.3)' 
            }}
          >
            {isDirectPrinting 
              ? (t('printing') || 'Printing...') 
              : directPrint.isAvailable() 
                ? '⚡ Direct Print' 
                : (t('print_receipt') || 'Print')}
          </Button>

          <Tooltip title={t('download') || 'Download'}>
            <IconButton onClick={handleMenuOpen} sx={{ border: '1px solid', borderColor: 'divider', bgcolor: 'background.paper' }}>
              <DownloadIcon />
            </IconButton>
          </Tooltip>
          <Menu anchorEl={anchorEl} open={Boolean(anchorEl)} onClose={handleMenuClose}>
            <MenuItem onClick={() => { handleDownload('pdf'); handleMenuClose(); }}>
              <ListItemIcon><ReceiptIcon fontSize="small" /></ListItemIcon>
              {t('download_pdf') || 'Download PDF'}
            </MenuItem>
            <MenuItem onClick={() => { handleDownload('png'); handleMenuClose(); }}>
              <ListItemIcon><TagIcon fontSize="small" /></ListItemIcon>
              {t('download_png') || 'Download PNG'}
            </MenuItem>
          </Menu>
        </Stack>
      </Stack>

      {sale.isVoided && sale.voidedReason && (
        <Paper sx={{ p: 2.5, mb: 4, bgcolor: 'error.50', border: '1px solid', borderColor: 'error.light', borderRadius: 2, display: 'flex', alignItems: 'center', gap: 2 }}>
          <Avatar sx={{ bgcolor: 'error.light', color: 'error.dark', width: 40, height: 40 }}>
            <InfoIcon />
          </Avatar>
          <Box>
            <Typography variant="subtitle2" color="error.dark" fontWeight={700}>{t('void_reason_label')}</Typography>
            <Typography variant="body2" color="error.dark">{sale.voidedReason}</Typography>
          </Box>
        </Paper>
      )}

      <Typography variant="h6" fontWeight={700} sx={{ mb: 2.5, display: 'flex', alignItems: 'center', gap: 1 }}>
        <PaymentsIcon color="success" /> {t('financial_summary') || 'Financial Summary'}
      </Typography>
      <Grid container spacing={2.5} sx={{ mb: 5 }}>
        <Grid item xs={6} md={3}>
          <StatCard label={t('subtotal')} value={formatCurrency(sale.subtotal)} icon={<CartIcon />} />
        </Grid>
        <Grid item xs={6} md={3}>
          <StatCard label={t('tax')} value={formatCurrency(sale.taxAmount)} icon={<TagIcon />} />
        </Grid>
        <Grid item xs={6} md={3}>
          <StatCard 
            label={t('discount')} 
            value={formatCurrency(sale.discountAmount)} 
            color={sale.discountAmount > 0 ? 'success.main' : undefined} 
            icon={<TagIcon />} 
          />
        </Grid>
        <Grid item xs={6} md={3}>
          <StatCard 
            label={t('total')} 
            value={formatCurrency(sale.totalAmount)} 
            icon={<PaymentsIcon />} 
            highlight 
          />
        </Grid>
        
        <Grid item xs={6} md={3}>
          <StatCard label={t('paid')} value={formatCurrency(sale.amountPaid)} />
        </Grid>
        <Grid item xs={6} md={3}>
          <StatCard label={t('change')} value={formatCurrency(sale.changeGiven)} />
        </Grid>
        <Grid item xs={6} md={3}>
          <StatCard label={t('customer')} value={sale.customerName || t('walk_in')} icon={<PersonIcon />} />
        </Grid>
        <Grid item xs={6} md={3}>
          <StatCard 
            label={t('net_after_returns')} 
            value={formatCurrency(netTotal)} 
            color={totalReturned > 0 ? 'warning.main' : 'success.main'} 
            highlight={totalReturned > 0}
            highlightColor={totalReturned > 0 ? 'warning' : 'success'}
            icon={<HistoryIcon />}
          />
        </Grid>
      </Grid>

      <Paper elevation={0} sx={{ p: 3, mb: 4, border: '1px solid', borderColor: 'divider', borderRadius: 3 }}>
        <Typography variant="h6" fontWeight={700} gutterBottom sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 2 }}>
          <CartIcon color="success" /> {t('items')}
        </Typography>
        <TableContainer sx={{ overflowX: 'auto' }}>
          <Table size="small">
            <TableHead>
              <TableRow sx={{ '& th': { bgcolor: 'grey.50', fontWeight: 700, color: 'text.secondary', borderBottom: '2px solid', borderColor: 'divider' } }}>
                <TableCell>{t('product')}</TableCell>
                <TableCell align="right">{t('qty')}</TableCell>
                <TableCell align="right">{t('refunded')}</TableCell>
                <TableCell align="right">{t('price')}</TableCell>
                <TableCell align="right">{t('subtotal')}</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {sale.items?.map((item, idx) => (
                <TableRow key={idx} sx={{ '&:last-child td': { border: 0 }, '&:hover': { bgcolor: 'grey.50' } }}>
                  <TableCell sx={{ fontWeight: 500 }}>{item.productName}</TableCell>
                  <TableCell align="right">{item.quantity}</TableCell>
                  <TableCell align="right">
                    {item.quantityRefunded > 0
                      ? <Chip size="small" label={item.quantityRefunded} color="warning" variant="outlined" sx={{ fontWeight: 600 }} />
                      : <Typography variant="body2" color="text.disabled">-</Typography>}
                  </TableCell>
                  <TableCell align="right" sx={{ fontFamily: '"IBM Plex Mono", monospace' }}>{formatCurrency(item.unitPrice)}</TableCell>
                  <TableCell align="right" sx={{ fontFamily: '"IBM Plex Mono", monospace', fontWeight: 600 }}>{formatCurrency(item.totalPrice)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      </Paper>

      {sale.returns?.length > 0 && (
        <Paper elevation={0} sx={{ p: 3, border: '1px solid', borderColor: 'divider', borderRadius: 3 }}>
          <Typography variant="h6" fontWeight={700} gutterBottom sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 2 }}>
            <HistoryIcon color="success" /> {t('return_history')}
          </Typography>
          <TableContainer sx={{ overflowX: 'auto' }}>
            <Table size="small">
              <TableHead>
                <TableRow sx={{ '& th': { bgcolor: 'grey.50', fontWeight: 700, color: 'text.secondary', borderBottom: '2px solid', borderColor: 'divider' } }}>
                  <TableCell>{t('date')}</TableCell>
                  <TableCell>{t('reason')}</TableCell>
                  <TableCell>{t('processed_by')}</TableCell>
                  <TableCell align="right">{t('amount')}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {sale.returns.map((r) => (
                  <TableRow key={r.id} sx={{ '&:last-child td': { border: 0 }, '&:hover': { bgcolor: 'grey.50' } }}>
                    <TableCell>{formatDateTime(r.returnDate)}</TableCell>
                    <TableCell>{r.reason}</TableCell>
                    <TableCell>{r.returnedByUsername || '-'}</TableCell>
                    <TableCell align="right" sx={{ fontFamily: '"IBM Plex Mono", monospace', fontWeight: 600, color: 'warning.main' }}>{formatCurrency(r.totalReturnAmount)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
          <Divider sx={{ my: 2.5 }} />
          <Box sx={{ display: 'flex', justifyContent: 'flex-end' }}>
            <Paper sx={{ px: 3, py: 1.5, bgcolor: 'warning.50', border: '1px solid', borderColor: 'warning.light', borderRadius: 2 }}>
              <Typography fontWeight={700} color="warning.dark" sx={{ fontFamily: '"IBM Plex Mono", monospace' }}>
                {t('total_returned', { amount: formatCurrency(totalReturned) })}
              </Typography>
            </Paper>
          </Box>
        </Paper>
      )}

      <SaleReturnDialog
        open={returnDialogOpen}
        onClose={() => setReturnDialogOpen(false)}
        saleId={sale.id}
      />
    </Box>
  );
};

export default SaleDetail;
