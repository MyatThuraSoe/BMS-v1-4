import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Alert, Box,TextField, Typography, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Paper, Button, IconButton, TablePagination, Dialog, DialogTitle, DialogContent, DialogActions, Chip, Menu, MenuItem,
} from '@mui/material';
import { Add as AddIcon, Delete as DeleteIcon, Visibility as ViewIcon, MoreVert as MoreVertIcon } from '@mui/icons-material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { purchaseService } from '../api/services';
import { formatDateTime, formatCurrency } from '../utils/helpers';
import { useAuth } from '../context/AuthContext';

import { notifyError } from '../utils/notify';

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

  const { data: purchasesData, isLoading } = useQuery({
    queryKey: ['purchases', page, size],
    queryFn: () => purchaseService.getAll(page, size),
  });

  const deleteMutation = useMutation({
    mutationFn: (id) => purchaseService.delete(id),
    onSuccess: () => {
      queryClient.invalidateQueries(['purchases']);
      setDeleteDialogOpen(false);
      notifySuccess('Purchase deleted');
    },
    onError: (err) => {
      setDeleteDialogOpen(false);
      notifyError(err.friendlyMessage || 'Failed to delete purchase');
    },
  });
  const paymentStatusMutation = useMutation({
    mutationFn: ({ id, paymentStatus }) => purchaseService.updatePaymentStatus(id, paymentStatus),
    onSuccess: () => queryClient.invalidateQueries(['purchases']),
    onError: (err) => notifyError(err.friendlyMessage || 'Failed to update payment status'),
  });

  const updatePaymentStatusMutation = useMutation({
    mutationFn: ({ id, paymentStatus }) => purchaseService.updatePaymentStatus(id, paymentStatus),
    onSuccess: () => {
      queryClient.invalidateQueries(['purchases']);
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
  const totalElements = purchasesData?.data?.totalElements || 0;

  return (
    <Box>
      <Box sx={{ display: 'flex', justifyContent: 'right', alignItems: 'center', mb: 3 }}>
        {isManager() && (
          <Button variant="contained" startIcon={<AddIcon />} onClick={() => navigate('/purchases/new')}>
            New Purchase
          </Button>
        )}
      </Box>

      <TableContainer component={Paper}>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>Purchase #</TableCell>
              <TableCell>Supplier</TableCell>
              <TableCell align="right">Total</TableCell>
              <TableCell>Payment Status</TableCell>
              <TableCell>Status</TableCell>
              <TableCell>Date</TableCell>
              {isManager() && <TableCell align="right">Actions</TableCell>}
            </TableRow>
          </TableHead>
          <TableBody>
            {isLoading ? (
              <TableRow><TableCell colSpan={7} align="center">Loading...</TableCell></TableRow>
            ) : purchases.length === 0 ? (
              <TableRow><TableCell colSpan={7} align="center">No purchases found</TableCell></TableRow>
            ) : (
              purchases.map((p) => (
                <TableRow key={p.id}>
                  <TableCell>{p.purchaseNumber}</TableCell>
                  <TableCell>{p.supplierName || '-'}</TableCell>
                  <TableCell align="right">{formatCurrency(p.totalAmount)}</TableCell>
                  <TableCell>
                    <Chip 
                      label={p.paymentStatus || 'PENDING'} 
                      size="small" 
                      color={p.paymentStatus === 'PAID' ? 'success' : p.paymentStatus === 'PARTIAL' ? 'warning' : 'default'} 
                    />
                    {(isAdmin() || isManager()) && (
                      <IconButton size="small" onClick={(e) => handlePaymentStatusClick(e, p)}>
                        <MoreVertIcon fontSize="small" />
                      </IconButton>
                    )}
                  </TableCell>
                  <TableCell>
                    <Chip
                      label={p.paymentStatus}
                      size="small"
                      color={p.paymentStatus === 'PAID' ? 'success' : p.paymentStatus === 'PARTIAL' ? 'warning' : 'error'}
                    />
                  </TableCell>
                  <TableCell>{formatDateTime(p.purchaseDate)}</TableCell>
                  {isManager() && (
                    <TableCell align="right">
                      <TextField
                        select
                        size="small"
                        value={p.paymentStatus}
                        onChange={(e) => paymentStatusMutation.mutate({ id: p.id, paymentStatus: e.target.value })}
                        sx={{ width: 110, mr: 1 }}
                      >
                        <MenuItem value="PENDING">Pending</MenuItem>
                        <MenuItem value="PARTIAL">Partial</MenuItem>
                        <MenuItem value="PAID">Paid</MenuItem>
                      </TextField>
                      <IconButton size="small" onClick={() => navigate(`/purchases/${p.id}`)}><ViewIcon /></IconButton>
                      <IconButton size="small" color="error" onClick={() => { setSelectedPurchase(p); setDeleteDialogOpen(true); }}><DeleteIcon /></IconButton>
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
        <DialogTitle>Confirm Delete</DialogTitle>
        <DialogContent>
          Are you sure you want to delete purchase "{selectedPurchase?.purchaseNumber}"?
          <Alert severity="warning" sx={{ mt: 2 }}>
            This won't reverse the stock that was added by this purchase — inventory will stay as-is.
          </Alert>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteDialogOpen(false)}>Cancel</Button>
          <Button onClick={handleDelete} color="error" variant="contained">Delete</Button>
        </DialogActions>
      </Dialog>

      <Menu anchorEl={paymentStatusMenuAnchor} open={Boolean(paymentStatusMenuAnchor)} onClose={() => setPaymentStatusMenuAnchor(null)}>
        <MenuItem onClick={() => handlePaymentStatusChange('PENDING')}>Pending</MenuItem>
        <MenuItem onClick={() => handlePaymentStatusChange('PARTIAL')}>Partial</MenuItem>
        <MenuItem onClick={() => handlePaymentStatusChange('PAID')}>Paid</MenuItem>
      </Menu>
    </Box>
  );
};

export default Purchases;
