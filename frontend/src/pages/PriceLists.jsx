import { useEffect, useState } from 'react';
import {
  Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle,
  FormControlLabel, Grid, IconButton, MenuItem, Paper, Stack, Switch, Table,
  TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, Typography,
} from '@mui/material';
import { Add as AddIcon, Delete as DeleteIcon, Edit as EditIcon } from '@mui/icons-material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { priceListService, productService } from '../api/services';
import { formatCurrency } from '../utils/helpers';
import { notifyError, notifySuccess } from '../utils/notify';

const emptyForm = {
  name: '',
  description: '',
  validFrom: '',
  validTo: '',
  active: true,
  items: [],
};

const PriceListDialog = ({ open, onClose, priceList, products }) => {
  const queryClient = useQueryClient();
  const [form, setForm] = useState(emptyForm);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!open) return;
    if (priceList) {
      setForm({
        name: priceList.name || '',
        description: priceList.description || '',
        validFrom: priceList.validFrom ? String(priceList.validFrom).slice(0, 10) : '',
        validTo: priceList.validTo ? String(priceList.validTo).slice(0, 10) : '',
        active: priceList.isActive !== false,
        items: (priceList.items || []).map((item) => ({
          productId: item.product?.id || '',
          price: item.price ?? '',
        })),
      });
    } else {
      setForm(emptyForm);
    }
    setError('');
  }, [open, priceList]);

  const update = (patch) => setForm((prev) => ({ ...prev, ...patch }));

  const mutation = useMutation({
    mutationFn: (payload) => priceList
      ? priceListService.update(priceList.id, payload)
      : priceListService.create(payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['price-lists'] });
      notifySuccess(priceList ? 'Price list updated' : 'Price list created');
      onClose();
    },
    onError: (err) => setError(err.friendlyMessage || err.response?.data?.message || 'Failed to save price list'),
  });

  const submit = () => {
    setError('');
    if (!form.name.trim()) { setError('Name is required'); return; }
    if (form.items.length === 0) { setError('Add at least one product price'); return; }
    const seen = new Set();
    for (const item of form.items) {
      if (!item.productId || item.price === '') { setError('Every line needs a product and price'); return; }
      if (seen.has(item.productId)) { setError('Duplicate product in the price list'); return; }
      seen.add(item.productId);
    }
    mutation.mutate({
      name: form.name.trim(),
      description: form.description || null,
      validFrom: form.validFrom || null,
      validTo: form.validTo || null,
      active: form.active,
      items: form.items.map((item) => ({
        productId: Number(item.productId),
        price: Number(item.price),
      })),
    });
  };

  const setItem = (index, patch) => {
    const next = [...form.items];
    next[index] = { ...next[index], ...patch };
    update({ items: next });
  };

  return (
    <Dialog open={open} onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>{priceList ? 'Edit price list' : 'New price list'}</DialogTitle>
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
            <TextField fullWidth type="date" label="Valid from" InputLabelProps={{ shrink: true }} value={form.validFrom} onChange={(e) => update({ validFrom: e.target.value })} />
          </Grid>
          <Grid item xs={12} sm={6}>
            <TextField fullWidth type="date" label="Valid to" InputLabelProps={{ shrink: true }} value={form.validTo} onChange={(e) => update({ validTo: e.target.value })} />
          </Grid>
          <Grid item xs={12}>
            <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1 }}>
              <Typography variant="subtitle2">Product prices</Typography>
              <Button size="small" startIcon={<AddIcon />} onClick={() => update({ items: [...form.items, { productId: '', price: '' }] })}>
                Add product
              </Button>
            </Stack>
            {form.items.length === 0 && (
              <Typography variant="body2" color="text.secondary">No product prices yet.</Typography>
            )}
            {form.items.map((item, index) => (
              <Stack key={index} direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ mb: 1 }}>
                <TextField
                  select
                  size="small"
                  fullWidth
                  label="Product"
                  value={item.productId}
                  onChange={(e) => setItem(index, { productId: e.target.value })}
                >
                  {products.map((product) => (
                    <MenuItem key={product.id} value={product.id}>
                      {product.name} ({product.sku}) — {formatCurrency(product.unitPrice)}
                    </MenuItem>
                  ))}
                </TextField>
                <TextField
                  size="small"
                  type="number"
                  label="Price"
                  value={item.price}
                  onChange={(e) => setItem(index, { price: e.target.value })}
                  sx={{ minWidth: 140 }}
                />
                <IconButton color="error" onClick={() => update({ items: form.items.filter((_, i) => i !== index) })}>
                  <DeleteIcon />
                </IconButton>
              </Stack>
            ))}
          </Grid>
        </Grid>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" onClick={submit} disabled={mutation.isPending}>Save</Button>
      </DialogActions>
    </Dialog>
  );
};

