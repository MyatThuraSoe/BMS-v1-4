import { useParams, useNavigate } from 'react-router-dom';
import { Box, Typography, Paper, Table, TableHead, TableBody, TableCell, TableContainer, TableRow, Button, CircularProgress, Grid, Chip, Divider, Stack } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { saleService } from '../api/services';
import { formatDateTime, formatCurrency } from '../utils/helpers';
import { Print as PrintIcon, ArrowBack as BackIcon, Person as PersonIcon, AccessTime as TimeIcon } from '@mui/icons-material';

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

  const { data, isLoading } = useQuery({
    queryKey: ['sale', id],
    queryFn: () => saleService.getById(id),
  });

  if (isLoading) return <Box sx={{ display: 'flex', justifyContent: 'center', p: 6 }}><CircularProgress /></Box>;
  if (!data?.data) return <Typography>Sale not found</Typography>;

  const sale = data.data;
  const netTotal = sale.totalAmount - (sale.totalRefunded || 0);
  const isFullyRefunded = sale.totalRefunded > 0 && Math.abs(netTotal) < 0.01;

  const statusChip = sale.isVoided
    ? <Chip label="Voided" color="error" />
    : isFullyRefunded
    ? <Chip label="Fully Refunded" color="warning" />
    : sale.totalRefunded > 0
    ? <Chip label="Partially Refunded" color="warning" variant="outlined" />
    : <Chip label="Completed" color="success" />;

  const handlePrint = () => window.open(`/receipt/${sale.invoiceNumber}`, '_blank');

  return (
    <Box>
      <Button startIcon={<BackIcon />} onClick={() => navigate('/sales')} sx={{ mb: 2 }}>Back to Sales</Button>

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
              <Typography variant="body2" color="text.secondary">{sale.cashierName || 'Unknown cashier'}</Typography>
            </Stack>
          </Stack>
        </Box>
        <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap">
          {statusChip}
          <Button variant="outlined" startIcon={<PrintIcon />} onClick={handlePrint}>Print Receipt</Button>
        </Stack>
      </Stack>

      {sale.isVoided && sale.voidedReason && (
        <Paper sx={{ p: 2, mb: 2, bgcolor: 'error.50', border: '1px solid', borderColor: 'error.light' }}>
          <Typography variant="body2" color="error.dark"><strong>Void reason:</strong> {sale.voidedReason}</Typography>
        </Paper>
      )}

      <Paper elevation={0} sx={{ p: 3, mb: 3, border: '1px solid', borderColor: 'divider', borderRadius: 3 }}>
        <Grid container spacing={3}>
          <Grid item xs={6} sm={3}><StatBlock label="Subtotal" value={formatCurrency(sale.subtotal)} /></Grid>
          <Grid item xs={6} sm={3}><StatBlock label="Tax" value={formatCurrency(sale.taxAmount)} /></Grid>
          <Grid item xs={6} sm={3}><StatBlock label="Discount" value={formatCurrency(sale.discountAmount)} color={sale.discountAmount > 0 ? 'success.main' : undefined} /></Grid>
          <Grid item xs={6} sm={3}><StatBlock label="Total" value={formatCurrency(sale.totalAmount)} /></Grid>
          <Grid item xs={6} sm={3}><StatBlock label="Paid" value={formatCurrency(sale.amountPaid)} /></Grid>
          <Grid item xs={6} sm={3}><StatBlock label="Change" value={formatCurrency(sale.changeGiven)} /></Grid>
          <Grid item xs={6} sm={3}><StatBlock label="Customer" value={sale.customerName || 'Walk-in'} /></Grid>
          <Grid item xs={6} sm={3}>
            <StatBlock label="Net After Refunds" value={formatCurrency(netTotal)} color={sale.totalRefunded > 0 ? 'warning.main' : undefined} />
          </Grid>
        </Grid>
      </Paper>

      <Paper elevation={0} sx={{ p: 3, mb: 3, border: '1px solid', borderColor: 'divider', borderRadius: 3 }}>
        <Typography variant="h6" gutterBottom>Items</Typography>
        <TableContainer sx={{ overflowX: 'auto' }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Product</TableCell>
                <TableCell align="right">Qty</TableCell>
                <TableCell align="right">Refunded</TableCell>
                <TableCell align="right">Price</TableCell>
                <TableCell align="right">Subtotal</TableCell>
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
          <Typography variant="h6" gutterBottom>Refund History</Typography>
          <TableContainer sx={{ overflowX: 'auto' }}>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Date</TableCell>
                  <TableCell>Reason</TableCell>
                  <TableCell>Processed By</TableCell>
                  <TableCell align="right">Amount</TableCell>
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
            <Typography fontWeight={600}>Total Refunded: {formatCurrency(sale.totalRefunded)}</Typography>
          </Box>
        </Paper>
      )}
    </Box>
  );
};

export default SaleDetail;