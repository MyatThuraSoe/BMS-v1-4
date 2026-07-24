import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Box, Typography, Paper, TextField, Button, Grid, Alert, MenuItem, CircularProgress } from '@mui/material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { productService, inventoryService } from '../api/services';
import { useAuth } from '../context/AuthContext';

const StockAdjustment = () => {
  const { isManager } = useAuth();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [formData, setFormData] = useState({ productId: '', quantityChange: '', adjustmentType: 'ADD', reason: '' });
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  const { data: products } = useQuery({ queryKey: ['products-all'], queryFn: () => productService.getAll(0, 100) });

  const adjustMutation = useMutation({
    mutationFn: (data) =>
      inventoryService.adjustStock(data.productId, {
        productId: Number(data.productId),
        quantityChange: Number(data.quantityChange),
        reason: data.reason,
      }),
    onSuccess: () => {
      setSuccess('Stock adjusted successfully');
      queryClient.invalidateQueries(['inventory-products']);
      setTimeout(() => navigate('/inventory'), 1500);
    },
    onError: (err) => setError(err.response?.data?.message || 'Failed to adjust stock'),
  });

  const handleSubmit = (e) => {
    e.preventDefault();
    if (!formData.productId) { setError('Product is required'); return; }
    if (!formData.quantityChange || parseInt(formData.quantityChange) <= 0) { setError('Quantity must be greater than 0'); return; }
    if (!formData.reason) { setError('Reason is required'); return; }
    setError('');
    setSuccess('');
    adjustMutation.mutate(formData);
  };

  if (!isManager()) return <Alert severity="error">Access denied</Alert>;

  return (
    <Box>
      
      {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
      {success && <Alert severity="success" sx={{ mb: 2 }}>{success}</Alert>}
      <Paper sx={{ p: 3 }}>
        <form onSubmit={handleSubmit}>
          <Grid container spacing={2}>
            <Grid item xs={12} md={6}>
              <TextField fullWidth label="Product" select value={formData.productId} onChange={(e) => setFormData({ ...formData, productId: e.target.value })} required>
                <MenuItem value="">Select Product</MenuItem>
                {products?.data?.content?.map((p) => (<MenuItem key={p.id} value={p.id}>{p.name} (Stock: {p.stockQuantity})</MenuItem>))}
              </TextField>
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField fullWidth label="Adjustment Type" select value={formData.adjustmentType} onChange={(e) => {
                const val = e.target.value;
                setFormData({ ...formData, adjustmentType: val, quantityChange: val === 'REMOVE' ? (formData.quantityChange ? formData.quantityChange : '') : (formData.quantityChange ? Math.abs(formData.quantityChange) : '') });
              }} required>
                <MenuItem value="ADD">Add Stock</MenuItem>
                <MenuItem value="REMOVE">Remove Stock</MenuItem>
              </TextField>
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField 
                fullWidth 
                label="Quantity" 
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
              <TextField fullWidth label="Reason" multiline rows={3} value={formData.reason} onChange={(e) => setFormData({ ...formData, reason: e.target.value })} required />
            </Grid>
            <Grid item xs={12}>
              <Button type="submit" variant="contained" disabled={adjustMutation.isPending}>
                {adjustMutation.isPending ? <CircularProgress size={24} /> : 'Adjust Stock'}
              </Button>
              <Button onClick={() => navigate('/inventory')} sx={{ ml: 1 }}>Cancel</Button>
            </Grid>
          </Grid>
        </form>
      </Paper>
    </Box>
  );
};

export default StockAdjustment;
