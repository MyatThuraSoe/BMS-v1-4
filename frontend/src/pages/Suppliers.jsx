import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Box, Typography, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Paper, Button, IconButton, TextField, TablePagination, Dialog, DialogTitle, DialogContent, DialogActions, Alert,
} from '@mui/material';
import { Add as AddIcon, Edit as EditIcon, Delete as DeleteIcon, Search as SearchIcon } from '@mui/icons-material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { supplierService } from '../api/services';
import { formatDateTime } from '../utils/helpers';
import { useAuth } from '../context/AuthContext';

const Suppliers = () => {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const [search, setSearch] = useState('');
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);
  const [selectedSupplier, setSelectedSupplier] = useState(null);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { isManager } = useAuth();

  const { data: suppliersData, isLoading } = useQuery({
    queryKey: ['suppliers', page, size, search],
    queryFn: () => supplierService.getAll(page, size),
  });

  const deleteMutation = useMutation({
    mutationFn: (id) => supplierService.delete(id),
    onSuccess: () => {
      queryClient.invalidateQueries(['suppliers']);
      setDeleteDialogOpen(false);
    },
    onError: () => {
      setDeleteDialogOpen(false);
    },
  });

  const handleDelete = () => {
    if (selectedSupplier) deleteMutation.mutate(selectedSupplier.id);
  };

  const suppliers = suppliersData?.data?.content || [];
  const totalElements = suppliersData?.data?.totalElements || 0;

  return (
    <Box>
      <Box sx={{ display: 'flex', justifyContent: 'right', alignItems: 'center', mb: 3 }}>
        
        {isManager() && (
          <Button variant="contained" startIcon={<AddIcon />} onClick={() => navigate('/suppliers/new')}>
            Add Supplier
          </Button>
        )}
      </Box>

      <Paper sx={{ mb: 2 }}>
        <TextField fullWidth placeholder="Search suppliers..." value={search} onChange={(e) => setSearch(e.target.value)} InputProps={{ startAdornment: <SearchIcon sx={{ mr: 1, color: 'text.secondary' }} /> }} size="small" />
      </Paper>

      <TableContainer component={Paper}>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>Name</TableCell>
              <TableCell>Contact</TableCell>
              <TableCell>Phone</TableCell>
              <TableCell>Email</TableCell>
              <TableCell>Created</TableCell>
              {isManager() && <TableCell align="right">Actions</TableCell>}
            </TableRow>
          </TableHead>
          <TableBody>
            {isLoading ? (
              <TableRow><TableCell colSpan={6} align="center">Loading...</TableCell></TableRow>
            ) : suppliers.length === 0 ? (
              <TableRow><TableCell colSpan={6} align="center">No suppliers found</TableCell></TableRow>
            ) : (
              suppliers.map((s) => (
                <TableRow key={s.id}>
                  <TableCell>{s.name}</TableCell>
                  <TableCell>{s.contactPerson || '-'}</TableCell>
                  <TableCell>{s.phone || '-'}</TableCell>
                  <TableCell>{s.email || '-'}</TableCell>
                  <TableCell>{formatDateTime(s.createdAt)}</TableCell>
                  {isManager() && (
                    <TableCell align="right">
                      <IconButton size="small" onClick={() => navigate(`/suppliers/${s.id}`)}><EditIcon /></IconButton>
                      <IconButton size="small" color="error" onClick={() => { setSelectedSupplier(s); setDeleteDialogOpen(true); }}><DeleteIcon /></IconButton>
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
        <DialogContent>Are you sure you want to delete "{selectedSupplier?.name}"?</DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteDialogOpen(false)}>Cancel</Button>
          <Button onClick={handleDelete} color="error" variant="contained">Delete</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default Suppliers;
