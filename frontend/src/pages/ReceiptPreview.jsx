import { useState, useEffect } from 'react';
import { useParams } from 'react-router-dom';
import { Box, Typography, Paper, Button, Dialog, DialogTitle, DialogContent, DialogActions, CircularProgress, TextField, IconButton, Chip } from '@mui/material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { receiptService, shopInfoService, saleService } from '../api/services';
import { formatDateTime, formatCurrency } from '../utils/helpers';
import { Print as PrintIcon, Download as DownloadIcon, Refund as RefundIcon, Add as AddIcon, Remove as RemoveIcon } from '@mui/icons-material';
import { notifySuccess, notifyError } from '../utils/notify';
import { useAuth } from '../context/AuthContext';

const ReceiptPreview = () => {
  const { invoiceNumber } = useParams();
  const { isManager } = useAuth();
  const [printDialogOpen, setPrintDialogOpen] = useState(false);
  const [shopInfo, setShopInfo] = useState(null);
  const [logoUrl, setLogoUrl] = useState(null);
  const [refundDialogOpen, setRefundDialogOpen] = useState(false);
  const [refundItems, setRefundItems] = useState([]);
  const [refundReason, setRefundReason] = useState('');
  const queryClient = useQueryClient();

  // Fetch shop info for branding
  useEffect(() => {
    const fetchShopInfo = async () => {
      try {
        const response = await shopInfoService.getShopInfo();
        const info = response?.data || null;
        setShopInfo(info);
        
        if (info?.hasLogo) {
          try {
            const logoBlob = await shopInfoService.getLogo();
            const url = URL.createObjectURL(logoBlob);
            setLogoUrl(url);
          } catch (err) {
            setLogoUrl(null);
          }
        }
      } catch (err) {
        setShopInfo(null);
      }
    };
    
    fetchShopInfo();
    
    return () => {
      if (logoUrl) {
        URL.revokeObjectURL(logoUrl);
      }
    };
  }, []);

  const { data, isLoading } = useQuery({
    queryKey: ['receipt', invoiceNumber],
    queryFn: () => receiptService.getByInvoiceNumber(invoiceNumber),
  });

  if (isLoading) return <CircularProgress />;
  if (!data?.data) return <Typography>Receipt not found</Typography>;

  const receipt = data.data;
  const shopName = shopInfo?.shopName || 'BUSINESS MANAGEMENT SYSTEM';

  // Initialize refund items when receipt data loads
  useEffect(() => {
    if (receipt?.items) {
      const initialRefundItems = receipt.items.map(item => ({
        ...item,
        quantityToRefund: 0,
        maxRefundable: item.quantity - (item.quantityRefunded || 0)
      })).filter(item => item.maxRefundable > 0);
      setRefundItems(initialRefundItems);
    }
  }, [receipt]);

  const refundMutation = useMutation({
    mutationFn: async (refundData) => {
      const response = await saleService.refundSale(receipt.id, refundData);
      return response.data;
    },
    onSuccess: () => {
      notifySuccess('Refund processed successfully');
      setRefundDialogOpen(false);
      setRefundReason('');
      queryClient.invalidateQueries(['receipt', invoiceNumber]);
    },
    onError: (error) => {
      notifyError(error.friendlyMessage || 'Failed to process refund');
    }
  });

  const handleRefundSubmit = () => {
    if (!refundReason.trim()) {
      notifyError('Please provide a reason for the refund');
      return;
    }

    const itemsToRefund = refundItems
      .filter(item => item.quantityToRefund > 0)
      .map(item => ({
        saleItemId: item.saleItemId || item.id,
        quantity: item.quantityToRefund
      }));

    if (itemsToRefund.length === 0) {
      notifyError('Please select at least one item to refund');
      return;
    }

    refundMutation.mutate({
      reason: refundReason,
      items: itemsToRefund
    });
  };

  const updateRefundQuantity = (itemId, delta) => {
    setRefundItems(prev => prev.map(item => {
      if ((item.saleItemId || item.id) === itemId) {
        const newQty = Math.max(0, Math.min(item.maxRefundable, (item.quantityToRefund || 0) + delta));
        return { ...item, quantityToRefund: newQty };
      }
      return item;
    }));
  };

  const calculateRefundTotal = () => {
    return refundItems.reduce((total, item) => {
      return total + (item.price || item.unitPrice) * (item.quantityToRefund || 0);
    }, 0);
  };

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

  return (
    <Box sx={{ p: 3, maxWidth: 400, mx: 'auto' }}>
      <Box sx={{ textAlign: 'center', mb: 2, fontFamily: 'monospace' }}>
        {logoUrl && (
          <Box component="img" src={logoUrl} alt="Shop Logo" sx={{ maxWidth: 80, maxHeight: 80, mb: 1 }} />
        )}
        <Typography variant="h6">{shopName}</Typography>
        {shopInfo?.address && (
          <Typography variant="body2" sx={{ fontSize: 10 }}>{shopInfo.address}</Typography>
        )}
        {shopInfo?.phone && (
          <Typography variant="body2" sx={{ fontSize: 10 }}>Tel: {shopInfo.phone}</Typography>
        )}
        {shopInfo?.email && (
          <Typography variant="body2" sx={{ fontSize: 10 }}>{shopInfo.email}</Typography>
        )}
        <Typography variant="body2">Invoice: {receipt.invoiceNumber}</Typography>
        <Typography variant="body2">{formatDateTime(receipt.saleDate)}</Typography>
        <Typography variant="body2">Cashier: {receipt.cashierName}</Typography>
        <Typography variant="body2">Customer: {receipt.customerName || 'Walk-in'}</Typography>
      </Box>

      <Box sx={{ borderTop: '1px dashed #999', borderBottom: '1px dashed #999', py: 2, fontFamily: 'monospace' }}>
        {receipt.items?.map((item, idx) => (
          <Box key={idx} sx={{ display: 'flex', justifyContent: 'space-between', mb: 1 }}>
            <Typography variant="body2">{item.productName} x{item.quantity}</Typography>
            <Typography variant="body2">{formatCurrency(item.price * item.quantity)}</Typography>
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

      {isManager() && (
        <Box sx={{ mt: 2, display: 'flex', gap: 1 }}>
          <Button 
            fullWidth 
            variant="outlined" 
            startIcon={<RefundIcon />} 
            onClick={() => setRefundDialogOpen(true)}
            disabled={receipt.isVoided || refundItems.length === 0}
          >
            Refund
          </Button>
        </Box>
      )}

      <Box sx={{ mt: 2, display: 'flex', gap: 1 }}>
        <Button fullWidth variant="contained" startIcon={<PrintIcon />} onClick={handlePrint}>Print</Button>
        <Button fullWidth variant="outlined" startIcon={<DownloadIcon />} onClick={() => handleDownload('pdf')}>PDF</Button>
        <Button fullWidth variant="outlined" startIcon={<DownloadIcon />} onClick={() => handleDownload('png')}>PNG</Button>
      </Box>
      <Box sx={{ mt: 1 }}>
        <Button fullWidth variant="text" onClick={() => window.close()}>Close</Button>
      </Box>

      {/* Refund Dialog */}
      <Dialog open={refundDialogOpen} onClose={() => setRefundDialogOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>Process Refund</DialogTitle>
        <DialogContent>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            Invoice: {receipt.invoiceNumber}
          </Typography>

          <TextField
            fullWidth
            multiline
            rows={3}
            label="Reason for refund"
            value={refundReason}
            onChange={(e) => setRefundReason(e.target.value)}
            required
            sx={{ mb: 2 }}
          />

          <Typography variant="subtitle2" sx={{ mb: 1 }}>Items to Refund:</Typography>
          {refundItems.map((item) => (
            <Box key={item.saleItemId || item.id} sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 1, p: 1, bgcolor: 'background.default', borderRadius: 1 }}>
              <Box sx={{ flex: 1 }}>
                <Typography variant="body2">{item.productName}</Typography>
                <Typography variant="caption" color="text.secondary">
                  Original: {item.quantity} | Already refunded: {item.quantityRefunded || 0} | Available: {item.maxRefundable}
                </Typography>
              </Box>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                <IconButton 
                  size="small" 
                  onClick={() => updateRefundQuantity(item.saleItemId || item.id, -1)}
                  disabled={item.quantityToRefund <= 0}
                >
                  <RemoveIcon />
                </IconButton>
                <Typography variant="body2" sx={{ minWidth: 30, textAlign: 'center' }}>
                  {item.quantityToRefund}
                </Typography>
                <IconButton 
                  size="small" 
                  onClick={() => updateRefundQuantity(item.saleItemId || item.id, 1)}
                  disabled={item.quantityToRefund >= item.maxRefundable}
                >
                  <AddIcon />
                </IconButton>
              </Box>
              <Typography variant="body2" sx={{ ml: 1, minWidth: 60, textAlign: 'right' }}>
                {formatCurrency((item.price || item.unitPrice) * (item.quantityToRefund || 0))}
              </Typography>
            </Box>
          ))}

          {refundItems.length === 0 && (
            <Typography variant="body2" color="text.secondary" sx={{ py: 2 }}>
              No items available for refund. All items have been fully refunded or this sale cannot be refunded.
            </Typography>
          )}

          <Box sx={{ borderTop: 1, borderColor: 'divider', pt: 2, mt: 2, display: 'flex', justifyContent: 'space-between' }}>
            <Typography variant="h6">Total Refund:</Typography>
            <Typography variant="h6">{formatCurrency(calculateRefundTotal())}</Typography>
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setRefundDialogOpen(false)}>Cancel</Button>
          <Button 
            onClick={handleRefundSubmit} 
            variant="contained" 
            disabled={refundMutation.isPending || calculateRefundTotal() === 0 || !refundReason.trim()}
          >
            {refundMutation.isPending ? 'Processing...' : 'Confirm Refund'}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default ReceiptPreview;
