import { useState, useEffect } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Box, Typography, TextField, Button, Grid, Paper, Alert, CircularProgress, MenuItem, Table, TableBody, TableCell, TableHead, TableRow, IconButton,
} from '@mui/material';
import { Add as AddIcon, Remove as RemoveIcon } from '@mui/icons-material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { purchaseService, supplierService, productService } from '../api/services';
import { useAuth } from '../context/AuthContext';

const PurchaseForm = () => {
  const { id } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { isManager } = useAuth();
  const isEdit = !!id;

  const [formData, setFormData] = useState({ supplierId: '', purchaseDate: new Date().toISOString().split('T')[0], notes: '' });
  const [items, setItems] = useState([]);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  const { data: suppliers } = useQuery({ queryKey: ['suppliers-all'], queryFn: () => supplierService.getAll(0, 100) });
  const { data: products } = useQuery({ queryKey: ['products-all'], queryFn: () => productService.getAll(0, 100) });
  const { data: existingPurchase } = useQuery({ queryKey: ['purchase', id], queryFn: () => purchaseService.getById(id), enabled: isEdit });

  useEffect(() => {
    if (existingPurchase?.data) {
      const p = existingPurchase.data;
      setFormData({ supplierId: p.supplierId || '', purchaseDate: p.purchaseDate?.split('T')[0] || '', notes: p.notes || '' });
      setItems(p.items || []);
    }
  }, [existingPurchase]);

  const addItem = () => {
    setItems([...items, { productId: '', quantity: 1, unitCost: 0 }]);
  };

  const updateItem = (index, field, value) => {
    const newItems = [...items];
    newItems[index] = { ...newItems[index], [field]: field === 'quantity' ? parseInt(value) || 0 : value };
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
    saveMutation.mutate({ ...formData, items });
  };

  if (!isManager()) return <Alert severity="error">Access denied</Alert>;

  return (
    <Box>
      <Typography variant="h4" gutterBottom>{isEdit ? 'Purchase Details' : 'New Purchase'}</Typography>
      {isEdit && <Alert severity="info" sx={{ mb: 2 }}>Purchases can't be edited after creation, since stock has already been updated. Use payment status to track payment, or delete and recreate if the details were wrong.</Alert>}
      {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
      {success && <Alert severity="success" sx={{ mb: 2 }}>{success}</Alert>}
      <Paper sx={{ p: 3 }}>
        <form onSubmit={handleSubmit}>
          <Grid container spacing={2}>
            <Grid item xs={12} md={6}>
              <TextField fullWidth label="Supplier" select name="supplierId" value={formData.supplierId} onChange={(e) => setFormData({ ...formData, supplierId: e.target.value })} required>
                <MenuItem value="">Select Supplier</MenuItem>
                {suppliers?.data?.content?.map((s) => (<MenuItem key={s.id} value={s.id}>{s.name}</MenuItem>))}
              </TextField>
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField fullWidth label="Date" type="date" name="purchaseDate" value={formData.purchaseDate} onChange={(e) => setFormData({ ...formData, purchaseDate: e.target.value })} InputLabelProps={{ shrink: true }} required />
            </Grid>
            <Grid item xs={12}>
              <Typography variant="h6" gutterBottom>Items</Typography>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>Product</TableCell>
                    <TableCell align="right">Quantity</TableCell>
                    <TableCell align="right">Cost Price</TableCell>
                    <TableCell align="right">Action</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {items.map((item, idx) => (
                    <TableRow key={idx}>
                      <TableCell>
                        <TextField select size="small" value={item.productId} onChange={(e) => updateItem(idx, 'productId', e.target.value)}>
                          <MenuItem value="">Select Product</MenuItem>
                          {products?.data?.content?.map((p) => (<MenuItem key={p.id} value={p.id}>{p.name}</MenuItem>))}
                        </TextField>
                      </TableCell>
                      <TableCell align="right">
                        <TextField type="number" size="small" value={item.quantity} onChange={(e) => updateItem(idx, 'quantity', e.target.value)} inputProps={{ min: 1 }} />
                      </TableCell>
                      <TableCell align="right">
                        <TextField type="number" size="small" value={item.unitCost} onChange={(e) => updateItem(idx, 'unitCost', parseFloat(e.target.value) || 0)} inputProps={{ step: '0.01' }} />
                      </TableCell>
                      <TableCell align="right">
                        <IconButton size="small" color="error" onClick={() => removeItem(idx)}><RemoveIcon /></IconButton>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
              <Button startIcon={<AddIcon />} onClick={addItem} sx={{ mt: 1 }}>Add Item</Button>
            </Grid>
            <Grid item xs={12}>
              <TextField fullWidth label="Notes" name="notes" multiline rows={2} value={formData.notes} onChange={(e) => setFormData({ ...formData, notes: e.target.value })} />
            </Grid>
            <Grid item xs={12}>
              <Button type="submit" variant="contained" disabled={saveMutation.isPending}>
                {saveMutation.isPending ? <CircularProgress size={24} /> : (isEdit ? 'Close' : 'Create')}
              </Button>
              <Button onClick={() => navigate('/purchases')} sx={{ ml: 1 }}>Cancel</Button>
            </Grid>
          </Grid>
        </form>
      </Paper>
    </Box>
  );
};

export default PurchaseForm;
