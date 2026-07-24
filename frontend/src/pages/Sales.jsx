import { useState, useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  Box, Typography, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Paper, Button, IconButton, TextField, TablePagination, Dialog, DialogTitle, DialogContent, DialogActions, Alert, Chip, InputAdornment, Autocomplete,
} from '@mui/material';
import { Delete as DeleteIcon, Visibility as ViewIcon, Print as PrintIcon, Search as SearchIcon } from '@mui/icons-material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { saleService, customerService } from '../api/services';
import { formatDateTime, formatCurrency } from '../utils/helpers';
import { useAuth } from '../context/AuthContext';

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
  const [voidDialogOpen, setVoidDialogOpen] = useState(false);
  const [selectedSale, setSelectedSale] = useState(null);
  const [voidReason, setVoidReason] = useState('');
  const [invoiceSearch, setInvoiceSearch] = useState('');
  const [debouncedInvoice, setDebouncedInvoice] = useState('');
  const [selectedCustomer, setSelectedCustomer] = useState(null);
  const [customerInput, setCustomerInput] = useState('');
  const [customStartDate, setCustomStartDate] = useState('');
  const [customEndDate, setCustomEndDate] = useState('');
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { isManager } = useAuth();

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

  const { data: salesData, isLoading } = useQuery({
    queryKey: ['sales', page, size, range, customStartDate, customEndDate, selectedCustomer?.id, debouncedInvoice],
    queryFn: () => saleService.getAll(
      page, size, 'saleDate',
      range || null,
      range === 'CUSTOM' ? customStartDate : null,
      range === 'CUSTOM' ? customEndDate : null,
      selectedCustomer?.id || null,
      debouncedInvoice || null,
    ),
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
              label={preset.label}
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
            placeholder="Search by invoice..."
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
            renderInput={(params) => <TextField {...params} label="Filter by Customer" />}
            isOptionEqualToValue={(option, value) => option.id === value.id}
          />
          {range === 'CUSTOM' && (
            <>
              <TextField
                size="small"
                type="date"
                label="Start Date"
                value={customStartDate}
                onChange={(e) => { setCustomStartDate(e.target.value); setPage(0); }}
                InputLabelProps={{ shrink: true }}
                sx={{ minWidth: 160 }}
              />
              <TextField
                size="small"
                type="date"
                label="End Date"
                value={customEndDate}
                onChange={(e) => { setCustomEndDate(e.target.value); setPage(0); }}
                InputLabelProps={{ shrink: true }}
                sx={{ minWidth: 160 }}
              />
            </>
          )}
          {hasActiveFilters && (
            <Button size="small" onClick={clearFilters}>Clear Filters</Button>
          )}
        </Box>
      </Paper>

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
              sales.map((s) => {
                const status = getSaleStatus(s);
                return (
                  <TableRow key={s.id}>
                    <TableCell>{s.invoiceNumber}</TableCell>
                    <TableCell>{s.customerName || 'Walk-in'}</TableCell>
                    <TableCell align="right">{formatCurrency(s.totalAmount)}</TableCell>
                    <TableCell align="right">{formatCurrency(s.amountPaid)}</TableCell>
                    <TableCell><Chip label={status} size="small" color={getStatusColor(status)} /></TableCell>
                    <TableCell>{formatDateTime(s.saleDate)}</TableCell>
                    <TableCell align="right">
                      <IconButton size="small" onClick={() => navigate(`/receipt/${s.invoiceNumber}`)}><PrintIcon /></IconButton>
                      <IconButton size="small" onClick={() => navigate(`/sales/${s.id}`)}><ViewIcon /></IconButton>
                      {isManager() && status !== 'VOIDED' && (
                        <IconButton size="small" color="warning" onClick={() => { setSelectedSale(s); setVoidDialogOpen(true); }}>Void</IconButton>
                      )}
                      {isManager() && (
                        <IconButton size="small" color="error" onClick={() => { setSelectedSale(s); setDeleteDialogOpen(true); }}><DeleteIcon /></IconButton>
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