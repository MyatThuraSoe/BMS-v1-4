import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Box, Typography, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Paper, Button, IconButton, TextField, TablePagination, Dialog, DialogTitle, DialogContent, DialogActions, Alert, Chip,
} from '@mui/material';
import { Delete as DeleteIcon, Visibility as ViewIcon, Print as PrintIcon } from '@mui/icons-material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { saleService } from '../api/services';
import { formatDateTime, formatCurrency } from '../utils/helpers';
import { useAuth } from '../context/AuthContext';

const Sales = () => {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);
  const [voidDialogOpen, setVoidDialogOpen] = useState(false);
  const [selectedSale, setSelectedSale] = useState(null);
  const [voidReason, setVoidReason] = useState('');
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { isManager } = useAuth();

  const { data: salesData, isLoading } = useQuery({
    queryKey: ['sales', page, size],
    queryFn: () => saleService.getAll(page, size),
  });

  const deleteMutation = useMutation({
    mutationFn: (id) => saleService.delete(id),
    onSuccess: () => {
      queryClient.invalidateQueries(['sales']);
      setDeleteDialogOpen(false);
    },
  });

  const voidMutation = useMutation({
    mutationFn: ({ id, reason }) => saleService.voidSale(id, reason),
    onSuccess: () => {
      queryClient.invalidateQueries(['sales']);
      setVoidDialogOpen(false);
      setVoidReason('');
    },
  });

  const handleDelete = () => {
    if (selectedSale) deleteMutation.mutate(selectedSale.id);
  };

  const handleVoid = () => {
    if (selectedSale && voidReason) voidMutation.mutate({ id: selectedSale.id, reason: voidReason });
  };

  const sales = salesData?.data?.content || [];
  const totalElements = salesData?.data?.totalElements || 0;

  return (
    <Box>
      

      <TableContainer component={Paper}>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>Invoice #</TableCell>
              <TableCell>Customer</TableCell>
              <TableCell align="right">Total</TableCell>
              <TableCell align="right">Paid</TableCell>
              <TableCell>Status</TableCell>
              <TableCell>Date</TableCell>
              <TableCell align="right">Actions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {isLoading ? (
              <TableRow><TableCell colSpan={7} align="center">Loading...</TableCell></TableRow>
            ) : sales.length === 0 ? (
              <TableRow><TableCell colSpan={7} align="center">No sales found</TableCell></TableRow>
            ) : (
              sales.map((s) => (
                <TableRow key={s.id}>
                  <TableCell>{s.invoiceNumber}</TableCell>
                  <TableCell>{s.customerName || 'Walk-in'}</TableCell>
                  <TableCell align="right">{formatCurrency(s.totalAmount)}</TableCell>
                  <TableCell align="right">{formatCurrency(s.amountPaid)}</TableCell>
                  <TableCell><Chip label={s.status} size="small" color={s.status === 'COMPLETED' ? 'success' : s.status === 'VOIDED' ? 'error' : 'warning'} /></TableCell>
                  <TableCell>{formatDateTime(s.saleDate)}</TableCell>
                  <TableCell align="right">
                    <IconButton size="small" onClick={() => navigate(`/receipt/${s.invoiceNumber}`)}><PrintIcon /></IconButton>
                    <IconButton size="small" onClick={() => navigate(`/sales/${s.id}`)}><ViewIcon /></IconButton>
                    {isManager() && s.status !== 'VOIDED' && (
                      <IconButton size="small" color="warning" onClick={() => { setSelectedSale(s); setVoidDialogOpen(true); }}>Void</IconButton>
                    )}
                    {isManager() && (
                      <IconButton size="small" color="error" onClick={() => { setSelectedSale(s); setDeleteDialogOpen(true); }}><DeleteIcon /></IconButton>
                    )}
                  </TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
        <TablePagination component="div" count={totalElements} page={page} rowsPerPage={size} onPageChange={(e, newPage) => setPage(newPage)} onRowsPerPageChange={(e) => { setSize(parseInt(e.target.value)); setPage(0); }} rowsPerPageOptions={[5, 10, 25]} />
      </TableContainer>

      <Dialog open={deleteDialogOpen} onClose={() => setDeleteDialogOpen(false)}>
        <DialogTitle>Confirm Delete</DialogTitle>
        <DialogContent>Are you sure you want to delete sale "{selectedSale?.invoiceNumber}"?</DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteDialogOpen(false)}>Cancel</Button>
          <Button onClick={handleDelete} color="error" variant="contained">Delete</Button>
        </DialogActions>
      </Dialog>

      <Dialog open={voidDialogOpen} onClose={() => setVoidDialogOpen(false)}>
        <DialogTitle>Void Sale</DialogTitle>
        <DialogContent>
          <Typography sx={{ mb: 2 }}>Sale: {selectedSale?.invoiceNumber}</Typography>
          <TextField fullWidth label="Reason" multiline rows={3} value={voidReason} onChange={(e) => setVoidReason(e.target.value)} required />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setVoidDialogOpen(false)}>Cancel</Button>
          <Button onClick={handleVoid} color="warning" variant="contained" disabled={!voidReason}>Void</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default Sales;
