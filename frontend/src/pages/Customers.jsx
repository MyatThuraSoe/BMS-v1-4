import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Box, Typography, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Paper, Button, IconButton, TextField, TablePagination, Dialog, DialogTitle, DialogContent, DialogActions, Alert, Chip,
} from '@mui/material';
import { Add as AddIcon, Edit as EditIcon, Delete as DeleteIcon, Search as SearchIcon, PersonAddAlt as QuickAddIcon } from '@mui/icons-material';
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

  return (
    <Box>
      <Box sx={{ display: 'flex', justifyContent: 'right', alignItems: 'center', mb: 3 }}>
        {isManager() && (
          <Button variant="contained" startIcon={<AddIcon />} onClick={() => navigate('/customers/new')}>
            Add Customer
          </Button>
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
              {isManager() && <TableCell align="right">Actions</TableCell>}
            </TableRow>
          </TableHead>
          <TableBody>
            {isLoading ? (
              <TableRow><TableCell colSpan={6} align="center">Loading...</TableCell></TableRow>
            ) : customers.length === 0 ? (
              <TableRow><TableCell colSpan={6} align="center">No customers found</TableCell></TableRow>
            ) : (
              customers.map((c) => (
                <TableRow 
                  key={c.id} 
                  hover 
                  onClick={() => navigate(`/customers/${c.id}`)} 
                  sx={{ cursor: 'pointer' }}
                >
                  <TableCell>
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                      {`${c.firstName || ''} ${c.lastName || ''}`.trim()}
                      {c.isQuickAdd && <Chip label="Quick Add" size="small" color="warning" variant="outlined" icon={<QuickAddIcon />} />}
                    </Box>
                  </TableCell>
                  <TableCell>{c.phone || '-'}</TableCell>
                  <TableCell>{c.email || '-'}</TableCell>
                  <TableCell>{c.address || '-'}</TableCell>
                  <TableCell>{formatDateTime(c.createdAt)}</TableCell>
                  {isManager() && (
                    <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                      {c.isQuickAdd && (
                        <Button 
                          size="small" 
                          variant="outlined" 
                          onClick={(e) => { e.stopPropagation(); navigate(`/customers/${c.id}/edit`); }} 
                          sx={{ mr: 1 }}
                        >
                          Complete Profile
                        </Button>
                      )}
                      {!c.isQuickAdd && (
                        <IconButton 
                          size="small" 
                          onClick={(e) => { e.stopPropagation(); navigate(`/customers/${c.id}/edit`); }}
                        >
                          <EditIcon />
                        </IconButton>
                      )}
                      <IconButton 
                        size="small" 
                        color="error" 
                        onClick={(e) => { e.stopPropagation(); setSelectedCustomer(c); setDeleteDialogOpen(true); }}
                      >
                        <DeleteIcon />
                      </IconButton>
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
          Are you sure you want to delete "{selectedCustomer ? `${selectedCustomer.firstName} ${selectedCustomer.lastName}`.trim() : ''}"?
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteDialogOpen(false)}>Cancel</Button>
          <Button onClick={handleDelete} color="error" variant="contained">Delete</Button>
        </DialogActions>
      </Dialog>
      
    </Box>
  );
};

export default Customers;