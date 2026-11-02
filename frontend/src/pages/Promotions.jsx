import { useEffect, useState } from 'react';
import {
  Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle,
  Divider, FormControlLabel, Grid, IconButton, MenuItem, Paper, Stack, Switch,
  Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField,
  Typography,
} from '@mui/material';
import { Add as AddIcon, Delete as DeleteIcon, Edit as EditIcon } from '@mui/icons-material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { categoryService, productService, promotionService } from '../api/services';
import { notifyError, notifySuccess } from '../utils/notify';

const TYPES = ['PERCENT_DISCOUNT', 'FIXED_DISCOUNT', 'BOGO', 'TIERED', 'FLASH_SALE'];
const SCOPES = ['ALL', 'PRODUCT', 'CATEGORY'];

const emptyForm = {
  name: '',
  description: '',
  type: 'PERCENT_DISCOUNT',
  scope: 'ALL',
  productId: '',
  categoryId: '',
  buyQuantity: '',
  getQuantity: '',
  minQuantity: 1,
  discountValue: '',
  startDate: '',
  endDate: '',
  priority: 0,
  active: true,
  tiers: [],
};

const toDateTimeInput = (value) => (value ? String(value).slice(0, 16) : '');

const PromotionDialog = ({ open, onClose, promotion, products, categories }) => {
  const queryClient = useQueryClient();
  const [form, setForm] = useState(emptyForm);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!open) return;
    if (promotion) {
      setForm({
        name: promotion.name || '',
        description: promotion.description || '',
        type: promotion.type || 'PERCENT_DISCOUNT',
        scope: promotion.scope || 'ALL',
        productId: promotion.product?.id || '',
        categoryId: promotion.category?.id || '',
        buyQuantity: promotion.buyQuantity ?? '',
        getQuantity: promotion.getQuantity ?? '',
        minQuantity: promotion.minQuantity ?? 1,
        discountValue: promotion.discountValue ?? '',
        startDate: toDateTimeInput(promotion.startDate),
        endDate: toDateTimeInput(promotion.endDate),
        priority: promotion.priority ?? 0,
        active: promotion.isActive !== false,
        tiers: (promotion.tiers || []).map((tier) => ({
          minQuantity: tier.minQuantity ?? '',
          discountPercent: tier.discountPercent ?? '',
        })),
      });
    } else {
      setForm(emptyForm);
    }
    setError('');
  }, [open, promotion]);

  const update = (patch) => setForm((prev) => ({ ...prev, ...patch }));

  const mutation = useMutation({
    mutationFn: (payload) => promotion
      ? promotionService.update(promotion.id, payload)
      : promotionService.create(payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['promotions'] });
      notifySuccess(promotion ? 'Promotion updated' : 'Promotion created');
      onClose();
    },
    onError: (err) => setError(err.friendlyMessage || err.response?.data?.message || 'Failed to save promotion'),
  });

  const submit = () => {
    setError('');
    if (!form.name.trim()) { setError('Name is required'); return; }
    if (form.scope === 'PRODUCT' && !form.productId) { setError('Select a product'); return; }
    if (form.scope === 'CATEGORY' && !form.categoryId) { setError('Select a category'); return; }
    if (form.type === 'BOGO' && (!form.buyQuantity || !form.getQuantity)) {
      setError('Buy and get quantities are required for BOGO'); return;
    }
    mutation.mutate({
      name: form.name.trim(),
      description: form.description || null,
      type: form.type,
      scope: form.scope,
      productId: form.scope === 'PRODUCT' ? Number(form.productId) : null,
      categoryId: form.scope === 'CATEGORY' ? Number(form.categoryId) : null,
      buyQuantity: form.buyQuantity === '' ? null : Number(form.buyQuantity),
      getQuantity: form.getQuantity === '' ? null : Number(form.getQuantity),
      minQuantity: form.minQuantity === '' ? 1 : Number(form.minQuantity),
      discountValue: form.discountValue === '' ? 0 : Number(form.discountValue),
      startDate: form.startDate ? `${form.startDate}:00` : null,
      endDate: form.endDate ? `${form.endDate}:59` : null,
      priority: Number(form.priority) || 0,
      active: form.active,
      tiers: form.type === 'TIERED'
        ? form.tiers
            .filter((tier) => tier.minQuantity !== '' && tier.discountPercent !== '')
            .map((tier) => ({
              minQuantity: Number(tier.minQuantity),
              discountPercent: Number(tier.discountPercent),
            }))
        : [],
    });
  };

  const setTier = (index, patch) => {
    const next = [...form.tiers];
    next[index] = { ...next[index], ...patch };
    update({ tiers: next });
  };

  return (
    <Dialog open={open} onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>{promotion ? 'Edit promotion' : 'New promotion'}</DialogTitle>
      <DialogContent>
        {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
        <Grid container spacing={2} sx={{ pt: 1 }}>
          <Grid item xs={12} sm={8}>
            <TextField fullWidth label="Name" value={form.name} onChange={(e) => update({ name: e.target.value })} />
          </Grid>
          <Grid item xs={12} sm={4}>
            <FormControlLabel
              control={<Switch checked={form.active} onChange={(e) => update({ active: e.target.checked })} />}
              label="Active"
            />
          </Grid>
          <Grid item xs={12}>
            <TextField fullWidth label="Description" multiline minRows={2} value={form.description} onChange={(e) => update({ description: e.target.value })} />
          </Grid>
          <Grid item xs={12} sm={6}>
            <TextField select fullWidth label="Type" value={form.type} onChange={(e) => update({ type: e.target.value })}>
              {TYPES.map((type) => <MenuItem key={type} value={type}>{type.replaceAll('_', ' ')}</MenuItem>)}
            </TextField>
          </Grid>
          <Grid item xs={12} sm={6}>
            <TextField select fullWidth label="Scope" value={form.scope} onChange={(e) => update({ scope: e.target.value })}>
              {SCOPES.map((scope) => <MenuItem key={scope} value={scope}>{scope}</MenuItem>)}
            </TextField>
          </Grid>
          {form.scope === 'PRODUCT' && (
            <Grid item xs={12} sm={6}>
              <TextField select fullWidth label="Product" value={form.productId} onChange={(e) => update({ productId: e.target.value })}>
                {products.map((product) => (
                  <MenuItem key={product.id} value={product.id}>{product.name} ({product.sku})</MenuItem>
                ))}
              </TextField>
            </Grid>
          )}
          {form.scope === 'CATEGORY' && (
            <Grid item xs={12} sm={6}>
              <TextField select fullWidth label="Category" value={form.categoryId} onChange={(e) => update({ categoryId: e.target.value })}>
                {categories.map((category) => (
                  <MenuItem key={category.id} value={category.id}>{category.name}</MenuItem>
                ))}
              </TextField>
            </Grid>
          )}
          {['PERCENT_DISCOUNT', 'FIXED_DISCOUNT', 'FLASH_SALE'].includes(form.type) && (
            <Grid item xs={12} sm={4}>
              <TextField
                fullWidth
                type="number"
                label={form.type === 'FIXED_DISCOUNT' ? 'Discount per unit' : 'Discount percent'}
                value={form.discountValue}
                onChange={(e) => update({ discountValue: e.target.value })}
              />
            </Grid>
          )}
          <Grid item xs={6} sm={4}>
            <TextField fullWidth type="number" label="Min quantity" value={form.minQuantity} onChange={(e) => update({ minQuantity: e.target.value })} />
          </Grid>
          {form.type === 'BOGO' && (
            <>
              <Grid item xs={6} sm={4}>
                <TextField fullWidth type="number" label="Buy quantity" value={form.buyQuantity} onChange={(e) => update({ buyQuantity: e.target.value })} />
              </Grid>
              <Grid item xs={6} sm={4}>
                <TextField fullWidth type="number" label="Get quantity (free)" value={form.getQuantity} onChange={(e) => update({ getQuantity: e.target.value })} />
              </Grid>
            </>
          )}
          <Grid item xs={12} sm={4}>
            <TextField fullWidth type="number" label="Priority (higher wins)" value={form.priority} onChange={(e) => update({ priority: e.target.value })} />
          </Grid>
          <Grid item xs={12} sm={6}>
            <TextField fullWidth type="datetime-local" label="Starts" InputLabelProps={{ shrink: true }} value={form.startDate} onChange={(e) => update({ startDate: e.target.value })} />
          </Grid>
          <Grid item xs={12} sm={6}>
            <TextField fullWidth type="datetime-local" label="Ends" InputLabelProps={{ shrink: true }} value={form.endDate} onChange={(e) => update({ endDate: e.target.value })} />
          </Grid>

          {form.type === 'TIERED' && (
            <Grid item xs={12}>
              <Divider sx={{ mb: 1 }} />
              <Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ mb: 1 }}>
                <Typography variant="subtitle2">Quantity tiers</Typography>
                <Button size="small" startIcon={<AddIcon />} onClick={() => update({ tiers: [...form.tiers, { minQuantity: '', discountPercent: '' }] })}>
                  Add tier
                </Button>
              </Stack>
              {form.tiers.length === 0 && <Typography variant="body2" color="text.secondary">No tiers yet.</Typography>}
              {form.tiers.map((tier, index) => (
                <Stack key={index} direction="row" spacing={1} sx={{ mb: 1 }}>
                  <TextField
                    size="small"
                    type="number"
                    label="Min quantity"
                    value={tier.minQuantity}
                    onChange={(e) => setTier(index, { minQuantity: e.target.value })}
                  />
                  <TextField
                    size="small"
                    type="number"
                    label="Discount %"
                    value={tier.discountPercent}
                    onChange={(e) => setTier(index, { discountPercent: e.target.value })}
                  />
                  <IconButton color="error" onClick={() => update({ tiers: form.tiers.filter((_, i) => i !== index) })}>
                    <DeleteIcon />
                  </IconButton>
                </Stack>
              ))}
            </Grid>
          )}
        </Grid>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" onClick={submit} disabled={mutation.isPending}>Save</Button>
      </DialogActions>
    </Dialog>
  );
};

