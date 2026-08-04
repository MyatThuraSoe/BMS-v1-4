import { useState, useEffect } from 'react'; // ✅ 1. Added useEffect to imports
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
  
  // ✅ 2. Single, clean declaration of search states
  const [search, setSearch] = useState('');
  const [debouncedSearch, setDebouncedSearch] = useState('');
  
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);
  const [selectedSupplier, setSelectedSupplier] = useState(null);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { isManager } = useAuth();

  // ✅ 3. Debounce useEffect (waits 300ms after typing stops)
  useEffect(() => {
    const timer = setTimeout(() => setDebouncedSearch(search), 300);
    return () => clearTimeout(timer);
  }, [search]);

  // ✅ 4. useQuery now uses debouncedSearch AND actually performs the search
  const { data: suppliersData, isLoading } = useQuery({
    queryKey: ['suppliers', page, size, debouncedSearch],
    queryFn: () => {
      if (debouncedSearch.trim()) {
        // Calls the search endpoint if there is text
        return supplierService.search(debouncedSearch); 
      }
      // Falls back to paginated list if search is empty
      return supplierService.getAll(page, size);
    },
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
  const totalElements = suppliersData?.data?.page?.totalElements || 0;

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
        {/* This correctly updates the immediate 'search' state, which triggers the debounce timer */}
        <TextField 
          fullWidth 
          placeholder="Search suppliers..." 
          value={search} 
          onChange={(e) => setSearch(e.target.value)} 
          InputProps={{ startAdornment: <SearchIcon sx={{ mr: 1, color: 'text.secondary' }} /> }} 
          size="small" 
        />
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
                <TableRow 
                  key={s.id} 
                  hover 
                  onClick={() => navigate(`/suppliers/${s.id}`)} 
                  sx={{ cursor: 'pointer' }}
                >
                  <TableCell>{s.name}</TableCell>
                  <TableCell>{s.contactPerson || '-'}</TableCell>
                  <TableCell>{s.phone || '-'}</TableCell>
                  <TableCell>{s.email || '-'}</TableCell>
                  <TableCell>{formatDateTime(s.createdAt)}</TableCell>
                  {isManager() && (
                    <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                      <IconButton 
                        size="small" 
                        color="primary" 
                        onClick={(e) => { e.stopPropagation(); navigate(`/suppliers/${s.id}/edit`); }}
                        sx={{ mr: 1 }}
                      >
                        <EditIcon fontSize="small" />
                      </IconButton>
                      <IconButton 
                        size="small" 
                        color="error" 
                        onClick={(e) => { e.stopPropagation(); setSelectedSupplier(s); setDeleteDialogOpen(true); }}
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
          rowsPerPageOptions={[5, 10, 25]} 
        />
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