const PriceLists = () => {
  const queryClient = useQueryClient();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState(null);

  const { data, isLoading, error } = useQuery({ queryKey: ['price-lists'], queryFn: priceListService.getAll });
  const { data: productData } = useQuery({
    queryKey: ['price-list-products'],
    queryFn: () => productService.getAll(0, 500, 'name'),
  });

  const priceLists = data?.data || [];
  const products = productData?.data?.content || [];

  const deleteMutation = useMutation({
    mutationFn: (id) => priceListService.delete(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['price-lists'] });
      notifySuccess('Price list deactivated');
    },
    onError: (err) => notifyError(err.friendlyMessage || 'Failed to deactivate price list'),
  });

  return (
    <Box>
      <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 2 }}>
        <Box>
          <Typography variant="h5" fontWeight={600}>Price Lists</Typography>
          <Typography variant="body2" color="text.secondary">
            Contract prices that override the shelf price for assigned customers
          </Typography>
        </Box>
        <Button variant="contained" startIcon={<AddIcon />} onClick={() => { setEditing(null); setDialogOpen(true); }}>
          New price list
        </Button>
      </Stack>

      {error && <Alert severity="error" sx={{ mb: 2 }}>Failed to load price lists</Alert>}

      <Stack spacing={2}>
        {isLoading && <Paper sx={{ p: 2 }}>Loading...</Paper>}
        {!isLoading && priceLists.length === 0 && <Paper sx={{ p: 2 }}>No price lists yet.</Paper>}
        {priceLists.map((list) => (
          <Paper key={list.id} sx={{ p: 2 }}>
            <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" spacing={1} sx={{ mb: 1 }}>
              <Box>
                <Stack direction="row" spacing={1} alignItems="center">
                  <Typography variant="subtitle1" fontWeight={600}>{list.name}</Typography>
                  <Chip size="small" label={list.isActive ? 'Active' : 'Inactive'} color={list.isActive ? 'success' : 'default'} />
                </Stack>
                <Typography variant="body2" color="text.secondary">
                  {list.validFrom || '—'} → {list.validTo || '—'} · {list.items?.length || 0} product(s)
                  {list.description ? ` · ${list.description}` : ''}
                </Typography>
              </Box>
              <Stack direction="row" spacing={1}>
                <Button size="small" startIcon={<EditIcon />} onClick={() => { setEditing(list); setDialogOpen(true); }}>Edit</Button>
                <Button size="small" color="error" startIcon={<DeleteIcon />} onClick={() => deleteMutation.mutate(list.id)}>Deactivate</Button>
              </Stack>
            </Stack>
            <TableContainer>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>Product</TableCell>
                    <TableCell>SKU</TableCell>
                    <TableCell align="right">Price list</TableCell>
                    <TableCell align="right">Shelf price</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {(list.items || []).map((item) => (
                    <TableRow key={item.id}>
                      <TableCell>{item.product?.name}</TableCell>
                      <TableCell>{item.product?.sku}</TableCell>
                      <TableCell align="right">{formatCurrency(item.price)}</TableCell>
                      <TableCell align="right">{formatCurrency(item.product?.unitPrice)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          </Paper>
        ))}
      </Stack>

      <PriceListDialog
        open={dialogOpen}
        onClose={() => setDialogOpen(false)}
        priceList={editing}
        products={products}
      />
    </Box>
  );
};

export default PriceLists;
