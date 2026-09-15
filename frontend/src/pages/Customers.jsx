import { useState, useEffect } from 'react'; // ✅ 1. Added useEffect to imports
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import {
  Box, Typography, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Paper, Button, IconButton, TextField, TablePagination, Dialog, DialogTitle, DialogContent, DialogActions, Alert, Chip, MenuItem,
} from '@mui/material';
import { Add as AddIcon, Edit as EditIcon, Delete as DeleteIcon, Search as SearchIcon, PersonAddAlt as QuickAddIcon } from '@mui/icons-material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { customerService } from '../api/services';
import { formatDateTime } from '../utils/helpers';
import { useAuth } from '../context/AuthContext';
import useListFilters from '../hooks/useListFilters';

const Customers = () => {
  const { filters, update, detailUrl } = useListFilters({
    page: { init: 0, parse: Number, serialize: (v) => String(v) },
    size: { init: 10, parse: Number },
    search: { init: '' },
    city: { init: '' },
  });
  const { page, size, search, city } = filters;
  const [debouncedSearch, setDebouncedSearch] = useState('');
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);
  const [selectedCustomer, setSelectedCustomer] = useState(null);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { isManager } = useAuth();
  const { t } = useTranslation('customers');

  // ✅ 3. Debounce useEffect (waits 300ms after typing stops)
  useEffect(() => {
    const timer = setTimeout(() => setDebouncedSearch(search), 300);
    return () => clearTimeout(timer);
  }, [search]);

  // ✅ 4. useQuery now depends on debouncedSearch, NOT search
  const { data: customersData, isLoading } = useQuery({
    queryKey: ['customers', page, size, debouncedSearch, city],
    queryFn: () => {
      if (debouncedSearch.trim() || city) {
        return customerService.search(debouncedSearch, page, size, city);
      }
      return customerService.getAll(page, size);
    },
  });

  const { data: citiesData } = useQuery({
    queryKey: ['customerCities'],
    queryFn: () => customerService.getCities(),
    staleTime: 5 * 60 * 1000,
  });

  const deleteMutation = useMutation({
    mutationFn: (id) => customerService.delete(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['customers'] });
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
  const totalElements = customersData?.data?.page?.totalElements || 0;

  return (
    <Box>
      <Box sx={{ display: 'flex', justifyContent: 'right', alignItems: 'center', mb: 3 }}>
        {isManager() && (
          <Button variant="contained" startIcon={<AddIcon />} onClick={() => navigate('/customers/new')}>
            {t('add_customer')}
          </Button>
        )}
      </Box>

      <Paper sx={{ mb: 2, p: 2 }}>
        <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
          <TextField 
            sx={{ flexGrow: 1, minWidth: 220 }}
            placeholder={t('search_placeholder')} 
            value={search} 
            onChange={(e) => update({ search: e.target.value, page: 0 }, { replace: true })} 
            InputProps={{ startAdornment: <SearchIcon sx={{ mr: 1, color: 'text.secondary' }} /> }} 
            size="small" 
          />
          <TextField
            select
            label={t('city')}
            value={city}
            onChange={(e) => { update({ city: e.target.value, page: 0 }); }}
            size="small"
            sx={{ minWidth: 180 }}
          >
            <MenuItem value="">{t('all_cities')}</MenuItem>
            {(citiesData?.data || []).map((c) => (
              <MenuItem key={c} value={c}>{c}</MenuItem>
            ))}
          </TextField>
        </Box>
      </Paper>

      <TableContainer component={Paper}>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>{t('name')}</TableCell>
              <TableCell>{t('phone')}</TableCell>
              <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{t('email_or_account')}</TableCell>
              <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{t('city')}</TableCell>
              <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{t('address')}</TableCell>
              <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{t('created')}</TableCell>
              {isManager() && <TableCell align="right">{t('actions')}</TableCell>}
            </TableRow>
          </TableHead>
          <TableBody>
            {isLoading ? (
              <TableRow><TableCell colSpan={7} align="center">{t('loading')}</TableCell></TableRow>
            ) : customers.length === 0 ? (
              <TableRow><TableCell colSpan={7} align="center">{t('no_customers_found')}</TableCell></TableRow>
            ) : (
              customers.map((c) => (
                <TableRow 
                  key={c.id} 
                  hover 
                  onClick={() => navigate(detailUrl(`/customers/${c.id}`))} 
                  sx={{ cursor: 'pointer' }}
                >
                  <TableCell>
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                      {`${c.firstName || ''} ${c.lastName || ''}`.trim()}
                      {c.isQuickAdd && <Chip label={t('quick_add')} size="small" color="warning" variant="outlined" icon={<QuickAddIcon />} />}
                    </Box>
                  </TableCell>
                  <TableCell>{(c.phones && c.phones.length) ? c.phones.join(', ') : (c.phone || '-')}</TableCell>
                  <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{c.email || '-'}</TableCell>
                  <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{c.city || '-'}</TableCell>
                  <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{c.address || '-'}</TableCell>
                  <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{formatDateTime(c.createdAt)}</TableCell>
                  {isManager() && (
                    <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                      {c.isQuickAdd && (
                        <Button 
                          size="small" 
                          variant="outlined" 
                          onClick={(e) => { e.stopPropagation(); navigate(detailUrl(`/customers/${c.id}/edit`)); }} 
                          sx={{ mr: 1 }}
                        >
                          {t('complete_profile')}
                        </Button>
                      )}
                      {!c.isQuickAdd && (
                        <IconButton 
                          size="small" 
                          onClick={(e) => { e.stopPropagation(); navigate(detailUrl(`/customers/${c.id}/edit`)); }}
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
        <TablePagination 
          component="div" 
          count={totalElements} 
          page={page} 
          rowsPerPage={size} 
          onPageChange={(e, newPage) => update({ page: newPage })} 
          onRowsPerPageChange={(e) => { update({ size: parseInt(e.target.value), page: 0 }); }} 
          rowsPerPageOptions={[5, 10, 25]} 
        />
      </TableContainer>

      <Dialog open={deleteDialogOpen} onClose={() => setDeleteDialogOpen(false)}>
        <DialogTitle>{t('confirm_delete')}</DialogTitle>
        <DialogContent>
          {t('delete_confirm', { name: selectedCustomer ? `${selectedCustomer.firstName} ${selectedCustomer.lastName}`.trim() : '' })}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteDialogOpen(false)}>{t('cancel')}</Button>
          <Button onClick={handleDelete} color="error" variant="contained">{t('delete')}</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default Customers;