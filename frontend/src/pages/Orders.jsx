import { useState, useEffect } from 'react';
import {
  Box, Typography, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Paper, Button, IconButton, TextField, TablePagination, Dialog, DialogTitle, DialogContent, DialogActions, Alert, Chip, InputAdornment, Autocomplete, Tab, Tabs,
} from '@mui/material';
import { Add as AddIcon, Search as SearchIcon, RemoveCircleOutline as RemoveIcon, Visibility as VisibilityIcon, Login as ConvertIcon, Cancel as CancelIcon } from '@mui/icons-material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { orderService, customerService, productService, shiftService } from '../api/services';
import { formatDateTime, formatCurrency } from '../utils/helpers';
import { useTranslation } from 'react-i18next';
import { notifySuccess, notifyError } from '../utils/notify';

const STATUS_FILTERS = ['ALL', 'PENDING', 'CONVERTED', 'CANCELLED'];

const Orders = () => {
  const { t } = useTranslation('orders');
  const queryClient = useQueryClient();

  const [status, setStatus] = useState('ALL');
  const [startDate, setStartDate] = useState('');
  const [endDate, setEndDate] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const [orderSearch, setOrderSearch] = useState('');
  const [debouncedSearch, setDebouncedSearch] = useState('');
  const [selectedCustomer, setSelectedCustomer] = useState(null);
  const [customerInput, setCustomerInput] = useState('');

  const [viewOrder, setViewOrder] = useState(null);
  const [newOrderOpen, setNewOrderOpen] = useState(false);
  const [convertOrder, setConvertOrder] = useState(null);
  const [cancelOrder, setCancelOrder] = useState(null);

  const [convertMethod, setConvertMethod] = useState('CASH');
  const [convertAmount, setConvertAmount] = useState('');
  const [convertDueDate, setConvertDueDate] = useState('');
  const [convertError, setConvertError] = useState('');
  const [cancelReason, setCancelReason] = useState('');

  const [orderCustomer, setOrderCustomer] = useState(null);
  const [orderCustomerInput, setOrderCustomerInput] = useState('');
  const [orderNotes, setOrderNotes] = useState('');
  const [productQuery, setProductQuery] = useState('');
  const [selectedProduct, setSelectedProduct] = useState(null);
  const [qty, setQty] = useState(1);
  const [cartItems, setCartItems] = useState([]);
  const [createError, setCreateError] = useState('');

  useEffect(() => {
    const timer = setTimeout(() => setDebouncedSearch(orderSearch), 300);
    return () => clearTimeout(timer);
  }, [orderSearch]);

  const { data: customerResults } = useQuery({
    queryKey: ['customer-search', customerInput],
    queryFn: () => customerService.search(customerInput, 0, 20),
    enabled: customerInput.length > 0,
  });

  const { data: orderCustomerResults } = useQuery({
    queryKey: ['customer-search', orderCustomerInput],
    queryFn: () => customerService.search(orderCustomerInput, 0, 20),
    enabled: orderCustomerInput.length > 0,
  });

  const { data: productResults } = useQuery({
    queryKey: ['product-search-orders', productQuery],
    queryFn: () => productService.search(productQuery, 0, 20),
    enabled: productQuery.length > 0,
  });

  const { data: shift } = useQuery({
    queryKey: ['current-shift'],
    queryFn: shiftService.getCurrentShift,
    staleTime: 0,
    retry: false,
  });

  const { data: ordersData, isLoading } = useQuery({
    queryKey: ['orders', page, size, status, startDate, endDate, selectedCustomer?.id ?? null, debouncedSearch],
    queryFn: () =>
      orderService.getAll({
        page,
        size,
        status: status === 'ALL' ? null : status,
        startDate: startDate || null,
        endDate: endDate || null,
        customerId: selectedCustomer?.id ?? null,
        orderNumber: debouncedSearch || null,
      }),
    staleTime: 0,
    refetchOnMount: 'always',
    refetchOnWindowFocus: true,
  });

  const invalidateAll = () => {
    queryClient.invalidateQueries({ queryKey: ['orders'] });
    queryClient.invalidateQueries({ queryKey: ['products'] });
    queryClient.invalidateQueries({ queryKey: ['low-stock'] });
  };

  const createMutation = useMutation({
    mutationFn: (data) => orderService.create(data),
    onSuccess: () => {
      notifySuccess(t('order_created'));
      invalidateAll();
      setNewOrderOpen(false);
      resetOrderForm();
    },
    onError: (err) => notifyError(err.friendlyMessage || t('order_create_failed')),
  });

  const convertMutation = useMutation({
    mutationFn: ({ id, data }) => orderService.convert(id, data),
    onSuccess: () => {
      notifySuccess(t('order_converted'));
      invalidateAll();
      queryClient.invalidateQueries({ queryKey: ['ar'] });
      queryClient.invalidateQueries({ queryKey: ['current-shift'] });
      setConvertOrder(null);
      setConvertMethod('CASH');
      setConvertAmount('');
      setConvertDueDate('');
      setConvertError('');
      setViewOrder(null);
    },
    onError: (err) => {
      setConvertError(err.friendlyMessage || t('order_convert_failed'));
    },
  });

  const cancelMutation = useMutation({
    mutationFn: ({ id, data }) => orderService.cancel(id, data),
    onSuccess: () => {
      notifySuccess(t('order_cancelled'));
      invalidateAll();
      setCancelOrder(null);
      setCancelReason('');
      setViewOrder(null);
    },
    onError: (err) => notifyError(err.friendlyMessage || t('order_cancel_failed')),
  });

  const orders = ordersData?.data?.content || [];
  const totalElements = ordersData?.data?.page?.totalElements || 0;

  const getStatusColor = (s) => {
    if (s === 'PENDING') return 'warning';
    if (s === 'CONVERTED') return 'success';
    if (s === 'CANCELLED') return 'error';
    return 'default';
  };

  const clearFilters = () => {
    setStatus('ALL');
    setStartDate('');
    setEndDate('');
    setSelectedCustomer(null);
    setOrderSearch('');
    setDebouncedSearch('');
    setPage(0);
  };

  const hasActiveFilters = status !== 'ALL' || startDate || endDate || selectedCustomer || debouncedSearch;

  function resetOrderForm() {
    setOrderCustomer(null);
    setOrderCustomerInput('');
    setOrderNotes('');
    setProductQuery('');
    setSelectedProduct(null);
    setQty(1);
    setCartItems([]);
    setCreateError('');
  }

  const subtotal = cartItems.reduce((sum, it) => sum + it.unitPrice * it.quantity, 0);

  const addToCart = () => {
    if (!selectedProduct) {
      setCreateError(t('select_product_required'));
      return;
    }
    const q = parseInt(qty, 10) || 1;
    if (q < 1) {
      setCreateError(t('qty_positive'));
      return;
    }
    const maxAvail = Number(selectedProduct.availableQuantity ?? selectedProduct.quantity ?? 0);
    const existing = cartItems.find((it) => it.productId === selectedProduct.id);
    const alreadyInCart = existing ? existing.quantity : 0;
    if (alreadyInCart + q > maxAvail) {
      setCreateError(t('insufficient_stock', { available: maxAvail }));
      return;
    }
    setCartItems((prev) => {
      const found = prev.find((it) => it.productId === selectedProduct.id);
      if (found) {
        return prev.map((it) => it.productId === selectedProduct.id ? { ...it, quantity: it.quantity + q } : it);
      }
      return [...prev, {
        productId: selectedProduct.id,
        productName: selectedProduct.name,
        sku: selectedProduct.sku,
        unitPrice: Number(selectedProduct.price),
        quantity: q,
        available: maxAvail,
      }];
    });
    setSelectedProduct(null);
    setProductQuery('');
    setQty(1);
    setCreateError('');
  };

  const removeFromCart = (productId) => {
    setCartItems((prev) => prev.filter((it) => it.productId !== productId));
  };

  const handleCreate = () => {
    if (cartItems.length === 0) {
      setCreateError(t('cart_empty'));
      return;
    }
    createMutation.mutate({
      items: cartItems.map((it) => ({ productId: it.productId, quantity: it.quantity })),
      customerId: orderCustomer?.id ?? null,
      notes: orderNotes || undefined,
    });
  };

  const handleConvert = () => {
    const total = Number(convertOrder?.totalAmount || 0);
    let err = '';
    if (convertMethod === 'CASH') {
      if (!shift?.data) err = t('require_open_shift');
      const amt = Number(convertAmount);
      if (!err && (isNaN(amt) || amt < total)) err = t('full_payment_required');
    } else {
      if (!convertOrder?.customerId) err = t('no_customer_for_credit');
      if (!err && !convertDueDate) err = t('due_date_required');
      if (!err && convertDueDate < new Date().toISOString().slice(0, 10)) err = t('due_date_past');
    }
    if (err) {
      setConvertError(err);
      return;
    }
    setConvertError('');
    convertMutation.mutate({
      id: convertOrder.id,
      data: convertMethod === 'CASH'
        ? { paymentMethod: 'CASH', amountPaid: Number(convertAmount) }
        : { paymentMethod: 'CREDIT', dueDate: convertDueDate },
    });
  };

  const handleCancel = () => {
    if (!cancelReason.trim()) return;
    cancelMutation.mutate({ id: cancelOrder.id, data: { reason: cancelReason.trim() } });
  };

  const statusFilter = (s) => (
    <Chip
      key={s}
      label={s === 'ALL' ? t('all') : t(`status_${s.toLowerCase()}`)}
      onClick={() => { setStatus(s); setPage(0); }}
      color={status === s ? 'primary' : 'default'}
      variant={status === s ? 'filled' : 'outlined'}
      size="small"
    />
  );

  return (
    <Box>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 2, flexWrap: 'wrap', gap: 2 }}>
        <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap' }}>
          {STATUS_FILTERS.map(statusFilter)}
        </Box>
        <Button variant="contained" startIcon={<AddIcon />} onClick={() => { setCreateError(''); setNewOrderOpen(true); }}>
          {t('new_order')}
        </Button>
      </Box>

      <Paper sx={{ p: 2, mb: 2 }}>
        <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap', alignItems: 'center' }}>
          <TextField
            size="small"
            placeholder={t('search_by_order')}
            value={orderSearch}
            onChange={(e) => setOrderSearch(e.target.value)}
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
          <TextField size="small" type="date" label={t('start_date')} value={startDate} onChange={(e) => { setStartDate(e.target.value); setPage(0); }} InputLabelProps={{ shrink: true }} sx={{ minWidth: 160 }} />
          <TextField size="small" type="date" label={t('end_date')} value={endDate} onChange={(e) => { setEndDate(e.target.value); setPage(0); }} InputLabelProps={{ shrink: true }} sx={{ minWidth: 160 }} />
          {hasActiveFilters && <Button size="small" onClick={clearFilters}>{t('clear_filters')}</Button>}
        </Box>
      </Paper>

      <TableContainer component={Paper}>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>{t('order_number')}</TableCell>
              <TableCell>{t('customer')}</TableCell>
              <TableCell align="right">{t('items')}</TableCell>
              <TableCell align="right">{t('total')}</TableCell>
              <TableCell>{t('status')}</TableCell>
              <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{t('created_at')}</TableCell>
              <TableCell align="right" sx={{ width: 130 }}>{t('actions')}</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {isLoading ? (
              <TableRow><TableCell colSpan={7} align="center">{t('loading')}</TableCell></TableRow>
            ) : orders.length === 0 ? (
              <TableRow><TableCell colSpan={7} align="center">{t('no_orders_found')}</TableCell></TableRow>
            ) : (
              orders.map((o) => (
                <TableRow key={o.id} hover onClick={() => setViewOrder(o)} sx={{ cursor: 'pointer' }}>
                  <TableCell>{o.orderNumber}</TableCell>
                  <TableCell>{o.customerName || t('walk_in')}</TableCell>
                  <TableCell align="right">{o.itemCount}</TableCell>
                  <TableCell align="right">{formatCurrency(o.totalAmount)}</TableCell>
                  <TableCell><Chip label={t(`status_${o.status.toLowerCase()}`)} size="small" color={getStatusColor(o.status)} /></TableCell>
                  <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{formatDateTime(o.createdAt)}</TableCell>
                  <TableCell align="right" sx={{ whiteSpace: 'nowrap' }} onClick={(e) => e.stopPropagation()}>
                    <IconButton size="small" color="info" onClick={() => setViewOrder(o)}><VisibilityIcon fontSize="small" /></IconButton>
                    {o.status === 'PENDING' && (
                      <>
                        <IconButton size="small" color="success" onClick={() => { setConvertOrder(o); setConvertMethod('CASH'); setConvertAmount(o.totalAmount); setConvertDueDate(''); setConvertError(''); }}><ConvertIcon fontSize="small" /></IconButton>
                        <IconButton size="small" color="error" onClick={() => { setCancelOrder(o); setCancelReason(''); }}><CancelIcon fontSize="small" /></IconButton>
                      </>
                    )}
                  </TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
        <TablePagination component="div" count={totalElements} page={page} rowsPerPage={size} onPageChange={(e, newPage) => setPage(newPage)} onRowsPerPageChange={(e) => { setSize(parseInt(e.target.value)); setPage(0); }} rowsPerPageOptions={[5, 10, 25]} />
      </TableContainer>

      {/* View order detail dialog */}
      <Dialog open={!!viewOrder} onClose={() => setViewOrder(null)} maxWidth="sm" fullWidth>
        {viewOrder && (
          <>
            <DialogTitle>
              {viewOrder.orderNumber}
              <Chip sx={{ ml: 1 }} label={t(`status_${viewOrder.status.toLowerCase()}`)} size="small" color={getStatusColor(viewOrder.status)} />
            </DialogTitle>
            <DialogContent dividers>
              <Box sx={{ mb: 2, display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 1 }}>
                <Typography variant="body2"><strong>{t('customer')}:</strong> {viewOrder.customerName || t('walk_in')}</Typography>
                <Typography variant="body2"><strong>{t('created_by')}:</strong> {viewOrder.cashierName}</Typography>
                <Typography variant="body2"><strong>{t('created_at')}:</strong> {formatDateTime(viewOrder.createdAt)}</Typography>
                {viewOrder.convertedAt && <Typography variant="body2"><strong>{t('converted_at')}:</strong> {formatDateTime(viewOrder.convertedAt)}</Typography>}
                {viewOrder.cancelledAt && <Typography variant="body2"><strong>{t('cancelled_at')}:</strong> {formatDateTime(viewOrder.cancelledAt)}</Typography>}
                {viewOrder.cancelReason && <Typography variant="body2" sx={{ gridColumn: '1 / -1' }}><strong>{t('reason')}:</strong> {viewOrder.cancelReason}</Typography>}
                {viewOrder.notes && <Typography variant="body2" sx={{ gridColumn: '1 / -1' }}><strong>{t('notes')}:</strong> {viewOrder.notes}</Typography>}
              </Box>
              <TableContainer component={Paper} variant="outlined">
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell>{t('product')}</TableCell>
                      <TableCell align="right">{t('quantity')}</TableCell>
                      <TableCell align="right">{t('unit_price')}</TableCell>
                      <TableCell align="right">{t('line_total')}</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {(viewOrder.items || []).map((it) => (
                      <TableRow key={it.id}>
                        <TableCell>{it.productName}</TableCell>
                        <TableCell align="right">{it.quantity}</TableCell>
                        <TableCell align="right">{formatCurrency(it.unitPrice)}</TableCell>
                        <TableCell align="right">{formatCurrency(it.totalPrice)}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
              <Box sx={{ mt: 2, display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 0.5 }}>
                <Typography variant="body2">{t('subtotal')}: {formatCurrency(viewOrder.subtotal)}</Typography>
                <Typography variant="body2">{t('tax')}: {formatCurrency(viewOrder.taxAmount)}</Typography>
                <Typography variant="h6">{t('total')}: {formatCurrency(viewOrder.totalAmount)}</Typography>
              </Box>
            </DialogContent>
            <DialogActions>
              {viewOrder.status === 'PENDING' && (
                <>
                  <Button color="error" onClick={() => { setCancelOrder(viewOrder); setCancelReason(''); }}>
                    {t('cancel_order')}
                  </Button>
                  <Button color="success" variant="contained" onClick={() => { setConvertOrder(viewOrder); setConvertMethod('CASH'); setConvertAmount(viewOrder.totalAmount); setConvertDueDate(''); setConvertError(''); }}>
                    {t('convert')}
                  </Button>
                </>
              )}
              <Button onClick={() => setViewOrder(null)}>{t('close')}</Button>
            </DialogActions>
          </>
        )}
      </Dialog>

      {/* New order dialog */}
      <Dialog open={newOrderOpen} onClose={() => setNewOrderOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>{t('new_order')}</DialogTitle>
        <DialogContent dividers>
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            <Autocomplete
              size="small"
              options={orderCustomerResults?.data?.content || []}
              getOptionLabel={(option) => `${option.firstName} ${option.lastName} (${option.phone || option.email})`}
              value={orderCustomer}
              onChange={(e, newValue) => setOrderCustomer(newValue)}
              inputValue={orderCustomerInput}
              onInputChange={(e, newValue) => setOrderCustomerInput(newValue)}
              renderInput={(params) => <TextField {...params} label={t('customer_optional')} />}
              isOptionEqualToValue={(option, value) => option.id === value.id}
            />
            <Box sx={{ display: 'flex', gap: 1, alignItems: 'flex-start' }}>
              <Autocomplete
                size="small"
                sx={{ flex: 1, minWidth: 240 }}
                options={productResults?.data?.content || []}
                getOptionLabel={(option) => `${option.sku || ''} ${option.name}`.trim()}
                value={selectedProduct}
                onChange={(e, newValue) => setSelectedProduct(newValue)}
                inputValue={productQuery}
                onInputChange={(e, newValue) => setProductQuery(newValue)}
                renderInput={(params) => <TextField {...params} label={t('select_product')} />}
                isOptionEqualToValue={(option, value) => option.id === value.id}
              />
              <TextField
                size="small"
                type="number"
                label={t('quantity')}
                value={qty}
                onChange={(e) => setQty(e.target.value)}
                inputProps={{ min: 1 }}
                sx={{ width: 100 }}
              />
              <Button variant="outlined" onClick={addToCart}>{t('add')}</Button>
            </Box>
            {selectedProduct && (
              <Typography variant="caption">
                {t('available', { count: selectedProduct.availableQuantity ?? selectedProduct.quantity ?? 0 })}{selectedProduct.sku ? ` · ${selectedProduct.sku}` : ''}
              </Typography>
            )}
            {createError && <Alert severity="error">{createError}</Alert>}
            {cartItems.length > 0 && (
              <TableContainer component={Paper} variant="outlined">
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell>{t('product')}</TableCell>
                      <TableCell align="right">{t('quantity')}</TableCell>
                      <TableCell align="right">{t('line_total')}</TableCell>
                      <TableCell />
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {cartItems.map((it) => (
                      <TableRow key={it.productId}>
                        <TableCell>{it.productName}</TableCell>
                        <TableCell align="right">{it.quantity}</TableCell>
                        <TableCell align="right">{formatCurrency(it.unitPrice * it.quantity)}</TableCell>
                        <TableCell align="right"><IconButton size="small" color="error" onClick={() => removeFromCart(it.productId)}><RemoveIcon fontSize="small" /></IconButton></TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
            )}
            <Box sx={{ display: 'flex', justifyContent: 'flex-end', gap: 0.5 }}>
              <Typography variant="body2" sx={{ mr: 1 }}>{t('subtotal')}: {formatCurrency(subtotal)}</Typography>
            </Box>
            <TextField size="small" label={t('notes')} multiline rows={2} value={orderNotes} onChange={(e) => setOrderNotes(e.target.value)} />
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setNewOrderOpen(false)}>{t('cancel')}</Button>
          <Button variant="contained" onClick={handleCreate} disabled={createMutation.isPending}>
            {t('create_order')}
          </Button>
        </DialogActions>
      </Dialog>

      {/* Convert dialog */}
      <Dialog open={!!convertOrder} onClose={() => setConvertOrder(null)} maxWidth="xs" fullWidth>
        {convertOrder && (
          <>
            <DialogTitle>{t('convert_order')} — {convertOrder.orderNumber}</DialogTitle>
            <DialogContent>
              <Box sx={{ mt: 1, display: 'flex', flexDirection: 'column', gap: 2 }}>
                <Typography variant="h6" align="right">{t('total')}: {formatCurrency(convertOrder.totalAmount)}</Typography>
                <Tabs value={convertMethod} onChange={(e, v) => setConvertMethod(v)}>
                  <Tab value="CASH" label={t('method_cash')} />
                  <Tab value="CREDIT" label={t('method_credit')} />
                </Tabs>
                {convertMethod === 'CASH' ? (
                  <>
                    {!shift?.data && <Alert severity="warning">{t('require_open_shift')}</Alert>}
                    <TextField size="small" label={t('amount_paid')} type="number" value={convertAmount} onChange={(e) => setConvertAmount(e.target.value)} />
                  </>
                ) : (
                  <>
                    {!convertOrder.customerId && <Alert severity="warning">{t('no_customer_for_credit')}</Alert>}
                    <TextField size="small" label={t('due_date')} type="date" value={convertDueDate} onChange={(e) => setConvertDueDate(e.target.value)} InputLabelProps={{ shrink: true }} />
                  </>
                )}
                {convertError && <Alert severity="error">{convertError}</Alert>}
              </Box>
            </DialogContent>
            <DialogActions>
              <Button onClick={() => setConvertOrder(null)}>{t('cancel')}</Button>
              <Button variant="contained" color="success" onClick={handleConvert} disabled={convertMutation.isPending}>
                {t('confirm_convert')}
              </Button>
            </DialogActions>
          </>
        )}
      </Dialog>

      {/* Cancel dialog */}
      <Dialog open={!!cancelOrder} onClose={() => setCancelOrder(null)} maxWidth="xs" fullWidth>
        {cancelOrder && (
          <>
            <DialogTitle>{t('cancel_order')} — {cancelOrder.orderNumber}</DialogTitle>
            <DialogContent>
              <Typography sx={{ mb: 1 }}>{t('cancel_will_release_stock')}</Typography>
              <TextField fullWidth label={t('reason')} multiline rows={3} value={cancelReason} onChange={(e) => setCancelReason(e.target.value)} required />
            </DialogContent>
            <DialogActions>
              <Button onClick={() => setCancelOrder(null)}>{t('close')}</Button>
              <Button variant="contained" color="error" onClick={handleCancel} disabled={!cancelReason.trim() || cancelMutation.isPending}>
                {t('confirm_cancel')}
              </Button>
            </DialogActions>
          </>
        )}
      </Dialog>
    </Box>
  );
};

export default Orders;