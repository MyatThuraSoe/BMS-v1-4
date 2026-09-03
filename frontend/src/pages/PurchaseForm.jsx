import { useState, useEffect, useMemo } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  Box, Typography, TextField, Button, Grid, Paper, Alert, CircularProgress, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, IconButton, Autocomplete, Chip, MenuItem, Select, FormControl, InputLabel, Divider, Stack,
} from '@mui/material';
import { Add as AddIcon, Remove as RemoveIcon, Payments as PaymentsIcon } from '@mui/icons-material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { purchaseService, supplierService, productService } from '../api/services';
import { useAuth } from '../context/AuthContext';
import { formatCurrency, preventNumberScroll } from '../utils/helpers';

const ProductSearchField = ({ value, onSelect }) => {
  const [inputValue, setInputValue] = useState('');
  const [debounced, setDebounced] = useState('');
  const { t } = useTranslation('purchases');

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(inputValue), 300);
    return () => clearTimeout(timer);
  }, [inputValue]);

  const { data } = useQuery({
    queryKey: ['product-search', debounced],
    queryFn: () => productService.search(debounced, 0, 10),
    enabled: debounced.length >= 2,
  });
  const options = data?.data?.content || [];

  return (
    <Autocomplete
      size="small"
      options={options}
      getOptionLabel={(p) => p.name ? `${p.name} (${p.sku})` : ''}
      value={value}
      onChange={(e, selected) => onSelect(selected)}
      inputValue={inputValue}
      onInputChange={(e, newVal) => setInputValue(newVal)}
      noOptionsText={inputValue.length < 2 ? t('type_to_search') : t('no_products_found')}
      renderInput={(params) => <TextField {...params} placeholder={t('search_by_name_or_sku')} />}
      sx={{ minWidth: 220 }}
    />
  );
};

const SupplierSearchField = ({ value, onSelect }) => {
  const [inputValue, setInputValue] = useState('');
  const [debounced, setDebounced] = useState('');
  const { t } = useTranslation('purchases');

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(inputValue), 300);
    return () => clearTimeout(timer);
  }, [inputValue]);

  const { data } = useQuery({
    queryKey: ['supplier-search', debounced],
    queryFn: () => supplierService.search(debounced),
    enabled: debounced.length >= 2,
  });
  const options = data?.data?.content || [];

  const noSupplierOption = { id: null, name: t('no_supplier') };
  const showNoSupplier = !inputValue.trim();
  const displayOptions = showNoSupplier ? [noSupplierOption] : options;

  return (
    <Autocomplete
      size="small"
      options={displayOptions}
      getOptionLabel={(s) => s?.name || ''}
      isOptionEqualToValue={(option, val) => (option?.id ?? null) === (val?.id ?? null)}
      value={value}
      onChange={(e, selected) => {
        onSelect(selected);
        setInputValue(selected?.name || '');
      }}
      inputValue={inputValue}
      onInputChange={(e, newVal) => setInputValue(newVal)}
      noOptionsText={inputValue.length < 2 ? t('type_to_search') : t('no_suppliers_found')}
      clearText={t('no_supplier')}
      renderInput={(params) => <TextField {...params} label={t('supplier')} placeholder={t('search_suppliers')} />}
      sx={{ minWidth: 220 }}
    />
  );
};

