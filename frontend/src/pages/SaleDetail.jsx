import { useParams } from 'react-router-dom';
import { Box, Typography, Paper, Table, TableHead, TableBody, TableCell, TableRow, Button, CircularProgress } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { saleService } from '../api/services';
import { formatDateTime, formatCurrency } from '../utils/helpers';
import { Print as PrintIcon } from '@mui/icons-material';

const SaleDetail = () => {
  const { id } = useParams();

  const { data, isLoading } = useQuery({
    queryKey: ['sale', id],
    queryFn: () => saleService.getById(id),
  });

  if (isLoading) return <CircularProgress />;
  if (!data?.data) return <Typography>Sale not found</Typography>;


  const sale = data.data;
  console.log(sale);
  const handlePrint = () => {
    window.open(`/receipt/${sale.invoiceNumber}`, '_blank');
  };

  return (
    <Box>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 3 }}>
        <Typography variant="h4">Sale Details</Typography>
        <Button variant="outlined" startIcon={<PrintIcon />} onClick={handlePrint}>Print Receipt</Button>
      </Box>

      <Paper sx={{ p: 3, mb: 3 }}>
        <Grid container spacing={2}>
          <Grid item xs={12} md={6}><Typography><strong>Invoice #:</strong> {sale.invoiceNumber}</Typography></Grid>
          <Grid item xs={12} md={6}><Typography><strong>Date:</strong> {formatDateTime(sale.saleDate)}</Typography></Grid>
          <Grid item xs={12} md={6}><Typography><strong>Customer:</strong> {sale.customerName || 'Walk-in'}</Typography></Grid>
          <Grid item xs={12} md={6}><Typography><strong>Cashier:</strong> {sale.cashierName}</Typography></Grid>
          <Grid item xs={12} md={6}><Typography><strong>Status:</strong> {sale.status}</Typography></Grid>
          <Grid item xs={12} md={6}><Typography><strong>Payment Method:</strong> {sale.paymentMethod}</Typography></Grid>
        </Grid>
      </Paper>

      <Paper sx={{ p: 3 }}>
        <Typography variant="h6" gutterBottom>Items</Typography>
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Product</TableCell>
              <TableCell align="right">Qty</TableCell>
              <TableCell align="right">Price</TableCell>
              <TableCell align="right">Subtotal</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {sale.items?.map((item, idx) => (
              <TableRow key={idx}>
                <TableCell>{item.productName}</TableCell>
                <TableCell align="right">{item.quantity}</TableCell>
                <TableCell align="right">{formatCurrency(item.unitPrice)}</TableCell>
                <TableCell align="right">{formatCurrency(item.unitPrice * item.quantity)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        <Box sx={{ mt: 2, display: 'flex', justifyContent: 'flex-end', flexDirection: 'column', alignItems: 'flex-end' }}>
          <Typography>
            <strong>Subtotal:</strong> {formatCurrency(sale.subtotal)}
          </Typography>

          <Typography>
            <strong>Tax:</strong> {formatCurrency(sale.taxAmount)}
          </Typography>

          <Typography>
            <strong>Total:</strong> {formatCurrency(sale.totalAmount)}
          </Typography>

          <Typography>
            <strong>Paid:</strong> {formatCurrency(sale.amountPaid)}
          </Typography>

          <Typography>
            <strong>Change:</strong> {formatCurrency(sale.changeGiven)}
          </Typography>
        </Box>
      </Paper>
    </Box>
  );
};

// Simple Grid substitute
const Grid = ({ children, container, item, xs, md, spacing, sx }) => {
  if (container) {
    return <Box sx={{ display: 'flex', flexWrap: 'wrap', mx: -1, ...sx }}>{children}</Box>;
  }
  return <Box sx={{ px: 1, width: xs ? `${(xs / 12) * 100}%` : '100%', ...sx }}>{children}</Box>;
};

export default SaleDetail;
