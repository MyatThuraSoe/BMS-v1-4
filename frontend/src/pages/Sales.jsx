import { useState, useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  Box, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Paper, Button, IconButton, TextField, TablePagination, Dialog, DialogTitle, DialogContent, DialogActions, Alert, Chip, InputAdornment, Autocomplete,
} from '@mui/material';
import { Delete as DeleteIcon, Search as SearchIcon } from '@mui/icons-material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { saleService, customerService } from '../api/services';
import { formatDateTime, formatCurrency } from '../utils/helpers';
import { useAuth } from '../context/AuthContext';
import { useTranslation } from 'react-i18next';

const RANGE_PRESETS = [
  { value: 'today', label: 'Today' },
  { value: 'week', label: 'This Week' },
  { value: 'month', label: 'This Month' },
  { value: 'quarter', label: 'This Quarter' },
  { value: 'year', label: 'This Year' },
  { value: 'ALL', label: 'All Time' },
  { value: 'CUSTOM', label: 'Custom Range' },
];

const Sales = () => {
  const [searchParams, setSearchParams] = useSearchParams();
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);
  const [selectedSale, setSelectedSale] = useState(null);
  const [invoiceSearch, setInvoiceSearch] = useState('');
  const [debouncedInvoice, setDebouncedInvoice] = useState('');
  const [selectedCustomer, setSelectedCustomer] = useState(null);
  const [customerInput, setCustomerInput] = useState('');
  const [customStartDate, setCustomStartDate] = useState('');
  const [customEndDate, setCustomEndDate] = useState('');
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { isManager } = useAuth();
  const { t } = useTranslation('sales');

  const range = searchParams.get('range') || 'today';

  useEffect(() => {
    const timer = setTimeout(() => setDebouncedInvoice(invoiceSearch), 300);
    return () => clearTimeout(timer);
  }, [invoiceSearch]);

  useEffect(() => {
    if (range !== 'CUSTOM') {
      setCustomStartDate('');
      setCustomEndDate('');
    }
  }, [range]);

  const { data: customerResults } = useQuery({
    queryKey: ['customer-search', customerInput],
    queryFn: () => customerService.search(customerInput, 0, 20),
    enabled: customerInput.length > 0,
  });

  const { data: salesData, isLoading, isFetching } = useQuery({
    queryKey: [
      'sales',
      page,
      size,
      range,
      customStartDate,
      customEndDate,
      selectedCustomer?.id,
      debouncedInvoice,
    ],
    queryFn: () =>
      saleService.getAll(
        page,
        size,
        'saleDate',
        range || null,
        range === 'CUSTOM' ? customStartDate : null,
        range === 'CUSTOM' ? customEndDate : null,
        selectedCustomer?.id || null,
        debouncedInvoice || null
      ),
    staleTime: 0,
    refetchOnMount: 'always',
    refetchOnWindowFocus: true,
  });

  const handleRangeChange = (newRange) => {
    setSearchParams(newRange && newRange !== 'today' ? { range: newRange } : {});
    setPage(0);
    if (newRange !== 'CUSTOM') {
      setCustomStartDate('');
      setCustomEndDate('');
    }
  };

  const clearFilters = () => {
    setSearchParams({});
    setInvoiceSearch('');
    setDebouncedInvoice('');
    setSelectedCustomer(null);
    setCustomStartDate('');
    setCustomEndDate('');
    setPage(0);
  };

  const deleteMutation = useMutation({
    mutationFn: (id) => saleService.delete(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['sales'] });
      queryClient.invalidateQueries({ queryKey: ['products'] });
      queryClient.invalidateQueries({ queryKey: ['low-stock'] });
      queryClient.invalidateQueries({ queryKey: ['inventoryReport'] });
      setDeleteDialogOpen(false);
    },
  });

  const handleDelete = () => {
    if (selectedSale) deleteMutation.mutate(selectedSale.id);
  };

  const sales = salesData?.data?.content || [];
  const totalElements = salesData?.data?.page?.totalElements || 0;

  const getSaleStatus = (sale) => {
    if (sale.isVoided) return 'VOIDED';
    const items = sale.items || [];
    const refunded = items.reduce((sum, item) => sum + Number(item.quantityRefunded || 0), 0);
    const quantity = items.reduce((sum, item) => sum + Number(item.quantity || 0), 0);
    if (quantity > 0 && refunded >= quantity) return 'REFUNDED';
    if (refunded > 0) return 'PARTIALLY REFUNDED';
    return 'COMPLETED';
  };

  const getStatusColor = (status) => {
    if (status === 'COMPLETED') return 'success';
    if (status === 'VOIDED') return 'error';
    if (status === 'REFUNDED' || status === 'PARTIALLY REFUNDED') return 'warning';
    return 'default';
  };

  const hasActiveFilters = range !== 'today' || debouncedInvoice || selectedCustomer || (range === 'CUSTOM' && (customStartDate || customEndDate));

  return (
    <Box>
      <Box sx={{ display: 'flex', gap: 2, mb: 2, flexWrap: 'wrap', alignItems: 'center' }}>
        <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap' }}>
          {RANGE_PRESETS.map((preset) => (
            <Chip
              key={preset.value}
              label={t(`range_${preset.value.toLowerCase()}`)}
              onClick={() => handleRangeChange(preset.value)}
              color={range === preset.value ? 'primary' : 'default'}
              variant={range === preset.value ? 'filled' : 'outlined'}
              size="small"
            />
          ))}
        </Box>
      </Box>

      <Paper sx={{ p: 2, mb: 2 }}>
        <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap', alignItems: 'center' }}>
          <TextField
            size="small"
            placeholder={t('search_by_invoice')}
            value={invoiceSearch}
            onChange={(e) => setInvoiceSearch(e.target.value)}
            sx={{ minWidth: 220 }}
            InputProps={{
              startAdornment: <InputAdornment position="start"><SearchIcon fontSize="small" /></InputAdornment>,
            }}
          />
          <Autocomplete
            size="small"
            sx={{ minWidth: 250 }}
            options={customerResults?.data?.content || []}
            getOptionLabel={(option) => `${option.firstName} ${option.lastName} (${option.phone || option.email})`}
            value={selectedCustomer}
            onChange={(e, newValue) => { setSelectedCustomer(newValue); setPage(0); }}
            inputValue={customerInput}
            onInputChange={(e, newValue) => setCustomerInput(newValue)}
            renderInput={(params) => <TextField {...params} label={t('filter_by_customer')} />}
            isOptionEqualToValue={(option, value) => option.id === value.id}
          />
          {range === 'CUSTOM' && (
            <>
              <TextField
                size="small"
                type="date"
                label={t('start_date')}
                value={customStartDate}
                onChange={(e) => { setCustomStartDate(e.target.value); setPage(0); }}
                InputLabelProps={{ shrink: true }}
                sx={{ minWidth: 160 }}
              />
              <TextField
                size="small"
                type="date"
                label={t('end_date')}
                value={customEndDate}
                onChange={(e) => { setCustomEndDate(e.target.value); setPage(0); }}
                InputLabelProps={{ shrink: true }}
                sx={{ minWidth: 160 }}
              />
            </>
          )}
          {hasActiveFilters && (
            <Button size="small" onClick={clearFilters}>{t('clear_filters')}</Button>
          )}
        </Box>
      </Paper>

      <TableContainer component={Paper}>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>{t('invoice_number')}</TableCell>
              <TableCell>{t('customer')}</TableCell>
              <TableCell align="right">{t('total')}</TableCell>
              <TableCell align="right" sx={{ display: { xs: 'none', sm: 'table-cell' } }}>{t('paid')}</TableCell>
              <TableCell>{t('status')}</TableCell>
              <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{t('date')}</TableCell>
              <TableCell align="right" sx={{ width: 100 }}>{t('actions')}</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {isLoading ? (
              <TableRow><TableCell colSpan={7} align="center">{t('loading')}</TableCell></TableRow>
            ) : sales.length === 0 ? (
              <TableRow><TableCell colSpan={7} align="center">{t('no_sales_found')}</TableCell></TableRow>
            ) : (
              sales.map((s) => {
                const status = getSaleStatus(s);
                return (
                  <TableRow
                    key={s.id}
                    hover
                    onClick={() => navigate(`/sales/${s.id}`)}
                    sx={{ cursor: 'pointer' }}
                  >
                    <TableCell>{s.invoiceNumber}</TableCell>
                    <TableCell>{s.customerName || t('walk_in')}</TableCell>
                    <TableCell align="right">{formatCurrency(s.totalAmount)}</TableCell>
                    <TableCell align="right" sx={{ display: { xs: 'none', sm: 'table-cell' } }}>{formatCurrency(s.amountPaid)}</TableCell>
                    <TableCell><Chip label={t(`status_${status.toLowerCase().replace(/\s+/g, '_')}`)} size="small" color={getStatusColor(status)} /></TableCell>
                    <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{formatDateTime(s.saleDate)}</TableCell>
                    <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                      {isManager() && (
                        <IconButton size="small" color="error" onClick={(e) => { e.stopPropagation(); setSelectedSale(s); setDeleteDialogOpen(true); }}><DeleteIcon /></IconButton>
                      )}
                    </TableCell>
                  </TableRow>
                );
              })
            )}
          </TableBody>
        </Table>
        <TablePagination component="div" count={totalElements} page={page} rowsPerPage={size} onPageChange={(e, newPage) => setPage(newPage)} onRowsPerPageChange={(e) => { setSize(parseInt(e.target.value)); setPage(0); }} rowsPerPageOptions={[5, 10, 25]} />
      </TableContainer>

      <Dialog open={deleteDialogOpen} onClose={() => setDeleteDialogOpen(false)}>
        <DialogTitle>{t('confirm_delete')}</DialogTitle>
        <DialogContent>{t('delete_sale_confirm', { number: selectedSale?.invoiceNumber })}</DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteDialogOpen(false)}>{t('cancel')}</Button>
          <Button onClick={handleDelete} color="error" variant="contained">{t('delete')}</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default Sales;