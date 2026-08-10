import { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Box, Typography, Paper, Table, TableHead, TableBody, TableCell, TableContainer, TableRow, Button, CircularProgress, Grid, Chip, Divider, Stack } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { saleService, receiptService, shopInfoService } from '../api/services';
import { formatDateTime, formatCurrency } from '../utils/helpers';
import { ArrowBack as BackIcon, Person as PersonIcon, AccessTime as TimeIcon, FlashOn as DirectPrintIcon, Download as DownloadIcon } from '@mui/icons-material';
import { useTranslation } from 'react-i18next';
import { notifySuccess, notifyError } from '../utils/notify';
import directPrint from '../services/directPrintService';

// Add connectQZ to the import
import { printReceiptViaQZ, isQZSupported, connectQZ } from '../utils/bluetoothPrinter';


const StatBlock = ({ label, value, color }) => (
  <Box>
    <Typography variant="caption" color="text.secondary">{label}</Typography>
    <Typography variant="h6" fontWeight={600} color={color || 'text.primary'} sx={{ fontFamily: '"IBM Plex Mono", monospace' }}>
      {value}
    </Typography>
  </Box>
);

const SaleDetail = () => {
  const { id } = useParams();
  const navigate = useNavigate();
  const { t } = useTranslation('sales');
  const [isDirectPrinting, setIsDirectPrinting] = useState(false);

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

  if (isLoading) return <Box sx={{ display: 'flex', justifyContent: 'center', p: 6 }}><CircularProgress /></Box>;
  if (!data?.data) return <Typography>{t('sale_not_found')}</Typography>;

  const sale = data.data;
  const netTotal = sale.totalAmount - (sale.totalRefunded || 0);
  const isFullyRefunded = sale.totalRefunded > 0 && Math.abs(netTotal) < 0.01;

  const statusChip = sale.isVoided
    ? <Chip label={t('status_voided')} color="error" />
    : isFullyRefunded
    ? <Chip label={t('status_fully_refunded')} color="warning" />
    : sale.totalRefunded > 0
    ? <Chip label={t('status_partially_refunded')} color="warning" variant="outlined" />
    : <Chip label={t('status_completed')} color="success" />;

  const handleViewReceipt = () => window.open(`/receipt/${sale.invoiceNumber}`, '_blank');

  const handleDirectPrint = async () => {
    if (!sale?.invoiceNumber) return;
    setIsDirectPrinting(true);
    try {
      if (directPrint.isAvailable()) {
        const htmlContent = await receiptService.getPrintHtml(sale.invoiceNumber);
        const result = await directPrint.print(htmlContent, null);
        if (result.success) {
          notifySuccess(t('receipt_sent_printer') || 'Receipt sent to printer');
        } else {
          notifyError(result.error || t('print_failed') || 'Print failed');
        }
      } else if (isQZSupported()) {
        await connectQZ(); // ✅ FIX: Connect before attempting to print
        const receiptRes = await receiptService.getByInvoiceNumber(sale.invoiceNumber);
        await printReceiptViaQZ(receiptRes.data, shopInfo || {});
        notifySuccess(t('receipt_sent_printer') || 'Receipt sent to printer');
      } else {
        handleViewReceipt();
      }
    } catch (err) {
      notifyError(err.message || t('print_failed') || 'Print failed');
    } finally {
      setIsDirectPrinting(false);
    }
  };

  const handleDownload = async (format) => {
    try {
      const blob = await receiptService.downloadReceipt(sale.invoiceNumber, format);
      const url = window.URL.createObjectURL(new Blob([blob]));
      const link = document.createElement('a');
      link.href = url;
      link.download = `receipt-${sale.invoiceNumber}.${format}`;
      link.click();
      window.URL.revokeObjectURL(url);
      notifySuccess(t('receipt_downloaded', { format: format.toUpperCase() }) || `Downloaded ${format.toUpperCase()}`);
    } catch (err) {
      notifyError(err.friendlyMessage || t('download_failed') || 'Download failed');
    }
  };

  return (
    <Box>
      <Button startIcon={<BackIcon />} onClick={() => navigate('/sales')} sx={{ mb: 2 }}>{t('back_to_sales')}</Button>

      <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} spacing={2} sx={{ mb: 3 }}>
        <Box>
          <Typography variant="h4">{sale.invoiceNumber}</Typography>
          <Stack direction="row" spacing={2} alignItems="center" sx={{ mt: 0.5 }}>
            <Stack direction="row" spacing={0.5} alignItems="center">
              <TimeIcon fontSize="small" color="disabled" />
              <Typography variant="body2" color="text.secondary">{formatDateTime(sale.saleDate)}</Typography>
            </Stack>
            <Stack direction="row" spacing={0.5} alignItems="center">
              <PersonIcon fontSize="small" color="disabled" />
              <Typography variant="body2" color="text.secondary">{sale.cashierName || t('unknown_cashier')}</Typography>
            </Stack>
          </Stack>
        </Box>
        <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap">
          {statusChip}
          
          <Button 
            variant="contained" 
            color="primary"
            startIcon={isDirectPrinting ? <CircularProgress size={20} sx={{ color: 'white' }} /> : <DirectPrintIcon />}
            onClick={handleDirectPrint}
            disabled={isDirectPrinting}
          >
            {isDirectPrinting 
              ? (t('printing') || 'Printing...') 
              : directPrint.isAvailable() 
                ? '⚡ Direct Print' 
                : (t('print_receipt') || 'Print')}
          </Button>

          <Button variant="outlined" startIcon={<DownloadIcon />} onClick={() => handleDownload('pdf')}>PDF</Button>
          <Button variant="outlined" startIcon={<DownloadIcon />} onClick={() => handleDownload('png')}>PNG</Button>
        </Stack>
      </Stack>

      {sale.isVoided && sale.voidedReason && (
        <Paper sx={{ p: 2, mb: 2, bgcolor: 'error.50', border: '1px solid', borderColor: 'error.light' }}>
          <Typography variant="body2" color="error.dark"><strong>{t('void_reason_label')}</strong> {sale.voidedReason}</Typography>
        </Paper>
      )}

      <Paper elevation={0} sx={{ p: 3, mb: 3, border: '1px solid', borderColor: 'divider', borderRadius: 3 }}>
        <Grid container spacing={3}>
          <Grid item xs={6} sm={3}><StatBlock label={t('subtotal')} value={formatCurrency(sale.subtotal)} /></Grid>
          <Grid item xs={6} sm={3}><StatBlock label={t('tax')} value={formatCurrency(sale.taxAmount)} /></Grid>
          <Grid item xs={6} sm={3}><StatBlock label={t('discount')} value={formatCurrency(sale.discountAmount)} color={sale.discountAmount > 0 ? 'success.main' : undefined} /></Grid>
          <Grid item xs={6} sm={3}><StatBlock label={t('total')} value={formatCurrency(sale.totalAmount)} /></Grid>
          <Grid item xs={6} sm={3}><StatBlock label={t('paid')} value={formatCurrency(sale.amountPaid)} /></Grid>
          <Grid item xs={6} sm={3}><StatBlock label={t('change')} value={formatCurrency(sale.changeGiven)} /></Grid>
          <Grid item xs={6} sm={3}><StatBlock label={t('customer')} value={sale.customerName || t('walk_in')} /></Grid>
          <Grid item xs={6} sm={3}>
            <StatBlock label={t('net_after_refunds')} value={formatCurrency(netTotal)} color={sale.totalRefunded > 0 ? 'warning.main' : undefined} />
          </Grid>
        </Grid>
      </Paper>

      <Paper elevation={0} sx={{ p: 3, mb: 3, border: '1px solid', borderColor: 'divider', borderRadius: 3 }}>
        <Typography variant="h6" gutterBottom>{t('items')}</Typography>
        <TableContainer sx={{ overflowX: 'auto' }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>{t('product')}</TableCell>
                <TableCell align="right">{t('qty')}</TableCell>
                <TableCell align="right">{t('refunded')}</TableCell>
                <TableCell align="right">{t('price')}</TableCell>
                <TableCell align="right">{t('subtotal')}</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {sale.items?.map((item, idx) => (
                <TableRow key={idx}>
                  <TableCell>{item.productName}</TableCell>
                  <TableCell align="right">{item.quantity}</TableCell>
                  <TableCell align="right">
                    {item.quantityRefunded > 0
                      ? <Chip size="small" label={item.quantityRefunded} color="warning" variant="outlined" />
                      : '-'}
                  </TableCell>
                  <TableCell align="right" sx={{ fontFamily: '"IBM Plex Mono", monospace' }}>{formatCurrency(item.unitPrice)}</TableCell>
                  <TableCell align="right" sx={{ fontFamily: '"IBM Plex Mono", monospace' }}>{formatCurrency(item.totalPrice)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      </Paper>

      {sale.refunds?.length > 0 && (
        <Paper elevation={0} sx={{ p: 3, border: '1px solid', borderColor: 'divider', borderRadius: 3 }}>
          <Typography variant="h6" gutterBottom>{t('refund_history')}</Typography>
          <TableContainer sx={{ overflowX: 'auto' }}>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>{t('date')}</TableCell>
                  <TableCell>{t('reason')}</TableCell>
                  <TableCell>{t('processed_by')}</TableCell>
                  <TableCell align="right">{t('amount')}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {sale.refunds.map((r) => (
                  <TableRow key={r.id}>
                    <TableCell>{formatDateTime(r.refundDate)}</TableCell>
                    <TableCell>{r.reason}</TableCell>
                    <TableCell>{r.refundedByName || '-'}</TableCell>
                    <TableCell align="right" sx={{ fontFamily: '"IBM Plex Mono", monospace' }}>{formatCurrency(r.totalRefundAmount)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
          <Divider sx={{ my: 1.5 }} />
          <Box sx={{ display: 'flex', justifyContent: 'flex-end' }}>
            <Typography fontWeight={600}>{t('total_refunded', { amount: formatCurrency(sale.totalRefunded) })}</Typography>
          </Box>
        </Paper>
      )}
    </Box>
  );
};

export default SaleDetail;