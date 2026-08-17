import { useState, useEffect } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Box, Typography, TextField, Button, Grid, Paper, Alert, MenuItem, CircularProgress,
} from '@mui/material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { productService, categoryService } from '../api/services';
import apiClient from '../api/apiClient';
import { useAuth } from '../context/AuthContext';
import { useTranslation } from 'react-i18next';

import ProductImage from '../components/ProductImage';

const STANDARD_UNITS = ['PC', 'KG', 'G', 'LB', 'L', 'ML', 'BOX', 'PACK', 'DOZEN'];
const CUSTOM_UNIT = '__custom__';

const isStandardUnit = (unit) => unit == null || unit === '' || STANDARD_UNITS.includes(unit);

const ProductForm = () => {
  const { t } = useTranslation('inventory');
  const { id } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { isManager } = useAuth();
  const isEdit = !!id;

  const [formData, setFormData] = useState({
    name: '', sku: '', unit: '', description: '', price: '', cost: '', stockQuantity: '', lowStockThreshold: '10', categoryId: '',
  });
  const [image, setImage] = useState(null);       // newly selected file, not yet uploaded
  const [imagePreview, setImagePreview] = useState(null); // local preview URL for the newly selected file
  const [removeExistingImage, setRemoveExistingImage] = useState(false);
  const [customUnitMode, setCustomUnitMode] = useState(false);
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
      setCustomUnitMode(!isStandardUnit(p.unit));
      setFormData({
        name: p.name || '', sku: p.sku || '', unit: p.unit || '', description: p.description || '',
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
      setSuccess(isEdit ? t('product_updated') : t('product_created'));
      queryClient.invalidateQueries({ queryKey: ['products'] });
      setTimeout(() => navigate('/products'), 1500);
    },
    onError: (err) => {
      if (err.response?.status === 409) {
        setError(t('conflict_error'));
      } else {
        setError(err.response?.data?.message || t('failed_to_save_product'));
      }
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

  const handleUnitChange = (e) => {
    const value = e.target.value;
    if (value === CUSTOM_UNIT) {
      setCustomUnitMode(true);
      setFormData((p) => ({ ...p, unit: '' }));
    } else {
      setCustomUnitMode(false);
      setFormData((p) => ({ ...p, unit: value }));
    }
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
      queryClient.invalidateQueries({ queryKey: ['product', id] });
    }
    setImage(null);
    setRemoveExistingImage(true);
    if (imagePreview) URL.revokeObjectURL(imagePreview);
    setImagePreview(null);
  };

  if (!isManager()) {
    return <Alert severity="error">{t('access_denied')}</Alert>;
  }

  return (
    <Box>
      <Typography variant="h4" gutterBottom>{isEdit ? t('edit_product') : t('add_product')}</Typography>
      {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
      {success && <Alert severity="success" sx={{ mb: 2 }}>{success}</Alert>}
      <Paper sx={{ p: 3 }}>
        <form onSubmit={handleSubmit}>
          <Grid container spacing={2}>
            <Grid item xs={12} md={6}>
              <TextField fullWidth label={t('name')} name="name" value={formData.name} onChange={handleChange} required />
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField fullWidth label={t('sku')} name="sku" value={formData.sku} onChange={handleChange} required />
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField
                fullWidth
                select
                label={t('unit')}
                name="unit"
                value={customUnitMode && formData.unit && !isStandardUnit(formData.unit) ? CUSTOM_UNIT : formData.unit}
                onChange={handleUnitChange}
              >
                <MenuItem value="">{t('not_specified')}</MenuItem>
                <MenuItem value="PC">{t('unit_piece')}</MenuItem>
                <MenuItem value="KG">{t('unit_kilogram')}</MenuItem>
                <MenuItem value="G">{t('unit_gram')}</MenuItem>
                <MenuItem value="LB">{t('unit_pound')}</MenuItem>
                <MenuItem value="L">{t('unit_liter')}</MenuItem>
                <MenuItem value="ML">{t('unit_milliliter')}</MenuItem>
                <MenuItem value="BOX">{t('unit_box')}</MenuItem>
                <MenuItem value="PACK">{t('unit_pack')}</MenuItem>
                <MenuItem value="DOZEN">{t('unit_dozen')}</MenuItem>
                <MenuItem value={CUSTOM_UNIT}>{t('custom_unit')}</MenuItem>
              </TextField>
            </Grid>
            {customUnitMode && (
              <Grid item xs={12} md={6}>
                <TextField
                  fullWidth
                  label={t('custom_unit_label')}
                  name="unit"
                  value={formData.unit}
                  onChange={handleChange}
                  placeholder={t('custom_unit_placeholder')}
                  autoFocus
                />
              </Grid>
            )}
            <Grid item xs={12} md={6}>
              <TextField fullWidth label={t('category')} name="categoryId" select value={formData.categoryId} onChange={handleChange}>
                <MenuItem value="">{t('none')}</MenuItem>
                {categories?.data?.content?.map((c) => (<MenuItem key={c.id} value={c.id}>{c.name}</MenuItem>))}
              </TextField>
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField fullWidth label={t('price')} name="price" type="number" InputProps={{ inputProps: { step: '0.01' } }} value={formData.price} onChange={handleChange} required />
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField fullWidth label={t('cost')} name="cost" type="number" InputProps={{ inputProps: { step: '0.01' } }} value={formData.cost} onChange={handleChange} />
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField fullWidth label={t('stock_quantity')} name="stockQuantity" type="number" value={formData.stockQuantity} onChange={handleChange} />
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField fullWidth label={t('low_stock_threshold')} name="lowStockThreshold" type="number" value={formData.lowStockThreshold} onChange={handleChange} />
            </Grid>
            <Grid item xs={12}>
              <TextField fullWidth label={t('description')} name="description" multiline rows={3} value={formData.description} onChange={handleChange} />
            </Grid>
            <Grid item xs={12}>
              {imagePreview ? (
                <Box component="img" src={imagePreview} alt={t('new_image_alt')} sx={{ width: 100, height: 100, objectFit: 'cover', borderRadius: 1, mb: 1 }} />
              ) : (isEdit && existingProduct?.data?.hasImage && !removeExistingImage) ? (
                <ProductImage productId={id} hasImage={true} size={100} />
              ) : null}
              {((isEdit && existingProduct?.data?.hasImage && !removeExistingImage) || image) && (
                <Button size="small" color="error" onClick={handleRemoveImage} sx={{ display: 'block', mt: 1 }}>
                  {t('remove_image')}
                </Button>
              )}
              <input
                  type="file"
                  accept="image/*"
                  onChange={handleImageChange}
              />
              <Typography variant="caption">{t('upload_product_images')}</Typography>
            </Grid>
            <Grid item xs={12}>
              <Button type="submit" variant="contained" disabled={saveMutation.isPending}>
                {saveMutation.isPending ? <CircularProgress size={24} /> : (isEdit ? t('update') : t('create'))}
              </Button>
              <Button onClick={() => navigate('/products')} sx={{ ml: 1 }}>{t('cancel')}</Button>
            </Grid>
          </Grid>
        </form>
      </Paper>
    </Box>
  );
};

export default ProductForm;
