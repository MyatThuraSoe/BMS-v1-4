import { useState, useEffect } from 'react'; // ✅ 1. Added useEffect to imports
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
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
  const { t } = useTranslation('purchases');

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
      queryClient.invalidateQueries({ queryKey: ['suppliers'] });
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
            {t('add_supplier')}
          </Button>
        )}
      </Box>

      <Paper sx={{ mb: 2 }}>
        {/* This correctly updates the immediate 'search' state, which triggers the debounce timer */}
        <TextField 
          fullWidth 
          placeholder={t('search_suppliers')} 
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
              <TableCell>{t('name')}</TableCell>
              <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{t('contact')}</TableCell>
              <TableCell>{t('phone')}</TableCell>
              <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{t('email')}</TableCell>
              <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{t('created')}</TableCell>
              {isManager() && <TableCell align="right">{t('actions')}</TableCell>}
            </TableRow>
          </TableHead>
          <TableBody>
            {isLoading ? (
              <TableRow><TableCell colSpan={6} align="center">{t('loading')}</TableCell></TableRow>
            ) : suppliers.length === 0 ? (
              <TableRow><TableCell colSpan={6} align="center">{t('no_suppliers_found')}</TableCell></TableRow>
            ) : (
              suppliers.map((s) => (
                <TableRow 
                  key={s.id} 
                  hover 
                  onClick={() => navigate(`/suppliers/${s.id}`)} 
                  sx={{ cursor: 'pointer' }}
                >
                  <TableCell>{s.name}</TableCell>
                  <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{s.contactPerson || '-'}</TableCell>
                  <TableCell>{s.phone || '-'}</TableCell>
                  <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{s.email || '-'}</TableCell>
                  <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{formatDateTime(s.createdAt)}</TableCell>
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
        <DialogTitle>{t('confirm_delete')}</DialogTitle>
        <DialogContent>{t('delete_supplier_confirm', { name: selectedSupplier?.name })}</DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteDialogOpen(false)}>{t('cancel')}</Button>
          <Button onClick={handleDelete} color="error" variant="contained">{t('delete')}</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default Suppliers;