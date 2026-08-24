import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Box, Typography, Paper, TextField, Button, Grid, Alert, MenuItem, CircularProgress, Autocomplete } from '@mui/material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { productService, inventoryService } from '../api/services';
import { useAuth } from '../context/AuthContext';

const ProductSearchField = ({ value, onSelect }) => {
  const [inputValue, setInputValue] = useState('');
  const [debounced, setDebounced] = useState('');
  const { t } = useTranslation('inventory');

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(inputValue), 300);
    return () => clearTimeout(timer);
  }, [inputValue]);

  const { data } = useQuery({
    queryKey: ['stock-adjust-product-search', debounced],
    queryFn: () => productService.search(debounced, 0, 10),
    enabled: debounced.length >= 2,
  });
  const options = data?.data?.content || [];

  return (
    <Autocomplete
      size="small"
      options={options}
      getOptionLabel={(p) => (p?.name ? t('product_with_stock', { name: p.name, stock: p.stockQuantity }) : '')}
      isOptionEqualToValue={(option, val) => option.id === val?.id}
      value={value}
      onChange={(e, selected) => onSelect(selected)}
      inputValue={inputValue}
      onInputChange={(e, newVal) => setInputValue(newVal)}
      noOptionsText={inputValue.length < 2 ? t('type_to_search') : t('no_products_found')}
      renderInput={(params) => <TextField {...params} label={t('product')} placeholder={t('search_by_name_or_sku')} />}
      sx={{ minWidth: 220 }}
    />
  );
};

const StockAdjustment = () => {
  const { isManager } = useAuth();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { t } = useTranslation('inventory');

  const [selectedProduct, setSelectedProduct] = useState(null);
  const [formData, setFormData] = useState({ productId: '', quantityChange: '', adjustmentType: 'ADD', reason: '' });
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  const adjustMutation = useMutation({
    mutationFn: (data) =>
      inventoryService.adjustStock(data.productId, {
        productId: Number(data.productId),
        quantityChange: Number(data.quantityChange),
        reason: data.reason,
      }),
    onSuccess: () => {
      setSuccess(t('stock_adjusted'));
      queryClient.invalidateQueries({ queryKey: ['inventory-products'] });
      queryClient.invalidateQueries({ queryKey: ['products'] });
      queryClient.invalidateQueries({ queryKey: ['low-stock'] });
      queryClient.invalidateQueries({ queryKey: ['inventoryReport'] });
      queryClient.invalidateQueries({ queryKey: ['inventory-summary'] });
      queryClient.invalidateQueries({ queryKey: ['stock-movements'] });
      queryClient.invalidateQueries({ queryKey: ['movement-stats'] });
      setTimeout(() => navigate(-1), 1500);
    },
    onError: (err) => setError(err.response?.data?.message || t('failed_to_adjust_stock')),
  });

  const handleSubmit = (e) => {
    e.preventDefault();
    if (!formData.productId) { setError(t('product_required')); return; }
    if (!formData.quantityChange || parseInt(formData.quantityChange) <= 0) { setError(t('quantity_must_be_positive')); return; }
    if (!formData.reason) { setError(t('reason_required')); return; }
    setError('');
    setSuccess('');
    adjustMutation.mutate(formData);
  };

  if (!isManager()) return <Alert severity="error">{t('access_denied')}</Alert>;

  return (
    <Box>
      
      {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
      {success && <Alert severity="success" sx={{ mb: 2 }}>{success}</Alert>}
      <Paper sx={{ p: 3 }}>
        <form onSubmit={handleSubmit}>
          <Grid container spacing={2}>
            <Grid item xs={12} md={6}>
              <ProductSearchField
                value={selectedProduct}
                onSelect={(product) => {
                  setSelectedProduct(product);
                  setFormData({ ...formData, productId: product ? product.id : '' });
                }}
              />
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField fullWidth label={t('adjustment_type')} select value={formData.adjustmentType} onChange={(e) => {
                const val = e.target.value;
                setFormData({ ...formData, adjustmentType: val, quantityChange: val === 'REMOVE' ? (formData.quantityChange ? formData.quantityChange : '') : (formData.quantityChange ? Math.abs(formData.quantityChange) : '') });
              }} required>
                <MenuItem value="ADD">{t('add_stock')}</MenuItem>
                <MenuItem value="REMOVE">{t('remove_stock')}</MenuItem>
              </TextField>
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField 
                fullWidth 
                label={t('quantity')} 
                type="number" 
                value={formData.quantityChange} 
                onChange={(e) => {
                  let val = e.target.value;
                  if (val === '' || val === '-') {
                    setFormData({ ...formData, quantityChange: val });
                  } else {
                    let num = parseInt(val);
                    if (formData.adjustmentType === 'REMOVE' && num > 0) {
                      num = -num;
                    } else if (formData.adjustmentType === 'ADD' && num < 0) {
                      num = Math.abs(num);
                    }
                    setFormData({ ...formData, quantityChange: num.toString() });
                  }
                }} 
                inputProps={{ min: formData.adjustmentType === 'REMOVE' ? '' : 1 }} 
                required 
              />
            </Grid>
            <Grid item xs={12}>
              <TextField fullWidth label={t('reason')} multiline rows={3} value={formData.reason} onChange={(e) => setFormData({ ...formData, reason: e.target.value })} required />
            </Grid>
            <Grid item xs={12}>
              <Button type="submit" variant="contained" disabled={adjustMutation.isPending}>
                {adjustMutation.isPending ? <CircularProgress size={24} /> : t('adjust_stock')}
              </Button>
              <Button onClick={() => navigate(-1)} sx={{ ml: 1 }}>{t('cancel')}</Button>
            </Grid>
          </Grid>
        </form>
      </Paper>
    </Box>
  );
};

export default StockAdjustment;
