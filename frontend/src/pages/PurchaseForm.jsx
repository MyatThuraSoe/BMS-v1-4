import { useState, useEffect } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import {
  Box, Typography, TextField, Button, Grid, Paper, Alert, CircularProgress, MenuItem, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, IconButton, Autocomplete,
} from '@mui/material';
import { Add as AddIcon, Remove as RemoveIcon } from '@mui/icons-material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { purchaseService, supplierService, productService } from '../api/services';
import { useAuth } from '../context/AuthContext';
import { formatCurrency } from '../utils/helpers';

const ProductSearchField = ({ value, onSelect }) => {
  const [inputValue, setInputValue] = useState('');
  const [debounced, setDebounced] = useState('');

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
      noOptionsText={inputValue.length < 2 ? 'Type to search...' : 'No products found'}
      renderInput={(params) => <TextField {...params} placeholder="Search by name or SKU" />}
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
  const isEdit = !!id;

  const preselectSupplierId = searchParams.get('supplierId') || '';
  const preselectProductId = searchParams.get('productId') || '';

  const [formData, setFormData] = useState({ 
    supplierId: preselectSupplierId, 
    purchaseDate: new Date().toISOString().split('T')[0], 
    notes: '' 
  });
  const [items, setItems] = useState(
    preselectProductId ? [{ productId: preselectProductId, quantity: 1, unitCost: 0, selectedProduct: null }] : []
  );
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  const { data: suppliers } = useQuery({ queryKey: ['suppliers-all'], queryFn: () => supplierService.getAll(0, 100) });
  const { data: existingPurchase } = useQuery({ queryKey: ['purchase', id], queryFn: () => purchaseService.getById(id), enabled: isEdit });

  useEffect(() => {
    if (existingPurchase?.data) {
      const p = existingPurchase.data;
      setFormData({ supplierId: p.supplierId || '', purchaseDate: p.purchaseDate?.split('T')[0] || '', notes: p.notes || '' });
      setItems(p.items || []);
    }
  }, [existingPurchase]);

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
      setSuccess('Purchase created');
      queryClient.invalidateQueries(['purchases']);
      setTimeout(() => navigate('/purchases'), 1500);
    },
    onError: (err) => setError(err.response?.data?.message || 'Failed to save'),
  });

  const handleSubmit = (e) => {
    e.preventDefault();
    if (isEdit) { navigate('/purchases'); return; } // existing purchases are view-only
    if (!formData.supplierId) { setError('Supplier is required'); return; }
    if (items.length === 0) { setError('At least one item is required'); return; }
    setError('');
    setSuccess('');
    
    // Strip the frontend-only 'selectedProduct' object before sending to the API
    const cleanItems = items.map(({ selectedProduct, ...rest }) => rest);
    saveMutation.mutate({ ...formData, items: cleanItems });
  };

  if (!isManager()) return <Alert severity="error">Access denied</Alert>;

  return (
    <Box>
      <Typography variant="h4" gutterBottom>{isEdit ? 'Purchase Details' : 'Adding Products to Inventory'}</Typography>
      {isEdit && <Alert severity="info" sx={{ mb: 2 }}>Purchases can't be edited after creation, since stock has already been updated. Use payment status to track payment, or delete and recreate if the details were wrong.</Alert>}
      {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
      {success && <Alert severity="success" sx={{ mb: 2 }}>{success}</Alert>}
      <Paper sx={{ p: 3 }}>
        <form onSubmit={handleSubmit}>
          <Grid container spacing={2}>
            <Grid item xs={12} md={6}>
              <TextField 
                fullWidth 
                label="Supplier" 
                select 
                name="supplierId" 
                value={formData.supplierId} 
                onChange={(e) => setFormData({ ...formData, supplierId: e.target.value })} 
                required 
                disabled={isEdit}
              >
                <MenuItem value="">No Supplier</MenuItem>
                {suppliers?.data?.content?.map((s) => (<MenuItem key={s.id} value={s.id}>{s.name}</MenuItem>))}
              </TextField>
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField 
                fullWidth 
                label="Date" 
                type="date" 
                name="purchaseDate" 
                value={formData.purchaseDate} 
                onChange={(e) => setFormData({ ...formData, purchaseDate: e.target.value })} 
                InputLabelProps={{ shrink: true }} 
                required 
                disabled={isEdit}
              />
            </Grid>
            <Grid item xs={12}>
              <Typography variant="h6" gutterBottom>Items</Typography>
              <TableContainer sx={{ overflowX: 'auto' }}>
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell>Product</TableCell>
                      <TableCell align="right">Quantity</TableCell>
                      <TableCell align="right">Cost Price</TableCell>
                      <TableCell align="right">Line Total</TableCell>
                      {!isEdit && <TableCell align="right">Action</TableCell>}
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {items.map((item, idx) => (
                      <TableRow key={idx}>
                        <TableCell>
                          {isEdit ? (
                            // Read-only view for existing purchase
                            <Typography variant="body2">
                              {item.productName || item.product?.name || `Product ID: ${item.productId}`}
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
                            <TextField type="number" size="small" value={item.quantity} onChange={(e) => updateItem(idx, 'quantity', e.target.value)} inputProps={{ min: 1 }} />
                          )}
                        </TableCell>
                        <TableCell align="right">
                          {isEdit ? (
                            <Typography>{formatCurrency(item.unitCost)}</Typography>
                          ) : (
                            <TextField type="number" size="small" value={item.unitCost} onChange={(e) => updateItem(idx, 'unitCost', parseFloat(e.target.value) || 0)} inputProps={{ step: '0.01' }} />
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
                    {isEdit && (
                      <TableRow>
                        <TableCell colSpan={3} align="right">
                          <Typography variant="h6" fontWeight="bold">Grand Total:</Typography>
                        </TableCell>
                        <TableCell align="right">
                          <Typography variant="h6" fontWeight="bold">
                            {formatCurrency(items.reduce((sum, i) => sum + (Number(i.quantity) * Number(i.unitCost)), 0))}
                          </Typography>
                        </TableCell>
                      </TableRow>
                    )}
                  </TableBody>
                </Table>
              </TableContainer>
              {!isEdit && (
                <Button startIcon={<AddIcon />} onClick={addItem} sx={{ mt: 1 }}>Add Item</Button>
              )}
            </Grid>
            <Grid item xs={12}>
              <TextField 
                fullWidth 
                label="Notes" 
                name="notes" 
                multiline 
                rows={2} 
                value={formData.notes} 
                onChange={(e) => setFormData({ ...formData, notes: e.target.value })} 
                disabled={isEdit}
              />
            </Grid>
            <Grid item xs={12}>
              <Button type="submit" variant="contained" disabled={saveMutation.isPending && !isEdit}>
                {saveMutation.isPending && !isEdit ? <CircularProgress size={24} /> : (isEdit ? 'Close' : 'Create')}
              </Button>
              {!isEdit && (
                <Button onClick={() => navigate('/purchases')} sx={{ ml: 1 }}>Cancel</Button>
              )}
            </Grid>
          </Grid>
        </form>
      </Paper>
    </Box>
  );
};

export default PurchaseForm;