import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  Alert, Box, Typography, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Paper, Button, IconButton, TablePagination, Dialog, DialogTitle, DialogContent, DialogActions, MenuItem, Select,
} from '@mui/material';
import { Add as AddIcon, Delete as DeleteIcon } from '@mui/icons-material';
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
  
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { isManager } = useAuth();
  const { t } = useTranslation('purchases');

  const { data: purchasesData, isLoading } = useQuery({
    queryKey: ['purchases', page, size],
    queryFn: () => purchaseService.getAll(page, size),
  });

  // ✅ Single consolidated mutation for payment status
  const updatePaymentStatusMutation = useMutation({
    mutationFn: ({ id, paymentStatus }) => purchaseService.updatePaymentStatus(id, paymentStatus),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['purchases'] });
      notifySuccess(t('payment_status_updated'));
    },
    onError: (err) => notifyError(err.friendlyMessage || t('update_payment_status_failed')),
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

  const handleStatusChange = (e, purchaseId) => {
    e.stopPropagation();
    const newStatus = e.target.value;
    updatePaymentStatusMutation.mutate({ id: purchaseId, paymentStatus: newStatus });
  };

  const purchases = purchasesData?.data?.content || [];
  const totalElements = purchasesData?.data?.page?.totalElements || 0;

  // Helper to get color based on status
  const getStatusColor = (status) => {
    switch (status) {
      case 'PAID': return 'success';
      case 'PARTIAL': return 'warning';
      default: return 'default';
    }
  };

  return (
    <Box>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 3 }}>
        <Typography variant="h5" fontWeight={600}>{t('purchases')}</Typography>
        {isManager() && (
          <Button variant="contained" startIcon={<AddIcon />} onClick={() => navigate('/purchases/new')}>
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
              <TableCell>{t('payment_status')}</TableCell>
              <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{t('date')}</TableCell>
              {isManager() && <TableCell align="center" width={80}>{t('actions')}</TableCell>}
            </TableRow>
          </TableHead>
          <TableBody>
            {isLoading ? (
              <TableRow><TableCell colSpan={6} align="center" sx={{ py: 4 }}>{t('loading')}</TableCell></TableRow>
            ) : purchases.length === 0 ? (
              <TableRow><TableCell colSpan={6} align="center" sx={{ py: 4 }}>{t('no_purchases_found')}</TableCell></TableRow>
            ) : (
              purchases.map((p) => (
                <TableRow
                  key={p.id}
                  hover
                  onClick={() => navigate(`/purchases/${p.id}`)}
                  sx={{ cursor: 'pointer', '&:last-child td': { borderBottom: 0 } }}
                >
                  <TableCell sx={{ fontWeight: 500 }}>{p.purchaseNumber}</TableCell>
                  <TableCell>{p.supplierName || '-'}</TableCell>
                  <TableCell align="right" sx={{ fontFamily: '"IBM Plex Mono", monospace' }}>
                    {formatCurrency(p.totalAmount)}
                  </TableCell>
                  
                  {/* ✅ Unified Payment Status Cell */}
                  <TableCell onClick={(e) => e.stopPropagation()}>
                    {isManager() ? (
                      <Select
                        size="small"
                        value={p.paymentStatus || 'PENDING'}
                        onChange={(e) => handleStatusChange(e, p.id)}
                        sx={{ 
                          minWidth: 120,
                          fontWeight: 600,
                          fontSize: '0.8rem',
                          borderRadius: 1,
                          '& .MuiSelect-select': { py: 0.75, pl: 1.5, pr: 4 },
                          
                          // ✅ Dynamic coloring based on status
                          color: `${getStatusColor(p.paymentStatus)}.main`,
                          bgcolor: `${getStatusColor(p.paymentStatus)}.50`,
                          '& fieldset': { 
                            borderColor: `${getStatusColor(p.paymentStatus)}.main` 
                          },
                          '&:hover fieldset': {
                            borderColor: `${getStatusColor(p.paymentStatus)}.dark`,
                          },
                          '&.Mui-focused fieldset': {
                            borderColor: `${getStatusColor(p.paymentStatus)}.main`,
                            borderWidth: 2,
                          },
                          // Color the dropdown arrow icon to match
                          '& .MuiSvgIcon-root': {
                            color: `${getStatusColor(p.paymentStatus)}.main`,
                          }
                        }}
                      >
                        <MenuItem value="PENDING" sx={{ fontWeight: 600 }}>{t('pending')}</MenuItem>
                        <MenuItem value="PARTIAL" sx={{ fontWeight: 600 }}>{t('partial')}</MenuItem>
                        <MenuItem value="PAID" sx={{ fontWeight: 600 }}>{t('paid')}</MenuItem>
                      </Select>
                    ) : (
                      // Read-only badge for non-managers (already handles green correctly)
                      <Box 
                        sx={{ 
                          display: 'inline-block', px: 1.5, py: 0.5, borderRadius: 1, fontSize: '0.8rem', fontWeight: 600,
                          bgcolor: `${getStatusColor(p.paymentStatus)}.50`,
                          color: `${getStatusColor(p.paymentStatus)}.main`,
                          border: '1px solid',
                          borderColor: `${getStatusColor(p.paymentStatus)}.main`
                        }}
                      >
                        {t((p.paymentStatus || 'PENDING').toLowerCase())}
                      </Box>
                    )}
                  </TableCell>

                  <TableCell sx={{ display: { xs: 'none', md: 'table-cell' }, color: 'text.secondary' }}>
                    {formatDateTime(p.purchaseDate)}
                  </TableCell>

                  {/* ✅ Clean Actions Column */}
                  {isManager() && (
                    <TableCell align="center">
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
          onPageChange={(e, newPage) => setPage(newPage)} 
          onRowsPerPageChange={(e) => { setSize(parseInt(e.target.value)); setPage(0); }} 
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
    </Box>
  );
};

export default Purchases;