const Promotions = () => {
  const queryClient = useQueryClient();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState(null);

  const { data, isLoading, error } = useQuery({ queryKey: ['promotions'], queryFn: promotionService.getAll });
  const { data: productData } = useQuery({
    queryKey: ['promotion-products'],
    queryFn: () => productService.getAll(0, 500, 'name'),
  });
  const { data: categoryData } = useQuery({
    queryKey: ['promotion-categories'],
    queryFn: () => categoryService.getAll(0, 200),
  });

  const promotions = data?.data || [];
  const products = productData?.data?.content || [];
  const categories = categoryData?.data?.content || [];

  const deleteMutation = useMutation({
    mutationFn: (id) => promotionService.delete(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['promotions'] });
      notifySuccess('Promotion deactivated');
    },
    onError: (err) => notifyError(err.friendlyMessage || 'Failed to deactivate promotion'),
  });

  const openCreate = () => { setEditing(null); setDialogOpen(true); };
  const openEdit = (promotion) => { setEditing(promotion); setDialogOpen(true); };

  return (
    <Box>
      <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 2 }}>
        <Box>
          <Typography variant="h5" fontWeight={600}>Promotions</Typography>
          <Typography variant="body2" color="text.secondary">
            Percent, fixed, BOGO, tiered and flash-sale pricing applied at checkout
          </Typography>
        </Box>
        <Button variant="contained" startIcon={<AddIcon />} onClick={openCreate}>New promotion</Button>
      </Stack>

      {error && <Alert severity="error" sx={{ mb: 2 }}>Failed to load promotions</Alert>}

      <Paper>
        <TableContainer>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Name</TableCell>
                <TableCell>Type</TableCell>
                <TableCell>Scope</TableCell>
                <TableCell>Value</TableCell>
                <TableCell>Window</TableCell>
                <TableCell align="right">Priority</TableCell>
                <TableCell>Status</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {isLoading ? (
                <TableRow><TableCell colSpan={8}>Loading...</TableCell></TableRow>
              ) : promotions.length === 0 ? (
                <TableRow><TableCell colSpan={8}>No promotions yet.</TableCell></TableRow>
              ) : promotions.map((promotion) => (
                <TableRow key={promotion.id} hover>
                  <TableCell>{promotion.name}</TableCell>
                  <TableCell>{promotion.type?.replaceAll('_', ' ')}</TableCell>
                  <TableCell>
                    {promotion.scope}
                    {promotion.product ? ` · ${promotion.product.name}` : ''}
                    {promotion.category ? ` · ${promotion.category.name}` : ''}
                  </TableCell>
                  <TableCell>
                    {['PERCENT_DISCOUNT', 'TIERED', 'FLASH_SALE'].includes(promotion.type)
                      ? `${promotion.discountValue}%`
                      : promotion.type === 'FIXED_DISCOUNT' ? promotion.discountValue
                        : promotion.type === 'BOGO' ? `Buy ${promotion.buyQuantity} get ${promotion.getQuantity}` : '—'}
                  </TableCell>
                  <TableCell>
                    {promotion.startDate ? String(promotion.startDate).slice(0, 10) : '—'}
                    {' → '}
                    {promotion.endDate ? String(promotion.endDate).slice(0, 10) : '—'}
                  </TableCell>
                  <TableCell align="right">{promotion.priority}</TableCell>
                  <TableCell>
                    <Chip size="small" label={promotion.isActive ? 'Active' : 'Inactive'} color={promotion.isActive ? 'success' : 'default'} />
                  </TableCell>
                  <TableCell align="right">
                    <IconButton size="small" onClick={() => openEdit(promotion)}><EditIcon fontSize="small" /></IconButton>
                    <IconButton size="small" color="error" onClick={() => deleteMutation.mutate(promotion.id)}><DeleteIcon fontSize="small" /></IconButton>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      </Paper>

      <PromotionDialog
        open={dialogOpen}
        onClose={() => setDialogOpen(false)}
        promotion={editing}
        products={products}
        categories={categories}
      />
    </Box>
  );
};

export default Promotions;
