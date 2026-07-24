import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { Box, Typography, Button, Dialog, DialogTitle, DialogContent, DialogActions, CircularProgress, TextField, Divider, FormControl, InputLabel, Select, MenuItem } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { receiptService, saleService, shopInfoService } from '../api/services';
import { formatDateTime, formatCurrency } from '../utils/helpers';
import { AssignmentReturn as RefundIcon, Print as PrintIcon, Download as DownloadIcon, LocalPrintshop as PrinterIcon } from '@mui/icons-material';
import { notifySuccess, notifyError } from '../utils/notify';
import ShopLogo from '../components/ShopLogo';
import { useAuth } from '../context/AuthContext';
// 👇 Replace Bluetooth imports with QZ Tray imports
import { connectQZ, printReceiptViaQZ, isQZSupported, getAvailablePrinters } from '../utils/bluetoothPrinter';

const ReceiptPreview = () => {
  const { invoiceNumber } = useParams();
  const [printDialogOpen, setPrintDialogOpen] = useState(false);
  const [refundDialogOpen, setRefundDialogOpen] = useState(false);
  const [refundReason, setRefundReason] = useState('');
  const [refundQuantities, setRefundQuantities] = useState({});
  const [printers, setPrinters] = useState([]);
  const [selectedPrinter, setSelectedPrinter] = useState('');
  const [isPrinting, setIsPrinting] = useState(false);
  const queryClient = useQueryClient();
  const { isManager } = useAuth();

  const { data, isLoading } = useQuery({
    queryKey: ['receipt', invoiceNumber],
    queryFn: () => receiptService.getByInvoiceNumber(invoiceNumber),
  });

  const { data: shopInfoData } = useQuery({
    queryKey: ['shopInfo-preview'],
    queryFn: () => shopInfoService.get(),
  });

  // 👇 Load available printers on mount
  useState(() => {
    if (isQZSupported()) {
      getAvailablePrinters().then(setPrinters);
    }
  }, []);

  const refundMutation = useMutation({
    mutationFn: ({ saleId, payload }) => saleService.refundSale(saleId, payload),
    onSuccess: () => {
      notifySuccess('Refund processed');
      setRefundDialogOpen(false);
      setRefundReason('');
      setRefundQuantities({});
      queryClient.invalidateQueries({ queryKey: ['receipt', invoiceNumber] });
      queryClient.invalidateQueries({ queryKey: ['sales'] });
    },
    onError: (err) => notifyError(err.friendlyMessage || 'Failed to process refund'),
  });

  if (isLoading) return <CircularProgress />;
  if (!data?.data) return <Typography>Receipt not found</Typography>;

  const receipt = data.data;
  const refundableItems = receipt.items?.filter((item) => (item.quantity || 0) - (item.quantityRefunded || 0) > 0) || [];
  const refundTotal = refundableItems.reduce((sum, item) => {
    const quantity = Number(refundQuantities[item.saleItemId] || 0);
    return sum + quantity * Number(item.unitPrice || 0);
  }, 0);

  const handlePrint = () => {
    window.print();
  };

  const handleDownload = async (format) => {
    try {
      const blob = await receiptService.downloadReceipt(invoiceNumber, format);
      const url = window.URL.createObjectURL(new Blob([blob]));
      const link = document.createElement('a');
      link.href = url;
      link.download = `receipt-${invoiceNumber}.${format}`;
      link.click();
      window.URL.revokeObjectURL(url);
      notifySuccess(`Receipt downloaded as ${format.toUpperCase()}`);
    } catch (err) {
      notifyError(err.friendlyMessage || 'Failed to download receipt');
    }
  };

  // 👇 Replace Bluetooth print with QZ Tray print
  const handleQZPrint = async () => {
    setIsPrinting(true);
    try {
      await printReceiptViaQZ(receipt, shopInfoData?.data || {}, selectedPrinter || null);
    } catch (err) {
      // Error is already handled and notified inside printReceiptViaQZ
    } finally {
      setIsPrinting(false);
    }
  };

  const setRefundQuantity = (item, value) => {
    const max = (item.quantity || 0) - (item.quantityRefunded || 0);
    const quantity = Math.max(0, Math.min(max, Number(value) || 0));
    setRefundQuantities((current) => ({ ...current, [item.saleItemId]: quantity }));
  };

  const handleRefundSubmit = () => {
    const items = refundableItems
      .map((item) => ({ saleItemId: item.saleItemId, quantity: Number(refundQuantities[item.saleItemId] || 0) }))
      .filter((item) => item.quantity > 0);

    if (!receipt.saleId || items.length === 0 || !refundReason.trim()) {
      return;
    }

    refundMutation.mutate({
      saleId: receipt.saleId,
      payload: { reason: refundReason.trim(), items },
    });
  };

  return (
    <Box sx={{ p: 3, maxWidth: 400, mx: 'auto' }}>
      <Box sx={{ textAlign: 'center', mb: 2, fontFamily: 'monospace' }}>
        <ReceiptHeader
            shopName={shopInfoData?.data?.shopName}
            hasLogo={shopInfoData?.data?.hasLogo}
        />
        <Typography variant="body2">Invoice: {receipt.invoiceNumber}</Typography>
        <Typography variant="body2">{formatDateTime(receipt.saleDate)}</Typography>
        <Typography variant="body2">Customer: {receipt.customerName || 'Walk-in'}</Typography>
      </Box>

      <Box sx={{ borderTop: '1px dashed #999', borderBottom: '1px dashed #999', py: 2, fontFamily: 'monospace' }}>
        {receipt.items?.map((item, idx) => (
          <Box key={idx} sx={{ mb: 1 }}>
            <Box sx={{ display: 'flex', justifyContent: 'space-between' }}>
              <Typography variant="body2">{item.productName} x{item.quantity}</Typography>
              <Typography variant="body2">{formatCurrency(Number(item.unitPrice || 0) * Number(item.quantity || 0))}</Typography>
            </Box>
            {(item.quantityRefunded || 0) > 0 && (
              <Typography variant="caption" color="warning.main">Refunded: {item.quantityRefunded}</Typography>
            )}
          </Box>
        ))}
      </Box>

      <Box sx={{ fontFamily: 'monospace', mt: 2 }}>
        <Box sx={{ display: 'flex', justifyContent: 'space-between' }}>
          <Typography variant="body2">Total:</Typography>
          <Typography variant="body2">{formatCurrency(receipt.totalAmount)}</Typography>
        </Box>
        <Box sx={{ display: 'flex', justifyContent: 'space-between' }}>
          <Typography variant="body2">Paid:</Typography>
          <Typography variant="body2">{formatCurrency(receipt.amountPaid)}</Typography>
        </Box>
        <Box sx={{ display: 'flex', justifyContent: 'space-between' }}>
          <Typography variant="body2">Change:</Typography>
          <Typography variant="body2">{formatCurrency(receipt.amountPaid - receipt.totalAmount)}</Typography>
        </Box>
      </Box>

      <Box sx={{ mt: 3, textAlign: 'center' }}>
        <Typography variant="caption">Thank you for your business!</Typography>
      </Box>

      {/* 👇 QZ Tray Printer Selection and Print Button */}
      {!isQZSupported() && (
        <Box sx={{ mt: 2, p: 2, bgcolor: 'error.light', color: 'error.dark', borderRadius: 1, textAlign: 'center' }}>
          QZ Tray is not detected. Please ensure QZ Tray is installed and running.
        </Box>
      )}

      {isQZSupported() && printers.length > 0 && (
        <FormControl fullWidth size="small" sx={{ mt: 2 }}>
          <InputLabel>Printer</InputLabel>
          <Select
            value={selectedPrinter}
            label="Printer"
            onChange={(e) => setSelectedPrinter(e.target.value)}
          >
            {printers.map((p, idx) => (
              <MenuItem key={idx} value={p}>{p}</MenuItem>
            ))}
          </Select>
        </FormControl>
      )}

      <Box sx={{ mt: 2, display: 'flex', gap: 1 }}>
        <Button fullWidth variant="contained" startIcon={<PrintIcon />} onClick={handlePrint}>Print (Browser)</Button>
        <Button fullWidth variant="outlined" startIcon={<DownloadIcon />} onClick={() => handleDownload('pdf')}>PDF</Button>
        <Button fullWidth variant="outlined" startIcon={<DownloadIcon />} onClick={() => handleDownload('png')}>PNG</Button>
      </Box>

      {/* 👇 QZ Tray Print Button (replaces Bluetooth) */}
      {isQZSupported() && (
        <Box sx={{ mt: 1 }}>
          <Button
            fullWidth
            variant="outlined"
            startIcon={<PrinterIcon />}
            onClick={handleQZPrint}
            disabled={isPrinting}
          >
            {isPrinting ? 'Printing...' : 'Print via QZ Tray'}
          </Button>
        </Box>
      )}

      {isManager() && refundableItems.length > 0 && (
        <Box sx={{ mt: 1 }}>
          <Button fullWidth variant="outlined" color="warning" startIcon={<RefundIcon />} onClick={() => setRefundDialogOpen(true)}>
            Refund
          </Button>
        </Box>
      )}
      <Box sx={{ mt: 1 }}>
        <Button fullWidth variant="text" onClick={() => window.close()}>Close</Button>
      </Box>

      {/* Refund Dialog remains exactly the same */}
      <Dialog open={refundDialogOpen} onClose={() => setRefundDialogOpen(false)} fullWidth maxWidth="sm">
        <DialogTitle>Refund Items</DialogTitle>
        <DialogContent>
          <Typography variant="body2" sx={{ mb: 2 }}>Invoice: {receipt.invoiceNumber}</Typography>
          {refundableItems.map((item) => {
            const max = (item.quantity || 0) - (item.quantityRefunded || 0);
            return (
              <Box key={item.saleItemId} sx={{ display: 'grid', gridTemplateColumns: '1fr 96px', gap: 2, alignItems: 'center', mb: 2 }}>
                <Box>
                  <Typography variant="body2" fontWeight={600}>{item.productName}</Typography>
                  <Typography variant="caption" color="text.secondary">
                    Available: {max} @ {formatCurrency(item.unitPrice)}
                  </Typography>
                </Box>
                <TextField
                  size="small"
                  type="number"
                  label="Qty"
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
            label="Reason"
            value={refundReason}
            onChange={(e) => setRefundReason(e.target.value)}
          />
          <Box sx={{ display: 'flex', justifyContent: 'space-between', mt: 2 }}>
            <Typography fontWeight={600}>Refund Total</Typography>
            <Typography fontWeight={600}>{formatCurrency(refundTotal)}</Typography>
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setRefundDialogOpen(false)}>Cancel</Button>
          <Button
            onClick={handleRefundSubmit}
            color="warning"
            variant="contained"
            disabled={refundMutation.isPending || refundTotal <= 0 || !refundReason.trim()}
          >
            Refund
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

const ReceiptHeader = ({ shopName, hasLogo }) => {
  return (
    <>
      <Box sx={{ display: 'flex', justifyContent: 'center', mb: 1 }}>
        <ShopLogo size={56}  hasLogo={hasLogo} />
      </Box>
      <Typography variant="h6">{shopName || 'Shop'}</Typography>
    </>
  );
};

export default ReceiptPreview;