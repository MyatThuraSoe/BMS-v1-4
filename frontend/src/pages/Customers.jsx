import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Box, Typography, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Paper, Button, IconButton, TextField, TablePagination, Dialog, DialogTitle, DialogContent, DialogActions, Alert,
} from '@mui/material';
import { Add as AddIcon, Edit as EditIcon, Delete as DeleteIcon, Search as SearchIcon, Download as DownloadIcon } from '@mui/icons-material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { customerService } from '../api/services';
import { formatDateTime } from '../utils/helpers';
import { useAuth } from '../context/AuthContext';

const Customers = () => {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const [search, setSearch] = useState('');
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);
  const [selectedCustomer, setSelectedCustomer] = useState(null);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { isManager } = useAuth();

  const { data: customersData, isLoading } = useQuery({
      queryKey: ['customers', page, size, search],
      queryFn: () => {
          if (search.trim()) {
              return customerService.search(search);
          }
          return customerService.getAll(page, size);
      },
  });

  const deleteMutation = useMutation({
    mutationFn: (id) => customerService.delete(id),
    onSuccess: () => {
      queryClient.invalidateQueries(['customers']);
      setDeleteDialogOpen(false);
    },
    onError: () => {
      setDeleteDialogOpen(false);
    },
  });

  const handleDelete = () => {
    if (selectedCustomer) deleteMutation.mutate(selectedCustomer.id);
  };

  const customers = customersData?.data?.content || [];
  const totalElements = customersData?.data?.totalElements || 0;

  const handleExport = async () => {
    try {
      await customerService.downloadExport();
    } catch (error) {
      console.error('Export failed:', error);
    }
  };

  return (
    <Box>
      <Box sx={{ display: 'flex', justifyContent: 'right', alignItems: 'center', mb: 3, gap: 1 }}>
        
        {isManager() && (
          <>
            <Button variant="outlined" startIcon={<DownloadIcon />} onClick={handleExport}>
              Export
            </Button>
            <Button variant="contained" startIcon={<AddIcon />} onClick={() => navigate('/customers/new')}>
              Add Customer
            </Button>
          </>
        )}
      </Box>

      <Paper sx={{ mb: 2 }}>
        <TextField fullWidth placeholder="Search customers..." value={search} onChange={(e) => setSearch(e.target.value)} InputProps={{ startAdornment: <SearchIcon sx={{ mr: 1, color: 'text.secondary' }} /> }} size="small" />
      </Paper>

      <TableContainer component={Paper}>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>Name</TableCell>
              <TableCell>Phone</TableCell>
              <TableCell>Email</TableCell>
              <TableCell>Address</TableCell>
              <TableCell>Created</TableCell>
              <TableCell>Credit (Balance)</TableCell>
              <TableCell>Credit Limit</TableCell>
              {isManager() && <TableCell align="right">Actions</TableCell>}
            </TableRow>
          </TableHead>
          <TableBody>
            {isLoading ? (
              <TableRow><TableCell colSpan={8} align="center">Loading...</TableCell></TableRow>
            ) : customers.length === 0 ? (
              <TableRow><TableCell colSpan={8} align="center">No customers found</TableCell></TableRow>
            ) : (
              customers.map((c) => (
                <TableRow key={c.id}>
                  <TableCell>
                    {`${c.firstName || ''} ${c.lastName || ''}`.trim()}
                  </TableCell>
                  <TableCell>{c.phone || '-'}</TableCell>
                  <TableCell>{c.email || '-'}</TableCell>
                  <TableCell>{c.address || '-'}</TableCell>
                  <TableCell>{formatDateTime(c.createdAt)}</TableCell>
                  <TableCell>{c.creditBalance ?? 0}</TableCell>
                  <TableCell>{c.creditLimit ?? 0}</TableCell>
                  {isManager() && (
                    <TableCell align="right">
                      <IconButton size="small" onClick={() => navigate(`/customers/${c.id}`)}><EditIcon /></IconButton>
                      <IconButton size="small" color="error" onClick={() => { setSelectedCustomer(c); setDeleteDialogOpen(true); }}><DeleteIcon /></IconButton>
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
        <DialogContent>Are you sure you want to delete "{selectedCustomer?.name}"?</DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteDialogOpen(false)}>Cancel</Button>
          <Button onClick={handleDelete} color="error" variant="contained">Delete</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default Customers;
