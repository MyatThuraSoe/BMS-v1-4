import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  Alert, Box, Typography, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Paper, Button, IconButton, TablePagination, Dialog, DialogTitle, DialogContent, DialogActions, MenuItem, Select, Chip, TextField, Divider, Stack, InputLabel, FormControl,
} from '@mui/material';
import { Add as AddIcon, Delete as DeleteIcon, Payments as PaymentsIcon } from '@mui/icons-material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { purchaseService } from '../api/services';
import { formatDateTime, formatCurrency } from '../utils/helpers';
import { useAuth } from '../context/AuthContext';
import { notifyError, notifySuccess } from '../utils/notify';
import useListFilters from '../hooks/useListFilters';

const PAYMENT_METHODS = ['CASH', 'BANK_TRANSFER', 'CARD', 'MOBILE_WALLET', 'OTHER'];

const Purchases = () => {
  const { filters, update, detailUrl } = useListFilters({
    page: { init: 0, parse: Number, serialize: (v) => String(v) },
    size: { init: 10, parse: Number },
  });
  const { page, size } = filters;
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);
  const [selectedPurchase, setSelectedPurchase] = useState(null);
  const [paymentDialogOpen, setPaymentDialogOpen] = useState(false);
  const [paymentPurchase, setPaymentPurchase] = useState(null);
  const [paymentForm, setPaymentForm] = useState({ amount: '', paymentMethod: 'CASH', notes: '', paymentDate: '' });
  
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { isManager } = useAuth();
  const { t } = useTranslation('purchases');

  const { data: purchasesData, isLoading } = useQuery({
    queryKey: ['purchases', page, size],
    queryFn: () => purchaseService.getAll(page, size),
  });

  const { data: paymentsData, isLoading: paymentsLoading } = useQuery({
    queryKey: ['purchase-payments', paymentPurchase?.id],
    queryFn: () => purchaseService.getPayments(paymentPurchase.id),
    enabled: paymentDialogOpen && !!paymentPurchase,
  });
  const payments = paymentsData?.data || [];

  const recordPaymentMutation = useMutation({
    mutationFn: ({ id, data }) => purchaseService.recordPayment(id, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['purchases'] });
      queryClient.invalidateQueries({ queryKey: ['purchase-payments', paymentPurchase?.id] });
      notifySuccess(t('payment_recorded'));
      setPaymentDialogOpen(false);
      setPaymentPurchase(null);
    },
    onError: (err) => notifyError(err.friendlyMessage || t('record_payment_failed')),
  });

  const deleteMutation = useMutation({
    mutationFn: (id) => purchaseService.delete(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['purchases'] });
      setDeleteDialogOpen(false);
      setSelectedPurchase(null);
      notifySuccess(t('purchase_deleted'));
    },
    onError: (err) => {
      setDeleteDialogOpen(false);
      notifyError(err.friendlyMessage || t('delete_purchase_failed'));
    },
  });

  const handleDelete = () => {
    if (selectedPurchase) deleteMutation.mutate(selectedPurchase.id);
  };

  const openPaymentDialog = (purchase) => {
    setPaymentPurchase(purchase);
    const outstanding = Number(purchase.outstandingAmount || 0);
    setPaymentForm({
      amount: outstanding > 0 ? String(outstanding) : '',
      paymentMethod: 'CASH',
      notes: '',
      paymentDate: '',
    });
    setPaymentDialogOpen(true);
  };

  const handleRecordPayment = () => {
    if (!paymentPurchase) return;
    const amount = Number(paymentForm.amount);
    if (!amount || amount <= 0) {
      notifyError(t('invalid_amount'));
      return;
    }
    recordPaymentMutation.mutate({
      id: paymentPurchase.id,
      data: {
        amount,
        paymentMethod: paymentForm.paymentMethod,
        notes: paymentForm.notes?.trim() || null,
        paymentDate: paymentForm.paymentDate || null,
      },
    });
  };

  const purchases = purchasesData?.data?.content || [];
  const totalElements = purchasesData?.data?.page?.totalElements || 0;

  // Helper to get color based on derived status
  const getStatusColor = (status) => {
    switch (status) {
      case 'PAID': return 'success';
      case 'PARTIAL': return 'warning';
      default: return 'default';
    }
  };

  const paymentOutstanding = Number(paymentPurchase?.outstandingAmount || 0);

  return (
    <Box>
      <Box sx={{ display: 'flex', justifyContent: 'flex-end', alignItems: 'center', mb: 3 }}>
        {isManager() && (
          <Button variant="contained" startIcon={<AddIcon />} onClick={() => navigate(detailUrl('/purchases/new'))}>
            {t('new_purchase')}
          </Button>
        )}
      </Box>

      <TableContainer component={Paper} elevation={0} sx={{ border: '1px solid', borderColor: 'divider' }}>
        <Table>
          <TableHead>
            <TableRow sx={{ bgcolor: 'action.hover' }}>
              <TableCell>{t('purchase_number')}</TableCell>
              <TableCell>{t('supplier')}</TableCell>
              <TableCell align="right">{t('total')}</TableCell>
              <TableCell align="right">{t('paid_amount')}</TableCell>
              <TableCell align="right">{t('outstanding')}</TableCell>
              <TableCell>{t('payment_status')}</TableCell>
              <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{t('date')}</TableCell>
              {isManager() && <TableCell align="center" width={110}>{t('actions')}</TableCell>}
            </TableRow>
          </TableHead>
          <TableBody>
            {isLoading ? (
              <TableRow><TableCell colSpan={8} align="center" sx={{ py: 4 }}>{t('loading')}</TableCell></TableRow>
            ) : purchases.length === 0 ? (
              <TableRow><TableCell colSpan={8} align="center" sx={{ py: 4 }}>{t('no_purchases_found')}</TableCell></TableRow>
            ) : (
              purchases.map((p) => (
                <TableRow
                  key={p.id}
                  hover
                  onClick={() => navigate(detailUrl(`/purchases/${p.id}`))}
                  sx={{ cursor: 'pointer', '&:last-child td': { borderBottom: 0 } }}
                >
                  <TableCell sx={{ fontWeight: 500 }}>{p.purchaseNumber}</TableCell>
                  <TableCell>{p.supplierName || '-'}</TableCell>
                  <TableCell align="right" sx={{ fontFamily: '"IBM Plex Mono", monospace' }}>
                    {formatCurrency(p.totalAmount)}
                  </TableCell>
                  <TableCell align="right" sx={{ fontFamily: '"IBM Plex Mono", monospace', color: 'success.main' }}>
                    {formatCurrency(p.paidAmount)}
                  </TableCell>
                  <TableCell align="right" sx={{ fontFamily: '"IBM Plex Mono", monospace', color: Number(p.outstandingAmount) > 0 ? 'warning.main' : 'text.secondary' }}>
                    {formatCurrency(p.outstandingAmount)}
                  </TableCell>
                  
                  {/* Derived payment status — read-only, driven by recorded payments */}
                  <TableCell>
                    <Chip
                      size="small"
                      variant="outlined"
                      color={getStatusColor(p.paymentStatus)}
                      label={t((p.paymentStatus || 'PENDING').toLowerCase())}
                      sx={{ fontWeight: 600 }}
                    />
                  </TableCell>

                  <TableCell sx={{ display: { xs: 'none', md: 'table-cell' }, color: 'text.secondary' }}>
                    {formatDateTime(p.purchaseDate)}
                  </TableCell>

                  {/* ✅ Clean Actions Column */}
                  {isManager() && (
                    <TableCell align="center">
                      <IconButton
                        size="small"
                        color="primary"
                        title={t('record_payment')}
                        onClick={(e) => { e.stopPropagation(); openPaymentDialog(p); }}
                      >
                        <PaymentsIcon fontSize="small" />
                      </IconButton>
                      <IconButton 
                        size="small" 
                        color="error" 
                        onClick={(e) => { 
                          e.stopPropagation(); 
                          setSelectedPurchase(p); 
                          setDeleteDialogOpen(true); 
                        }}
                      >
                        <DeleteIcon fontSize="small" />
                      </IconButton>
                    </TableCell>
                  )}
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
        <TablePagination 
          component="div" 
          count={totalElements} 
          page={page} 
          rowsPerPage={size} 
          onPageChange={(e, newPage) => update({ page: newPage })} 
          onRowsPerPageChange={(e) => { update({ size: parseInt(e.target.value), page: 0 }); }} 
          rowsPerPageOptions={[10, 25, 50]} 
        />
      </TableContainer>

      {/* Delete Confirmation Dialog */}
      <Dialog open={deleteDialogOpen} onClose={() => setDeleteDialogOpen(false)}>
        <DialogTitle>{t('confirm_delete')}</DialogTitle>
        <DialogContent>
          <Typography>
            {t('delete_purchase_confirm', { number: selectedPurchase?.purchaseNumber })}
          </Typography>
          <Alert severity="warning" sx={{ mt: 2 }}>
            {t('stock_not_reversed_warning')}
          </Alert>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteDialogOpen(false)}>{t('cancel')}</Button>
          <Button onClick={handleDelete} color="error" variant="contained">{t('delete')}</Button>
        </DialogActions>
      </Dialog>

      {/* Record Payment Dialog */}
      <Dialog open={paymentDialogOpen} onClose={() => setPaymentDialogOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>
          {t('record_payment')}
          {paymentPurchase?.purchaseNumber ? ` — ${paymentPurchase.purchaseNumber}` : ''}
        </DialogTitle>
        <DialogContent dividers>
          <Stack direction="row" spacing={3} sx={{ mb: 2 }}>
            <Box>
              <Typography variant="caption" color="text.secondary">{t('payable')}</Typography>
              <Typography sx={{ fontFamily: '"IBM Plex Mono", monospace' }}>{formatCurrency(paymentPurchase?.payableAmount)}</Typography>
            </Box>
            <Box>
              <Typography variant="caption" color="text.secondary">{t('paid_amount')}</Typography>
              <Typography sx={{ fontFamily: '"IBM Plex Mono", monospace', color: 'success.main' }}>{formatCurrency(paymentPurchase?.paidAmount)}</Typography>
            </Box>
            <Box>
              <Typography variant="caption" color="text.secondary">{t('outstanding')}</Typography>
              <Typography sx={{ fontFamily: '"IBM Plex Mono", monospace', color: paymentOutstanding > 0 ? 'warning.main' : 'text.secondary' }}>{formatCurrency(paymentPurchase?.outstandingAmount)}</Typography>
            </Box>
          </Stack>

          {paymentOutstanding <= 0 ? (
            <Alert severity="success" sx={{ mb: 2 }}>{t('fully_paid')}</Alert>
          ) : (
            <Stack spacing={2} sx={{ mb: 1 }}>
              <TextField
                label={t('amount')}
                type="number"
                size="small"
                value={paymentForm.amount}
                onChange={(e) => setPaymentForm((f) => ({ ...f, amount: e.target.value }))}
                inputProps={{ min: 0, step: '0.01' }}
                fullWidth
              />
              <FormControl size="small" fullWidth>
                <InputLabel id="purchase-payment-method-label">{t('payment_method')}</InputLabel>
                <Select
                  labelId="purchase-payment-method-label"
                  label={t('payment_method')}
                  value={paymentForm.paymentMethod}
                  onChange={(e) => setPaymentForm((f) => ({ ...f, paymentMethod: e.target.value }))}
                >
                  {PAYMENT_METHODS.map((m) => (
                    <MenuItem key={m} value={m}>{t(`method_${m.toLowerCase()}`)}</MenuItem>
                  ))}
                </Select>
              </FormControl>
              <TextField
                label={t('payment_date')}
                type="date"
                size="small"
                value={paymentForm.paymentDate}
                onChange={(e) => setPaymentForm((f) => ({ ...f, paymentDate: e.target.value }))}
                InputLabelProps={{ shrink: true }}
                helperText={t('payment_date_hint')}
                fullWidth
              />
              <TextField
                label={t('payment_notes')}
                size="small"
                multiline
                minRows={2}
                value={paymentForm.notes}
                onChange={(e) => setPaymentForm((f) => ({ ...f, notes: e.target.value }))}
                fullWidth
              />
            </Stack>
          )}

          <Divider sx={{ my: 2 }} />
          <Typography variant="subtitle2" sx={{ mb: 1 }}>{t('payment_history')}</Typography>
          {paymentsLoading ? (
            <Typography variant="body2" color="text.secondary">{t('loading')}</Typography>
          ) : payments.length === 0 ? (
            <Typography variant="body2" color="text.secondary">{t('no_payments')}</Typography>
          ) : (
            <Stack spacing={1}>
              {payments.map((pay) => (
                <Box key={pay.id} sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 2 }}>
                  <Box>
                    <Typography variant="body2">{formatDateTime(pay.paymentDate)}</Typography>
                    <Typography variant="caption" color="text.secondary">
                      {t(`method_${(pay.paymentMethod || 'CASH').toLowerCase()}`)}
                      {pay.recordedByName ? ` · ${pay.recordedByName}` : ''}
                      {pay.notes ? ` · ${pay.notes}` : ''}
                    </Typography>
                  </Box>
                  <Typography variant="body2" sx={{ fontFamily: '"IBM Plex Mono", monospace', whiteSpace: 'nowrap' }}>
                    {formatCurrency(pay.amount)}
                  </Typography>
                </Box>
              ))}
            </Stack>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setPaymentDialogOpen(false)}>{t('cancel')}</Button>
          <Button
            onClick={handleRecordPayment}
            variant="contained"
            disabled={paymentOutstanding <= 0 || recordPaymentMutation.isPending}
          >
            {t('record_payment')}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default Purchases;