import { useState, useEffect } from 'react';
import { useNavigate, useParams, useLocation } from 'react-router-dom';
import {
  Box, Typography, TextField, Button, Grid, Paper, Alert, MenuItem, CircularProgress,
  FormControlLabel, Checkbox, Dialog, DialogTitle, DialogContent, DialogActions,
} from '@mui/material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { productService, categoryService } from '../api/services';
import apiClient from '../api/apiClient';
import { useAuth } from '../context/AuthContext';
import { useTranslation } from 'react-i18next';

import ProductImage from '../components/ProductImage';
import { preventNumberScroll } from '../utils/helpers';

const STANDARD_UNITS = ['PC', 'KG', 'G', 'LB', 'L', 'ML', 'BOX', 'PACK', 'DOZEN'];
const CUSTOM_UNIT = '__custom__';

const isStandardUnit = (unit) => unit == null || unit === '' || STANDARD_UNITS.includes(unit);

const ProductForm = () => {
  const { t } = useTranslation('inventory');
  const { id } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const { isManager, isAdmin } = useAuth();
  const isEdit = !!id;

  const [formData, setFormData] = useState({
    name: '', sku: '', barcode: '', unit: '', description: '', price: '', cost: '',
    stockQuantity: '', lowStockThreshold: '10', categoryId: '', hasExpiry: false, expiryDate: '',
    binLocation: '', abcClass: 'C', weight: '',
  });
  const [version, setVersion] = useState(null); // optimistic-lock token, round-tripped on save
  const [adjustOpen, setAdjustOpen] = useState(false);
  const [adjustCostValue, setAdjustCostValue] = useState('');
  const [adjustReason, setAdjustReason] = useState('');
  const [adjustError, setAdjustError] = useState('');
  const [adjustSaving, setAdjustSaving] = useState(false);
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
      setVersion(p.version ?? null);
      setFormData({
        name: p.name || '', sku: p.sku || '', barcode: p.barcode || '', unit: p.unit || '', description: p.description || '',
        price: p.unitPrice ?? '', cost: p.costPrice ?? '', stockQuantity: p.stockQuantity ?? '', lowStockThreshold: p.minStockLevel ?? '10',
        categoryId: p.categoryId || '', hasExpiry: Boolean(p.hasExpiry), expiryDate: p.expiryDate || '',
        binLocation: p.binLocation || '', abcClass: p.abcClass || 'C', weight: p.weight ?? '',
      });
    }
  }, [existingProduct]);

  const saveMutation = useMutation({
    mutationFn: async (data) => {
      // Step 1: Create/update product with JSON data only (no images in this request)
      // Map frontend field names to backend DTO field names and coerce numbers so
      // 0 (free items, out-of-stock products) is sent as a real number, never ''.
      const jsonData = {
        ...data,
        unitPrice: Number(data.price) || 0,
        minStockLevel: Number(data.lowStockThreshold) || 0,
        barcode: data.barcode?.trim() ? data.barcode.trim() : null,
        binLocation: data.binLocation?.trim() ? data.binLocation.trim() : null,
        abcClass: data.abcClass || 'C',
        weight: Number(data.weight) || 0,
        hasExpiry: Boolean(data.hasExpiry),
        expiryDate: data.hasExpiry && data.expiryDate ? data.expiryDate : null,
      };
      delete jsonData.price;
      delete jsonData.cost;
      delete jsonData.lowStockThreshold;

      let result;
      if (isEdit) {
        // C4: update never changes cost or stock; round-trip the version so a
        // stale form is rejected instead of rolling back a receipt's data.
        delete jsonData.stockQuantity;
        jsonData.version = version;
        result = await productService.update(id, jsonData);
      } else {
        jsonData.costPrice = Number(data.cost);
        jsonData.stockQuantity = Number(data.stockQuantity) || 0;
        result = await productService.create(jsonData);
      }
      
      
      
      // Step 2: If a new image was selected, upload it (this replaces any existing image)
      if (image && result.data?.id) {
        await productService.uploadImage(result.data.id, image);
      }
        
      return result;

    },
    onSuccess: (result) => {
      if (result?.data?.version != null) {
        setVersion(result.data.version);
      }
      setSuccess(isEdit ? t('product_updated') : t('product_created'));
      queryClient.invalidateQueries({ queryKey: ['products'] });
      if (isEdit && id) {
        queryClient.invalidateQueries({ queryKey: ['product-unified-price-history', id] });
      }
      setTimeout(() => navigate(`/products${location.search}`), 1500);
    },
    onError: (err) => {
      if (err.response?.status === 409) {
        // 409 covers BOTH "changed by someone else" (optimistic lock) and
        // duplicate-value conflicts; the backend message says which one it is.
        setError(err.friendlyMessage || t('conflict_error'));
      } else {
        setError(err.response?.data?.message || t('failed_to_save_product'));
      }
    },
  });

  const handleSubmit = (e) => {
    e.preventDefault();
    setError('');
    setSuccess('');
    if (formData.hasExpiry && !formData.expiryDate) {
      setError(t('expiry_required'));
      return;
    }
    // C4: cost is required on create — never silently coerce a blank to 0.00.
    if (!isEdit) {
      const cost = Number(formData.cost);
      if (formData.cost === '' || formData.cost == null || !Number.isFinite(cost) || cost < 0) {
        setError(t('cost_required'));
        return;
      }
    }
    saveMutation.mutate(formData);
  };

  const openAdjustDialog = () => {
    setAdjustCostValue(formData.cost !== '' ? String(formData.cost) : '');
    setAdjustReason('');
    setAdjustError('');
    setAdjustOpen(true);
  };

  const handleAdjustCost = async () => {
    setAdjustError('');
    const newCost = Number(adjustCostValue);
    if (adjustCostValue === '' || !Number.isFinite(newCost) || newCost < 0) {
      setAdjustError(t('cost_required'));
      return;
    }
    if (adjustReason.trim().length < 10) {
      setAdjustError(t('adjust_reason_min'));
      return;
    }
    setAdjustSaving(true);
    try {
      const result = await productService.adjustCost(id, {
        newCost,
        reason: adjustReason.trim(),
        expectedVersion: version,
      });
      if (result?.data?.version != null) {
        setVersion(result.data.version);
      }
      if (result?.data?.costPrice != null) {
        setFormData((prev) => ({ ...prev, cost: result.data.costPrice }));
      }
      queryClient.invalidateQueries({ queryKey: ['product', id] });
      queryClient.invalidateQueries({ queryKey: ['product-unified-price-history', id] });
      queryClient.invalidateQueries({ queryKey: ['products'] });
      setAdjustOpen(false);
      setSuccess(t('cost_adjusted'));
    } catch (err) {
      if (err.response?.status === 409) {
        setAdjustError(err.friendlyMessage || t('conflict_error'));
      } else {
        setAdjustError(err.response?.data?.message || t('failed_to_save_product'));
      }
    } finally {
      setAdjustSaving(false);
    }
  };

  const handleChange = (e) => {
    setFormData({ ...formData, [e.target.name]: e.target.value });
  };

  const handleCheckboxChange = (e) => {
    const { name, checked } = e.target;
    setFormData((prev) => ({
      ...prev,
      [name]: checked,
      ...(name === 'hasExpiry' && !checked ? { expiryDate: '' } : {}),
    }));
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
      {!isEdit && <Typography variant="h4" gutterBottom>{t('add_product')}</Typography>}
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
                label={t('barcode')}
                name="barcode"
                value={formData.barcode}
                onChange={handleChange}
                placeholder={t('barcode_placeholder')}
                helperText={t('barcode_help')}
                InputProps={{ sx: { fontFamily: 'monospace' } }}
              />
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
              <TextField fullWidth label={t('price')} name="price" type="number" InputProps={{ inputProps: { step: '0.01' } }} value={formData.price} onChange={handleChange} required onWheel={preventNumberScroll} />
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField
                fullWidth
                label={t('cost')}
                name="cost"
                type="number"
                InputProps={{ inputProps: { step: '0.01', min: 0 } }}
                value={formData.cost}
                onChange={handleChange}
                onWheel={preventNumberScroll}
                required={!isEdit}
                disabled={isEdit}
                helperText={isEdit ? t('cost_readonly_help') : undefined}
              />
              {isEdit && isAdmin() && (
                <Button size="small" variant="outlined" onClick={openAdjustDialog} sx={{ mt: 1 }}>
                  {t('adjust_cost')}
                </Button>
              )}
            </Grid>
            {!isEdit && (
              <Grid item xs={12} md={6}>
                <TextField fullWidth label={t('stock_quantity')} name="stockQuantity" type="number" value={formData.stockQuantity} onChange={handleChange} onWheel={preventNumberScroll} />
              </Grid>
            )}
            <Grid item xs={12} md={6}>
              <TextField fullWidth label={t('low_stock_threshold')} name="lowStockThreshold" type="number" value={formData.lowStockThreshold} onChange={handleChange} onWheel={preventNumberScroll} />
            </Grid>
            <Grid item xs={12} md={4}>
              <TextField fullWidth label="Bin location" name="binLocation" value={formData.binLocation} onChange={handleChange} placeholder="e.g. A1-03" />
            </Grid>
            <Grid item xs={6} md={4}>
              <TextField fullWidth select label="ABC class" name="abcClass" value={formData.abcClass} onChange={handleChange}>
                <MenuItem value="A">A — fast mover</MenuItem>
                <MenuItem value="B">B — medium mover</MenuItem>
                <MenuItem value="C">C — slow mover</MenuItem>
              </TextField>
            </Grid>
            <Grid item xs={6} md={4}>
              <TextField fullWidth label="Weight per unit" name="weight" type="number" value={formData.weight} onChange={handleChange} onWheel={preventNumberScroll} InputProps={{ inputProps: { step: '0.001', min: 0 } }} helperText="Used for weight-based landed costs" />
            </Grid>
            <Grid item xs={12} md={6}>
              <FormControlLabel
                control={
                  <Checkbox
                    name="hasExpiry"
                    checked={formData.hasExpiry}
                    onChange={handleCheckboxChange}
                  />
                }
                label={t('has_expiry')}
              />
            </Grid>
            <Grid item xs={12} md={6}>
              <TextField
                fullWidth
                label={t('expiry_date')}
                name="expiryDate"
                type="date"
                value={formData.expiryDate}
                onChange={handleChange}
                disabled={!formData.hasExpiry}
                required={formData.hasExpiry}
                InputLabelProps={{ shrink: true }}
              />
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
              <Button onClick={() => navigate(`/products${location.search}`)} sx={{ ml: 1 }}>{t('cancel')}</Button>
            </Grid>
          </Grid>
        </form>
      </Paper>

      <Dialog open={adjustOpen} onClose={() => !adjustSaving && setAdjustOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>{t('adjust_cost')}</DialogTitle>
        <DialogContent>
          {adjustError && <Alert severity="error" sx={{ mb: 2 }}>{adjustError}</Alert>}
          <TextField
            fullWidth
            margin="normal"
            label={t('new_cost')}
            type="number"
            InputProps={{ inputProps: { step: '0.01', min: 0 } }}
            value={adjustCostValue}
            onChange={(e) => setAdjustCostValue(e.target.value)}
            onWheel={preventNumberScroll}
            required
          />
          <TextField
            fullWidth
            margin="normal"
            label={t('reason')}
            multiline
            rows={3}
            value={adjustReason}
            onChange={(e) => setAdjustReason(e.target.value)}
            helperText={t('adjust_reason_min')}
            required
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setAdjustOpen(false)} disabled={adjustSaving}>{t('cancel')}</Button>
          <Button onClick={handleAdjustCost} variant="contained" disabled={adjustSaving}>
            {adjustSaving ? <CircularProgress size={24} /> : t('update')}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default ProductForm;
