import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  Alert, Box,TextField, Typography, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Paper, Button, IconButton, TablePagination, Dialog, DialogTitle, DialogContent, DialogActions, Chip, Menu, MenuItem,
} from '@mui/material';
import { Add as AddIcon, Delete as DeleteIcon, MoreVert as MoreVertIcon } from '@mui/icons-material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { purchaseService } from '../api/services';
import { formatDateTime, formatCurrency } from '../utils/helpers';
import { useAuth } from '../context/AuthContext';

import { notifyError, notifySuccess } from '../utils/notify';

const Purchases = () => {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);
  const [selectedPurchase, setSelectedPurchase] = useState(null);
  const [paymentStatusMenuAnchor, setPaymentStatusMenuAnchor] = useState(null);
  const [selectedPurchaseForStatus, setSelectedPurchaseForStatus] = useState(null);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { isManager, isAdmin } = useAuth();
  const { t } = useTranslation('purchases');

  const { data: purchasesData, isLoading } = useQuery({
    queryKey: ['purchases', page, size],
    queryFn: () => purchaseService.getAll(page, size),
  });

  const deleteMutation = useMutation({
    mutationFn: (id) => purchaseService.delete(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['purchases'] });
      setDeleteDialogOpen(false);
      notifySuccess(t('purchase_deleted'));
    },
    onError: (err) => {
      setDeleteDialogOpen(false);
      notifyError(err.friendlyMessage || t('delete_purchase_failed'));
    },
  });
  const paymentStatusMutation = useMutation({
    mutationFn: ({ id, paymentStatus }) => purchaseService.updatePaymentStatus(id, paymentStatus),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['purchases'] }),
    onError: (err) => notifyError(err.friendlyMessage || t('update_payment_status_failed')),
  });

  const updatePaymentStatusMutation = useMutation({
    mutationFn: ({ id, paymentStatus }) => purchaseService.updatePaymentStatus(id, paymentStatus),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['purchases'] });
      setPaymentStatusMenuAnchor(null);
    },
  });

  const handleDelete = () => {
    if (selectedPurchase) deleteMutation.mutate(selectedPurchase.id);
  };

  const handlePaymentStatusClick = (event, purchase) => {
    setSelectedPurchaseForStatus(purchase);
    setPaymentStatusMenuAnchor(event.currentTarget);
  };

  const handlePaymentStatusChange = (status) => {
    if (selectedPurchaseForStatus) {
      updatePaymentStatusMutation.mutate({ id: selectedPurchaseForStatus.id, paymentStatus: status });
    }
  };

  const purchases = purchasesData?.data?.content || [];
  const totalElements = purchasesData?.data?.page?.totalElements || 0;

  return (
    <Box>
      <Box sx={{ display: 'flex', justifyContent: 'right', alignItems: 'center', mb: 3 }}>
        {isManager() && (
          <Button variant="contained" startIcon={<AddIcon />} onClick={() => navigate('/purchases/new')}>
            {t('new_purchase')}
          </Button>
        )}
      </Box>

      <TableContainer component={Paper}>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>{t('purchase_number')}</TableCell>
              <TableCell>{t('supplier')}</TableCell>
              <TableCell align="right">{t('total')}</TableCell>
              <TableCell>{t('payment_status')}</TableCell>
              <TableCell sx={{ display: { xs: 'none', sm: 'table-cell' } }}>{t('status')}</TableCell>
              <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{t('date')}</TableCell>
              {isManager() && <TableCell align="right">{t('actions')}</TableCell>}
            </TableRow>
          </TableHead>
          <TableBody>
            {isLoading ? (
              <TableRow><TableCell colSpan={7} align="center">{t('loading')}</TableCell></TableRow>
            ) : purchases.length === 0 ? (
              <TableRow><TableCell colSpan={7} align="center">{t('no_purchases_found')}</TableCell></TableRow>
            ) : (
              purchases.map((p) => (
                <TableRow
                  key={p.id}
                  hover
                  onClick={() => navigate(`/purchases/${p.id}`)}
                  sx={{ cursor: 'pointer' }}
                >
                  <TableCell>{p.purchaseNumber}</TableCell>
                  <TableCell>{p.supplierName || '-'}</TableCell>
                  <TableCell align="right">{formatCurrency(p.totalAmount)}</TableCell>
                  <TableCell>
                    <Chip 
                      label={t((p.paymentStatus || 'PENDING').toLowerCase())} 
                      size="small" 
                      color={p.paymentStatus === 'PAID' ? 'success' : p.paymentStatus === 'PARTIAL' ? 'warning' : 'default'} 
                    />
                    {(isAdmin() || isManager()) && (
                      <IconButton size="small" onClick={(e) => { e.stopPropagation(); handlePaymentStatusClick(e, p); }}>
                        <MoreVertIcon fontSize="small" />
                      </IconButton>
                    )}
                  </TableCell>
                  <TableCell sx={{ display: { xs: 'none', sm: 'table-cell' } }}>
                    <Chip
                      label={t((p.paymentStatus || 'PENDING').toLowerCase())}
                      size="small"
                      color={p.paymentStatus === 'PAID' ? 'success' : p.paymentStatus === 'PARTIAL' ? 'warning' : 'error'}
                    />
                  </TableCell>
                  <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{formatDateTime(p.purchaseDate)}</TableCell>
                  {isManager() && (
                    <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                      <TextField
                        select
                        size="small"
                        value={p.paymentStatus}
                        onClick={(e) => e.stopPropagation()}
                        onChange={(e) => { e.stopPropagation(); paymentStatusMutation.mutate({ id: p.id, paymentStatus: e.target.value }); }}
                        sx={{ width: 110, mr: 1 }}
                      >
                        <MenuItem value="PENDING">{t('pending')}</MenuItem>
                        <MenuItem value="PARTIAL">{t('partial')}</MenuItem>
                        <MenuItem value="PAID">{t('paid')}</MenuItem>
                      </TextField>
                      <IconButton size="small" color="error" onClick={(e) => { e.stopPropagation(); setSelectedPurchase(p); setDeleteDialogOpen(true); }}><DeleteIcon /></IconButton>
                    </TableCell>
                  )}
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
        <TablePagination component="div" count={totalElements} page={page} rowsPerPage={size} onPageChange={(e, newPage) => setPage(newPage)} onRowsPerPageChange={(e) => { setSize(parseInt(e.target.value)); setPage(0); }} rowsPerPageOptions={[5, 10, 25]} />
      </TableContainer>

      <Dialog open={deleteDialogOpen} onClose={() => setDeleteDialogOpen(false)}>
        <DialogTitle>{t('confirm_delete')}</DialogTitle>
        <DialogContent>
          {t('delete_purchase_confirm', { number: selectedPurchase?.purchaseNumber })}
          <Alert severity="warning" sx={{ mt: 2 }}>
            {t('stock_not_reversed_warning')}
          </Alert>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteDialogOpen(false)}>{t('cancel')}</Button>
          <Button onClick={handleDelete} color="error" variant="contained">{t('delete')}</Button>
        </DialogActions>
      </Dialog>

      <Menu anchorEl={paymentStatusMenuAnchor} open={Boolean(paymentStatusMenuAnchor)} onClose={() => setPaymentStatusMenuAnchor(null)}>
        <MenuItem onClick={() => handlePaymentStatusChange('PENDING')}>{t('pending')}</MenuItem>
        <MenuItem onClick={() => handlePaymentStatusChange('PARTIAL')}>{t('partial')}</MenuItem>
        <MenuItem onClick={() => handlePaymentStatusChange('PAID')}>{t('paid')}</MenuItem>
      </Menu>
    </Box>
  );
};

export default Purchases;