const PurchaseForm = () => {
  const { id } = useParams();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { isManager } = useAuth();
  const { t } = useTranslation('purchases');
  const isEdit = !!id;

  const preselectSupplierId = searchParams.get('supplierId') || '';
  const preselectProductId = searchParams.get('productId') || '';

  const [formData, setFormData] = useState({
    purchaseDate: new Date().toISOString().split('T')[0],
    notes: ''
  });
  const [selectedSupplier, setSelectedSupplier] = useState(null);
  const [items, setItems] = useState(
    preselectProductId ? [{ productId: preselectProductId, quantity: 1, unitCost: 0, selectedProduct: null }] : []
  );
  const [discountAmount, setDiscountAmount] = useState(0);
  const [taxRate, setTaxRate] = useState(0);
  const [paymentStatus, setPaymentStatus] = useState('PENDING');
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  const { data: preselectSupplier } = useQuery({
    queryKey: ['supplier', preselectSupplierId],
    queryFn: () => supplierService.getById(preselectSupplierId),
    enabled: !!preselectSupplierId && !isEdit,
  });
  const { data: existingPurchase } = useQuery({ queryKey: ['purchase', id], queryFn: () => purchaseService.getById(id), enabled: isEdit });

  useEffect(() => {
    if (preselectSupplier?.data) {
      setSelectedSupplier(preselectSupplier.data);
    }
  }, [preselectSupplier]);

  useEffect(() => {
    if (existingPurchase?.data) {
      const p = existingPurchase.data;
      setFormData({ purchaseDate: p.purchaseDate?.split('T')[0] || '', notes: p.notes || '' });
      setSelectedSupplier(p.supplierId ? { id: p.supplierId, name: p.supplierName || '' } : null);
      setItems(p.items || []);
      setDiscountAmount(p.discountAmount || 0);
      setTaxRate(p.taxAmount || 0);
      setPaymentStatus(p.paymentStatus || 'PENDING');
    }
  }, [existingPurchase]);

  const subtotal = useMemo(
    () => items.reduce((sum, i) => sum + (Number(i.quantity) || 0) * (Number(i.unitCost) || 0), 0),
    [items]
  );

  const discount = useMemo(() => {
    const d = Number(discountAmount) || 0;
    return Math.min(d, subtotal);
  }, [discountAmount, subtotal]);

  const tax = useMemo(() => {
    const rate = Number(taxRate) || 0;
    return (subtotal - discount) * (rate / 100);
  }, [taxRate, discount, subtotal]);

  const total = useMemo(() => Math.max(0, subtotal - discount + tax), [subtotal, discount, tax]);

  const addItem = () => {
    setItems([...items, { productId: '', quantity: 1, unitCost: 0, selectedProduct: null }]);
  };

  const updateItem = (index, field, value, productObj = null) => {
    const newItems = [...items];
    if (field === 'productId') {
      newItems[index] = { ...newItems[index], productId: value, selectedProduct: productObj };
    } else {
      newItems[index] = { ...newItems[index], [field]: field === 'quantity' ? parseInt(value) || 0 : value };
    }
    setItems(newItems);
  };

  const removeItem = (index) => {
    setItems(items.filter((_, i) => i !== index));
  };

  const saveMutation = useMutation({
    mutationFn: async (data) => purchaseService.create(data),
    onSuccess: () => {
      setSuccess(t('purchase_created'));
      queryClient.invalidateQueries({ queryKey: ['purchases'] });
      queryClient.invalidateQueries({ queryKey: ['products'] });
      queryClient.invalidateQueries({ queryKey: ['low-stock'] });
      queryClient.invalidateQueries({ queryKey: ['inventoryReport'] });
      setTimeout(() => navigate('/purchases'), 1500);
    },
    onError: (err) => setError(err.response?.data?.message || t('save_failed')),
  });

  const handleSubmit = (e) => {
    e.preventDefault();
    if (isEdit) { navigate('/purchases'); return; } // existing purchases are view-only
    if (items.length === 0) { setError(t('at_least_one_item_required')); return; }
    if (!items.every((i) => i.productId && (Number(i.quantity) || 0) > 0 && (Number(i.unitCost) || 0) >= 0)) {
      setError(t('items_invalid'));
      return;
    }
    setError('');
    setSuccess('');

    // Strip the frontend-only 'selectedProduct' object before sending to the API
    const cleanItems = items.map(({ selectedProduct, ...rest }) => rest);
    saveMutation.mutate({
      ...formData,
      supplierId: selectedSupplier?.id ?? null,
      items: cleanItems,
      discountAmount: Math.round(discount * 100) / 100,
      taxAmount: Math.round(tax * 100) / 100,
      paymentStatus,
    });
  };

  if (!isManager()) return <Alert severity="error">{t('access_denied')}</Alert>;

  const statusColor = {
    PENDING: 'warning',
    PARTIAL: 'info',
    PAID: 'success',
  };

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 2, flexWrap: 'wrap', gap: 1 }}>
        <Typography variant="h4" gutterBottom sx={{ mb: 0 }}>{isEdit ? t('purchase_details') : t('add_products_to_inventory')}</Typography>
        {isEdit && <Chip size="small" label={t('purchase_number') + ' ' + (existingPurchase?.data?.purchaseNumber || '')} variant="outlined" />}
      </Box>
      {isEdit && <Alert severity="info" sx={{ mb: 2 }}>{t('purchase_view_only_info')}</Alert>}
      {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
      {success && <Alert severity="success" sx={{ mb: 2 }}>{success}</Alert>}

      <Paper sx={{ p: 3 }}>
        <form onSubmit={handleSubmit}>
          <Grid container spacing={3}>
            <Grid item xs={12} md={8}>
              <Stack spacing={2}>
                <Grid container spacing={2}>
                  <Grid item xs={12} sm={6}>
                    {isEdit ? (
                      <TextField
                        fullWidth
                        label={t('supplier')}
                        value={selectedSupplier?.name || t('no_supplier')}
                        disabled
                      />
                    ) : (
                      <SupplierSearchField
                        value={selectedSupplier}
                        onSelect={(supplier) => setSelectedSupplier(supplier)}
                      />
                    )}
                  </Grid>
                  <Grid item xs={12} sm={6}>
                    <TextField
                      fullWidth
                      label={t('date')}
                      type="date"
                      name="purchaseDate"
                      value={formData.purchaseDate}
                      onChange={(e) => setFormData({ ...formData, purchaseDate: e.target.value })}
                      InputLabelProps={{ shrink: true }}
                      required
                      disabled={isEdit}
                    />
                  </Grid>
                </Grid>

                <Box>
                  <Typography variant="h6" gutterBottom>{t('items')}</Typography>
                  <TableContainer sx={{ overflowX: 'auto' }}>
                    <Table size="small">
                      <TableHead>
                        <TableRow>
                          <TableCell>{t('product')}</TableCell>
                          <TableCell align="right">{t('quantity')}</TableCell>
                          <TableCell align="right">{t('cost_price')}</TableCell>
                          <TableCell align="right">{t('line_total')}</TableCell>
                          {!isEdit && <TableCell align="right">{t('action')}</TableCell>}
                        </TableRow>
                      </TableHead>
                      <TableBody>
                        {items.map((item, idx) => (
                          <TableRow key={idx}>
                            <TableCell>
                              {isEdit ? (
                                <Typography variant="body2">
                                  {item.productName || item.product?.name || t('product_id', { id: item.productId })}
                                </Typography>
                              ) : (
                                <ProductSearchField
                                  value={item.selectedProduct || null}
                                  onSelect={(product) => updateItem(idx, 'productId', product ? product.id : '', product)}
                                />
                              )}
                            </TableCell>
                            <TableCell align="right">
                              {isEdit ? (
                                <Typography>{item.quantity}</Typography>
                              ) : (
                                <TextField type="number" size="small" value={item.quantity} onChange={(e) => updateItem(idx, 'quantity', e.target.value)} inputProps={{ min: 1 }} onWheel={preventNumberScroll} />
                              )}
                            </TableCell>
                            <TableCell align="right">
                              {isEdit ? (
                                <Typography>{formatCurrency(item.unitCost)}</Typography>
                              ) : (
                                <TextField type="number" size="small" value={item.unitCost} onChange={(e) => updateItem(idx, 'unitCost', parseFloat(e.target.value) || 0)} inputProps={{ step: '0.01', min: 0 }} onWheel={preventNumberScroll} />
                              )}
                            </TableCell>
                            <TableCell align="right">
                              <Typography fontWeight={isEdit ? 'bold' : 'normal'}>
                                {formatCurrency((Number(item.quantity) || 0) * (Number(item.unitCost) || 0))}
                              </Typography>
                            </TableCell>
                            {!isEdit && (
                              <TableCell align="right">
                                <IconButton size="small" color="error" onClick={() => removeItem(idx)}><RemoveIcon /></IconButton>
                              </TableCell>
                            )}
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </TableContainer>
                  {!isEdit && (
                    <Button startIcon={<AddIcon />} onClick={addItem} sx={{ mt: 1 }}>{t('add_item')}</Button>
                  )}
                </Box>

                <TextField
                  fullWidth
                  label={t('notes')}
                  name="notes"
                  multiline
                  rows={2}
                  value={formData.notes}
                  onChange={(e) => setFormData({ ...formData, notes: e.target.value })}
                  disabled={isEdit}
                />
              </Stack>
            </Grid>

            <Grid item xs={12} md={4}>
              <Paper variant="outlined" sx={{ p: 2 }}>
                <Typography variant="h6" gutterBottom><PaymentsIcon sx={{ verticalAlign: 'middle', mr: 1 }} />{t('payment_summary')}</Typography>
                <Divider sx={{ mb: 2 }} />

                <Stack spacing={2} mb={2}>
                  <Grid container spacing={2}>
                    <Grid item xs={6}>
                      <TextField
                        fullWidth
                        label={t('discount')}
                        type="number"
                        value={discountAmount}
                        onChange={(e) => setDiscountAmount(e.target.value)}
                        inputProps={{ step: '0.01', min: 0 }}
                        onWheel={preventNumberScroll}
                        disabled={isEdit}
                        InputProps={{ startAdornment: <Typography variant="caption" sx={{ mr: 0.5 }}>$</Typography> }}
                      />
                    </Grid>
                    <Grid item xs={6}>
                      <TextField
                        fullWidth
                        label={t('tax_rate')}
                        type="number"
                        value={taxRate}
                        onChange={(e) => setTaxRate(e.target.value)}
                        inputProps={{ step: '0.1', min: 0 }}
                        onWheel={preventNumberScroll}
                        disabled={isEdit}
                        InputProps={{ endAdornment: <Typography variant="caption" sx={{ ml: 0.5 }}>%</Typography> }}
                      />
                    </Grid>
                  </Grid>

                  <FormControl fullWidth size="small">
                    <InputLabel>{t('payment_status')}</InputLabel>
                    <Select
                      value={paymentStatus}
                      onChange={(e) => setPaymentStatus(e.target.value)}
                      label={t('payment_status')}
                      disabled={isEdit}
                    >
                      <MenuItem value="PENDING">{t('pending')}</MenuItem>
                      <MenuItem value="PARTIAL">{t('partial')}</MenuItem>
                      <MenuItem value="PAID">{t('paid')}</MenuItem>
                    </Select>
                  </FormControl>
                </Stack>

                <Divider sx={{ my: 1 }} />

                <Stack spacing={1}>
                  <Box sx={{ display: 'flex', justifyContent: 'space-between' }}>
                    <Typography variant="body2" color="text.secondary">{t('subtotal')}</Typography>
                    <Typography variant="body1" fontWeight="bold">{formatCurrency(subtotal)}</Typography>
                  </Box>
                  <Box sx={{ display: 'flex', justifyContent: 'space-between' }}>
                    <Typography variant="body2" color="text.secondary">{t('discount')}</Typography>
                    <Typography variant="body1" color="error.main">-{formatCurrency(discount)}</Typography>
                  </Box>
                  <Box sx={{ display: 'flex', justifyContent: 'space-between' }}>
                    <Typography variant="body2" color="text.secondary">{t('tax')}</Typography>
                    <Typography variant="body1" color="text.secondary">{formatCurrency(tax)}</Typography>
                  </Box>
                  <Divider />
                  <Box sx={{ display: 'flex', justifyContent: 'space-between' }}>
                    <Typography variant="h6" fontWeight="bold">{t('grand_total')}</Typography>
                    <Typography variant="h6" fontWeight="bold">{formatCurrency(total)}</Typography>
                  </Box>
                  <Box sx={{ display: 'flex', justifyContent: 'space-between' }}>
                    <Typography variant="body2" color="text.secondary">{t('payment_status')}</Typography>
                    <Chip size="small" label={t(paymentStatus.toLowerCase())} color={statusColor[paymentStatus] || 'default'} />
                  </Box>
                </Stack>
              </Paper>
            </Grid>

            <Grid item xs={12}>
              <Button type="submit" variant="contained" disabled={saveMutation.isPending && !isEdit}>
                {saveMutation.isPending && !isEdit ? <CircularProgress size={24} /> : (isEdit ? t('close') : t('create'))}
              </Button>
              {!isEdit && (
                <Button onClick={() => navigate('/purchases')} sx={{ ml: 1 }}>{t('cancel')}</Button>
              )}
            </Grid>
          </Grid>
        </form>
      </Paper>
    </Box>
  );
};

export default PurchaseForm;
