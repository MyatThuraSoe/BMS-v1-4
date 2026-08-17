import { useState, useRef, useEffect } from 'react';
import { useParams } from 'react-router-dom';
import { Box, Typography, Button, Dialog, DialogTitle, DialogContent, DialogActions, CircularProgress, TextField, Divider, FormControl, InputLabel, Select, MenuItem } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { receiptService, saleService, shopInfoService, receiptCustomizationService } from '../api/services';
import { formatReceiptDateTime, formatCurrency } from '../utils/helpers';
import { AssignmentReturn as RefundIcon, Print as PrintIcon, Download as DownloadIcon, LocalPrintshop as PrinterIcon, FlashOn as DirectPrintIcon } from '@mui/icons-material';
import { notifySuccess, notifyError } from '../utils/notify';
import ShopLogo from '../components/ShopLogo';
import { useAuth } from '../context/AuthContext';
import { connectQZ, printReceiptViaQZ, isQZSupported, getAvailablePrinters, getReceiptPreviewWidth } from '../utils/bluetoothPrinter';
import directPrint from '../services/directPrintService';

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
  
  const receiptRef = useRef();

  const { data, isLoading } = useQuery({
    queryKey: ['receipt', invoiceNumber],
    queryFn: () => receiptService.getByInvoiceNumber(invoiceNumber),
  });

  const { data: shopInfoData } = useQuery({
    queryKey: ['shopInfo-preview'],
    queryFn: () => shopInfoService.get(),
  });

  const { data: customizationData } = useQuery({
    queryKey: ['receipt-customization-preview'],
    queryFn: () => receiptCustomizationService.get(),
  });

  useEffect(() => {
    if (isQZSupported()) {
      // ✅ FIX: Connect to QZ Tray FIRST, then get printers
      connectQZ()
        .then(() => getAvailablePrinters())
        .then(setPrinters)
        .catch((err) => {
          console.error("QZ Tray connection failed:", err);
          // notifyError("Could not connect to QZ Tray. Is the app running?");
        });
    } else if (directPrint.isAvailable()) {
      directPrint.getPrinters().then(list => {
        setPrinters(list.map(p => p.name));
        if (list.length > 0) {
          const def = list.find(p => p.isDefault) || list[0];
          setSelectedPrinter(def.name);
        }
      });
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
      queryClient.invalidateQueries({ queryKey: ['products'] });
      queryClient.invalidateQueries({ queryKey: ['low-stock'] });
      queryClient.invalidateQueries({ queryKey: ['inventoryReport'] });
    },
    onError: (err) => notifyError(err.friendlyMessage || 'Failed to process refund'),
  });

  if (isLoading) return <CircularProgress />;
  if (!data?.data) return <Typography>Receipt not found</Typography>;

  const receipt = data.data;
  const customPaperSize = customizationData?.data?.paperSize || shopInfoData?.data?.receiptPaperSize || '58';
  const paperSize = customPaperSize;
  const paperWidthMm = Math.max(20, parseInt(String(paperSize).replace(/\D/g, ''), 10) || 58);
  const previewWidth = getReceiptPreviewWidth(paperSize);
  const receiptHeaderText = customizationData?.data?.headerText ?? '';
  const receiptMainMessage = customizationData?.data?.mainMessage || 'Please keep this receipt for your records.';
  const receiptFooterText = customizationData?.data?.footerText || 'Thank you for your business!';
  const receiptTimeFormat = customizationData?.data?.timeFormat || '12';
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

     const handleQZPrint = async () => {
    setIsPrinting(true);
    try {
      await connectQZ(); // ✅ Ensure connection is active
      await printReceiptViaQZ(receipt, shopInfoData?.data || {}, selectedPrinter || null, receiptTimeFormat);
    } catch (err) {
      // Error is already handled and notified inside printReceiptViaQZ
    } finally {
      setIsPrinting(false);
    }
  };

  const handleDirectPrint = async () => {
    if (!receiptRef.current) return;
    setIsPrinting(true);
    try {
      if (directPrint.isAvailable()) {
        const receiptHtml = receiptRef.current.innerHTML;
        const result = await directPrint.print(receiptHtml, selectedPrinter || null, paperWidthMm);
        if (result.success) {
          notifySuccess('Receipt sent to printer');
        } else {
          notifyError(result.error || 'Print failed');
        }
      } else if (isQZSupported()) {
        await connectQZ(); // ✅ Ensure connection is active
        await printReceiptViaQZ(receipt, shopInfoData?.data || {}, selectedPrinter || null, receiptTimeFormat);
      } else {
        window.print();
      }
    } catch (err) {
      notifyError(err.message || 'Print failed');
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
    <Box sx={{ p: 3, maxWidth: previewWidth, mx: 'auto' }}>
      <Box ref={receiptRef}>
        <Box sx={{ textAlign: 'center', mb: 1, fontFamily: 'monospace', fontSize: '0.9rem' }}>
          <ReceiptHeader
              shopName={shopInfoData?.data?.shopName}
              hasLogo={shopInfoData?.data?.hasLogo}
          />
        </Box>

        {/* Separator */}
        {receiptHeaderText.trim() ? (
          <Typography variant="body2" sx={{ textAlign: 'center', mb: 1, fontFamily: 'monospace', borderTop: '1px dashed #999', borderBottom: '1px dashed #999', py: 0.5 }}>
            {receiptHeaderText}
          </Typography>
        ) : (
          <Box sx={{ borderTop: '1px dashed #999', borderBottom: '1px dashed #999', mb: 1 }} />
        )}

        {/* Invoice Details */}
        <Box sx={{ fontFamily: 'monospace', fontSize: '0.85rem', mb: 1.5 }}>
          <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 0.3 }}>
            <Typography variant="caption">Invoice No:</Typography>
            <Typography variant="caption">{receipt.invoiceNumber}</Typography>
          </Box>
          <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 0.3 }}>
            <Typography variant="caption">Date:</Typography>
            <Typography variant="caption">{formatReceiptDateTime(receipt.saleDate, receiptTimeFormat)}</Typography>
          </Box>
          {receipt.customerName && receipt.customerName !== 'Walk-in' && (
            <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 0.3 }}>
              <Typography variant="caption">Customer:</Typography>
              <Typography variant="caption">{receipt.customerName}</Typography>
            </Box>
          )}
        </Box>

        {/* Separator */}
        <Box sx={{ borderTop: '1px dashed #999', borderBottom: '1px dashed #999', py: 1, fontFamily: 'monospace', fontSize: '0.85rem' }}>
          {receipt.items?.map((item, idx) => (
            <Box key={idx} sx={{ mb: 0.8 }}>
              <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                <Typography variant="caption" noWrap sx={{ minWidth: 0, pr: 1, flex: 1 }}>
                  {item.productName} x{item.quantity}
                </Typography>
                <Typography variant="caption" sx={{ flexShrink: 0, fontWeight: 600 }}>
                  {formatCurrency(Number(item.unitPrice || 0) * Number(item.quantity || 0))}
                </Typography>
              </Box>
              <Typography variant="caption" sx={{ display: 'block', pl: 1, color: 'text.secondary' }}>
                @ {formatCurrency(Number(item.unitPrice || 0))}
              </Typography>
              {(item.quantityRefunded || 0) > 0 && (
                <Typography variant="caption" color="warning.main" sx={{ display: 'block', pl: 1 }}>
                  Refunded: {item.quantityRefunded}
                </Typography>
              )}
            </Box>
          ))}
        </Box>

        {/* Totals */}
        <Box sx={{ fontFamily: 'monospace', fontSize: '0.85rem', mb: 1.5 }}>
          <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 0.3 }}>
            <Typography variant="caption">Subtotal:</Typography>
            <Typography variant="caption">{formatCurrency(receipt.subTotal || receipt.totalAmount)}</Typography>
          </Box>
          {receipt.taxAmount && Number(receipt.taxAmount) > 0 && (
            <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 0.3 }}>
              <Typography variant="caption">Tax:</Typography>
              <Typography variant="caption">{formatCurrency(receipt.taxAmount)}</Typography>
            </Box>
          )}
          {receipt.discountAmount && Number(receipt.discountAmount) > 0 && (
            <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 0.3 }}>
              <Typography variant="caption">Discount:</Typography>
              <Typography variant="caption">-{formatCurrency(receipt.discountAmount)}</Typography>
            </Box>
          )}
        </Box>

        {/* Separator & Total */}
        <Box sx={{ borderTop: '1px dashed #999', borderBottom: '1px dashed #999', py: 0.8, fontFamily: 'monospace' }}>
          <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 0.5 }}>
            <Typography variant="body2" sx={{ fontWeight: 700 }}>TOTAL:</Typography>
            <Typography variant="body2" sx={{ fontWeight: 700 }}>{formatCurrency(receipt.totalAmount)}</Typography>
          </Box>
          <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 0.5 }}>
            <Typography variant="caption">Paid:</Typography>
            <Typography variant="caption">{formatCurrency(receipt.amountPaid)}</Typography>
          </Box>
          <Box sx={{ display: 'flex', justifyContent: 'space-between' }}>
            <Typography variant="caption">Change:</Typography>
            <Typography variant="caption">{formatCurrency(receipt.amountPaid - receipt.totalAmount)}</Typography>
          </Box>
        </Box>

        {/* Footer Message */}
        <Box sx={{ mt: 1.5, textAlign: 'center' }}>
          <Typography variant="caption" sx={{ fontFamily: 'monospace', display: 'block' }}>
            {receiptFooterText}
          </Typography>
        </Box>
      </Box>

      {/* Printer Selection */}
      {(isQZSupported() || directPrint.isAvailable()) && printers.length > 0 && (
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

      {/* Direct Print Button (Always visible) */}
      <Box sx={{ mt: 2 }}>
        <Button
          fullWidth
          variant="contained"
          color="primary"
          size="large"
          startIcon={isPrinting ? <CircularProgress size={20} sx={{ color: 'white' }} /> : <DirectPrintIcon />}
          onClick={handleDirectPrint}
          disabled={isPrinting}
          sx={{ py: 1.2, fontSize: '1rem' }}
        >
          {isPrinting
            ? 'Printing...'
            : directPrint.isAvailable()
              ? '⚡ Direct Print (Silent)'
              : '⚡ Direct Print'}
        </Button>
      </Box>

      {/* Browser Print & Downloads */}
      <Box sx={{ mt: 1, display: 'flex', gap: 1, flexWrap: 'wrap' }}>
        {/* Hide Browser Print in Electron to prevent stuck windows */}
        {!directPrint.isAvailable() && (
          <Button fullWidth sx={{ flex: { xs: '1 1 100%', sm: '1 1 0' } }} variant="outlined" startIcon={<PrintIcon />} onClick={handlePrint}>Print (Browser)</Button>
        )}
        <Button fullWidth sx={{ flex: { xs: '1 1 100%', sm: '1 1 0' } }} variant="outlined" startIcon={<DownloadIcon />} onClick={() => handleDownload('pdf')}>PDF</Button>
        <Button fullWidth sx={{ flex: { xs: '1 1 100%', sm: '1 1 0' } }} variant="outlined" startIcon={<DownloadIcon />} onClick={() => handleDownload('png')}>PNG</Button>
      </Box>

      {/* QZ Tray Print Button */}
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

      {/* Refund Dialog */}
      <Dialog open={refundDialogOpen} onClose={() => setRefundDialogOpen(false)} fullWidth maxWidth="sm">
        <DialogTitle>Refund Items</DialogTitle>
        <DialogContent>
          <Typography variant="body2" sx={{ mb: 2 }}>Invoice No: {receipt.invoiceNumber}</Typography>
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