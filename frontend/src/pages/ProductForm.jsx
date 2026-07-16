import { useState, useEffect } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Box, Typography, TextField, Button, Grid, Paper, Alert, MenuItem, CircularProgress,
} from '@mui/material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { productService, categoryService } from '../api/services';
import apiClient from '../api/apiClient';
import { useAuth } from '../context/AuthContext';

import ProductImage from '../components/ProductImage';

const ProductForm = () => {
  const { id } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { isManager } = useAuth();
  const isEdit = !!id;

  const [formData, setFormData] = useState({
    name: '', sku: '', barcode: '', description: '', price: '', cost: '', stockQuantity: '', lowStockThreshold: '10', categoryId: '',
  });
  const [image, setImage] = useState(null);       // newly selected file, not yet uploaded
  const [imagePreview, setImagePreview] = useState(null); // local preview URL for the newly selected file
  const [removeExistingImage, setRemoveExistingImage] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');

  const { data: categories } = useQuery({
    queryKey: ['categories'],
    queryFn: () => categoryService.getAll(),
  });

  const { data: existingProduct } = useQuery({
    queryKey: ['product', id],
    queryFn: () => productService.getById(id),
    enabled: isEdit,
  });

  useEffect(() => {
    if (existingProduct?.data) {
      const p = existingProduct.data;
      setFormData({
        name: p.name || '', sku: p.sku || '', barcode: p.barcode || '', description: p.description || '',
        price: p.unitPrice || '', cost: p.costPrice || '', stockQuantity: p.stockQuantity || '', lowStockThreshold: p.minStockLevel || '10',
        categoryId: p.categoryId || '',
      });
    }
  }, [existingProduct]);

  const saveMutation = useMutation({
    mutationFn: async (data) => {
      // Step 1: Create/update product with JSON data only (no images in this request)
      // Map frontend field names to backend DTO field names
      const jsonData = {
        ...data,
        unitPrice: data.price,
        costPrice: data.cost,
        minStockLevel: data.lowStockThreshold,
      };
      delete jsonData.price;
      delete jsonData.cost;
      delete jsonData.lowStockThreshold;
      
      let result;
      if (isEdit) {
        result = await productService.update(id, jsonData);
      } else {
        result = await productService.create(jsonData);
      }
      
      
      
      // Step 2: If a new image was selected, upload it (this replaces any existing image)
      if (image && result.data?.id) {
        await productService.uploadImage(result.data.id, image);
      }
        
      return result;

    },
    onSuccess: () => {
      setSuccess(isEdit ? 'Product updated successfully' : 'Product created successfully');
      queryClient.invalidateQueries(['products']);
      setTimeout(() => navigate('/products'), 1500);
    },
    onError: (err) => {
      setError(err.response?.data?.message || 'Failed to save product');
    },
  });

  const handleSubmit = (e) => {
    e.preventDefault();
    setError('');
    setSuccess('');
    saveMutation.mutate(formData);
  };

  const handleChange = (e) => {
    setFormData({ ...formData, [e.target.name]: e.target.value });
  };

  const handleImageChange = (e) => {
    const file = e.target.files[0];
    setImage(file);
    setRemoveExistingImage(false);
    if (imagePreview) URL.revokeObjectURL(imagePreview);
    setImagePreview(file ? URL.createObjectURL(file) : null);
  };

  const handleRemoveImage = async () => {
    if (isEdit && existingProduct?.data?.hasImage && !image) {
      await productService.deleteImage(id);
      queryClient.invalidateQueries(['product', id]);
    }
    setImage(null);
    setRemoveExistingImage(true);
    if (imagePreview) URL.revokeObjectURL(imagePreview);
    setImagePreview(null);
  };

  if (!isManager()) {
    return <Alert severity="error">Access denied</Alert>;
  }

  return (
    <Box>
      <Typography variant="h4" gutterBottom>{isEdit ? 'Edit Product' : 'Add Product'}</Typography>
      {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
      {success && <Alert severity="success" sx={{ mb: 2 }}>{success}</Alert>}
      <Paper sx={{ p: 3 }}>
        <form onSubmit={handleSubmit}>
          <Grid container spacing={2}>
            <Grid item xs={12} md={6}>
              <TextField fullWidth label="Name" name="name" value={formData.name} onChange={handleChange} required />
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField fullWidth label="SKU" name="sku" value={formData.sku} onChange={handleChange} required />
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField fullWidth label="Barcode" name="barcode" value={formData.barcode} onChange={handleChange} />
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField fullWidth label="Category" name="categoryId" select value={formData.categoryId} onChange={handleChange}>
                <MenuItem value="">None</MenuItem>
                {categories?.data?.content?.map((c) => (<MenuItem key={c.id} value={c.id}>{c.name}</MenuItem>))}
              </TextField>
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField fullWidth label="Price" name="price" type="number" InputProps={{ inputProps: { step: '0.01' } }} value={formData.price} onChange={handleChange} required />
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField fullWidth label="Cost" name="cost" type="number" InputProps={{ inputProps: { step: '0.01' } }} value={formData.cost} onChange={handleChange} />
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField fullWidth label="Stock Quantity" name="stockQuantity" type="number" value={formData.stockQuantity} onChange={handleChange} />
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField fullWidth label="Low Stock Threshold" name="lowStockThreshold" type="number" value={formData.lowStockThreshold} onChange={handleChange} />
            </Grid>
            <Grid item xs={12}>
              <TextField fullWidth label="Description" name="description" multiline rows={3} value={formData.description} onChange={handleChange} />
            </Grid>
            <Grid item xs={12}>
              {imagePreview ? (
                <Box component="img" src={imagePreview} alt="New" sx={{ width: 100, height: 100, objectFit: 'cover', borderRadius: 1, mb: 1 }} />
              ) : (isEdit && existingProduct?.data?.hasImage && !removeExistingImage) ? (
                <ProductImage productId={id} hasImage={true} size={100} />
              ) : null}
              {((isEdit && existingProduct?.data?.hasImage && !removeExistingImage) || image) && (
                <Button size="small" color="error" onClick={handleRemoveImage} sx={{ display: 'block', mt: 1 }}>
                  Remove Image
                </Button>
              )}
              <input
                  type="file"
                  accept="image/*"
                  onChange={handleImageChange}
              />
              <Typography variant="caption">Upload product images</Typography>
            </Grid>
            <Grid item xs={12}>
              <Button type="submit" variant="contained" disabled={saveMutation.isPending}>
                {saveMutation.isPending ? <CircularProgress size={24} /> : (isEdit ? 'Update' : 'Create')}
              </Button>
              <Button onClick={() => navigate('/products')} sx={{ ml: 1 }}>Cancel</Button>
            </Grid>
          </Grid>
        </form>
      </Paper>
    </Box>
  );
};

export default ProductForm;
