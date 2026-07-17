import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { Box, Typography, Paper, Button, Dialog, DialogTitle, DialogContent, DialogActions, CircularProgress } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { receiptService, shopInfoService } from '../api/services';
import { formatDateTime, formatCurrency } from '../utils/helpers';
import { Print as PrintIcon, Download as DownloadIcon } from '@mui/icons-material';
import { notifySuccess, notifyError } from '../utils/notify';
import ShopLogo from '../components/ShopLogo';

const ReceiptPreview = () => {
  const { invoiceNumber } = useParams();
  const [printDialogOpen, setPrintDialogOpen] = useState(false);

  const { data, isLoading } = useQuery({
    queryKey: ['receipt', invoiceNumber],
    queryFn: () => receiptService.getByInvoiceNumber(invoiceNumber),
  });

  const { data: shopInfoData } = useQuery({
    queryKey: ['shopInfo-preview'],
    queryFn: () => shopInfoService.get(),
  });

  if (isLoading) return <CircularProgress />;
  if (!data?.data) return <Typography>Receipt not found</Typography>;

  const receipt = data.data;

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
        <ReceiptHeader
            shopName={shopInfoData?.data?.shopName}
            hasLogo={shopInfoData?.data?.hasLogo}
        />
        <Typography variant="body2">Invoice: {receipt.invoiceNumber}</Typography>

        <Typography variant="body2">{formatDateTime(receipt.saleDate)}</Typography>
        {/* <Typography variant="body2">Cashier: {receipt.cashierName}</Typography> */}
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

      <Box sx={{ mt: 2, display: 'flex', gap: 1 }}>
        <Button fullWidth variant="contained" startIcon={<PrintIcon />} onClick={handlePrint}>Print</Button>
        <Button fullWidth variant="outlined" startIcon={<DownloadIcon />} onClick={() => handleDownload('pdf')}>PDF</Button>
        <Button fullWidth variant="outlined" startIcon={<DownloadIcon />} onClick={() => handleDownload('png')}>PNG</Button>
      </Box>
      <Box sx={{ mt: 1 }}>
        <Button fullWidth variant="text" onClick={() => window.close()}>Close</Button>
      </Box>
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

