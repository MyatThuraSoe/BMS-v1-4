import { useState, useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  Box, Typography, Table, TableBody, TableCell, TableContainer, TableHead, TableRow,
  Paper, Button, IconButton, TextField, TablePagination, Dialog, DialogTitle, DialogContent,
  DialogActions, Chip, Alert, Tabs, Tab, ToggleButton, ToggleButtonGroup, Stack,
  FormControl, Select, MenuItem, InputLabel,
} from '@mui/material';
import { Add as AddIcon, Edit as EditIcon, Delete as DeleteIcon, Search as SearchIcon, Build as AdjustIcon } from '@mui/icons-material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { productService, categoryService, inventoryService, reportService } from '../api/services';
import { formatCurrency, formatDateTime } from '../utils/helpers';
import { useAuth } from '../context/AuthContext';
import ProductImage from '../components/ProductImage';
import { useTranslation } from 'react-i18next';

const VIEW_PRESETS = [
  { value: '', key: 'all_products' },
  { value: 'most-sold', key: 'most_sold' },
  { value: 'least-sold', key: 'least_sold' },
  { value: 'low-stock', key: 'low_stock' },
];

const stockStatus = (p) => {
  if (p.stockQuantity === 0) return { key: 'out_of_stock', color: 'error' };
  if (p.stockQuantity <= (p.minStockLevel || 10)) return { key: 'low_stock', color: 'warning' };
  return { key: 'in_stock', color: 'success' };
};

const ProductsTab = () => {
  const { t } = useTranslation('inventory');
  const [searchParams, setSearchParams] = useSearchParams();
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const [search, setSearch] = useState('');
  const [debouncedSearch, setDebouncedSearch] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);
  const [selectedProduct, setSelectedProduct] = useState(null);

  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { isManager } = useAuth();
  const view = searchParams.get('view') || '';

  useEffect(() => {
    const timer = setTimeout(() => setDebouncedSearch(search), 300);
    return () => clearTimeout(timer);
  }, [search]);

  const { data: categoryData } = useQuery({
    queryKey: ['categories-filter'],
    queryFn: () => categoryService.getAll(0, 100),
  });
  const categories = categoryData?.data?.content || [];

  const { data: productsData, isLoading } = useQuery({
    queryKey: ['products', page, size, debouncedSearch, categoryId, view],
    queryFn: () => {
      if (debouncedSearch) return productService.search(debouncedSearch, page, size);
      return productService.getAll(page, size, 'createdAt', categoryId || null, view || null);
    },
  });

  const { data: lowStockData } = useQuery({
    queryKey: ['low-stock'],
    queryFn: () => inventoryService.getLowStock(10),
  });
  const lowStock = lowStockData?.data || [];

  const handleViewChange = (newView) => {
    setSearchParams(newView ? { view: newView } : {});
    setPage(0);
  };

  const deleteMutation = useMutation({
    mutationFn: (id) => productService.delete(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['products'] });
      queryClient.invalidateQueries({ queryKey: ['low-stock'] });
      setDeleteDialogOpen(false);
    },
    onError: () => setDeleteDialogOpen(false),
  });

  const products = productsData?.data?.content || [];
  const totalElements = productsData?.data?.page?.totalElements || 0;

  

  return (
    <Box>
      {lowStock.length > 0 && (
        <Alert
          severity="warning"
          sx={{ mb: 2, cursor: 'pointer' }}
          onClick={() => handleViewChange('low-stock')}
        >
          <strong>{t('low_stock_alert_title')}:</strong> {t('low_stock_below_threshold', { count: lowStock.length })} — {t('click_to_view')}
        </Alert>
      )}

      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 3, flexWrap: 'wrap', gap: 2 }}>
        <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap' }}>
          {VIEW_PRESETS.map((preset) => (
            <Chip
              key={preset.value}
              label={t(preset.key)}
              onClick={() => handleViewChange(preset.value)}
              color={view === preset.value ? 'primary' : 'default'}
              variant={view === preset.value ? 'filled' : 'outlined'}
            />
          ))}
        </Box>
        <Stack direction="row" spacing={1}>
          {isManager() && (
            <Button variant="outlined" startIcon={<AdjustIcon />} onClick={() => navigate('/inventory/adjust')}>
              {t('adjust_stock')}
            </Button>
          )}
          {isManager() && (
            <Button variant="contained" startIcon={<AddIcon />} onClick={() => navigate('/products/new')}>
              {t('add_product')}
            </Button>
          )}
        </Stack>
      </Box>

      <Paper sx={{ mb: 2, p: 2 }}>
        <Box sx={{ display: 'flex', gap: 2, alignItems: 'center', flexWrap: 'wrap' }}>
          <Box sx={{ flex: 1, minWidth: 220 }}>
            <TextField
              fullWidth
              placeholder={t('search_products')}
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              InputProps={{ startAdornment: <SearchIcon sx={{ mr: 1, color: 'text.secondary' }} /> }}
              size="small"
            />
          </Box>
          <FormControl sx={{ minWidth: 240 }} size="small">
            <InputLabel id="category-filter-label">{t('category')}</InputLabel>
            <Select
              labelId="category-filter-label"
              label={t('category')}
              value={categoryId}
              onChange={(e) => { setCategoryId(e.target.value); setPage(0); }}
            >
              <MenuItem value="">{t('all_categories')}</MenuItem>
              {categories.map((c) => <MenuItem key={c.id} value={c.id}>{c.name}</MenuItem>)}
            </Select>
          </FormControl>
        </Box>
      </Paper>

      <TableContainer component={Paper}>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>{t('no')}</TableCell>
              <TableCell>{t('image')}</TableCell>
              <TableCell>{t('name')}</TableCell>
              <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{t('sku')}</TableCell>
              <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{t('category')}</TableCell>
              <TableCell align="right">{t('price')}</TableCell>
              <TableCell align="right">{t('stock')}</TableCell>
              <TableCell align="right" sx={{ display: { xs: 'none', sm: 'table-cell' } }}>{t('threshold')}</TableCell>
              <TableCell>{t('status')}</TableCell>
              {isManager() && <TableCell align="right">{t('actions')}</TableCell>}
            </TableRow>
          </TableHead>
          <TableBody>
            {isLoading ? (
              <TableRow><TableCell colSpan={9 + (isManager() ? 1 : 0)} align="center">{t('loading')}</TableCell></TableRow>
            ) : products.length === 0 ? (
              <TableRow><TableCell colSpan={9 + (isManager() ? 1 : 0)} align="center">{t('no_products_found')}</TableCell></TableRow>
            ) : (
              products.map((product, index) => {
                const status = stockStatus(product);
                return (
                  <TableRow
                    key={product.id}
                    hover
                    onClick={() => navigate(`/products/${product.id}`)}
                    sx={{ cursor: 'pointer' }}
                  >
                    <TableCell>{page * size + index + 1}</TableCell>
                    <TableCell><ProductImage productId={product.id} hasImage={product.hasImage} size={48} /></TableCell>
                    <TableCell>{product.name}</TableCell>
                    <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{product.sku}</TableCell>
                    <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{product.categoryName || '-'}</TableCell>
                    <TableCell align="right">{formatCurrency(product.unitPrice)}</TableCell>
                    <TableCell align="right">
                      <Typography color={product.stockQuantity <= (product.minStockLevel || 10) ? 'error' : 'inherit'}>
                        {product.stockQuantity}
                      </Typography>
                    </TableCell>
                    <TableCell align="right" sx={{ display: { xs: 'none', sm: 'table-cell' } }}>{product.minStockLevel || 10}</TableCell>
                    <TableCell><Chip size="small" label={t(status.key)} color={status.color} /></TableCell>
                    {isManager() && (
                      <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                        <IconButton size="small" onClick={(e) => { e.stopPropagation(); navigate(`/products/${product.id}/edit`); }} title={t('edit')}><EditIcon /></IconButton>
                        <IconButton size="small" color="error" onClick={(e) => { e.stopPropagation(); setSelectedProduct(product); setDeleteDialogOpen(true); }} title={t('delete')}><DeleteIcon /></IconButton>
                      </TableCell>
                    )}
                  </TableRow>
                );
              })
            )}
          </TableBody>
        </Table>
        <TablePagination
          component="div"
          count={totalElements}
          page={page}
          rowsPerPage={size}
          onPageChange={(e, newPage) => setPage(newPage)}
          onRowsPerPageChange={(e) => { setSize(parseInt(e.target.value)); setPage(0); }}
          rowsPerPageOptions={[5, 10, 25]}
        />
      </TableContainer>

      <Dialog open={deleteDialogOpen} onClose={() => setDeleteDialogOpen(false)}>
        <DialogTitle>{t('confirm_delete')}</DialogTitle>
        <DialogContent>{t('delete_warning', { name: selectedProduct?.name })}</DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteDialogOpen(false)}>{t('cancel')}</Button>
          <Button onClick={() => deleteMutation.mutate(selectedProduct.id)} color="error" variant="contained">{t('delete')}</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

const DeadStockTab = () => {
  const { t } = useTranslation('inventory');
  const navigate = useNavigate();
  const [deadStockThreshold, setDeadStockThreshold] = useState(30);

  const { data: deadStockData, isLoading } = useQuery({
    queryKey: ['dead-stock', deadStockThreshold],
    queryFn: () => reportService.getDeadStock(deadStockThreshold),
  });
  const deadStock = deadStockData?.data || [];
  const totalCashTiedUp = deadStock.reduce((sum, item) => sum + (Number(item.stockValue) || 0), 0);

  return (
    <Paper sx={{ p: 3 }}>
      <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} spacing={2} sx={{ mb: 2 }}>
        <Box>
          <Typography variant="h6">{t('dead_stock_slow_moving')}</Typography>
          <Typography variant="body2" color="text.secondary">
            {t('dead_stock_description')}
          </Typography>
        </Box>
        <ToggleButtonGroup size="small" value={deadStockThreshold} exclusive onChange={(_, val) => val && setDeadStockThreshold(val)}>
          <ToggleButton value={30}>{t('days_period', { days: 30 })}</ToggleButton>
          <ToggleButton value={60}>{t('days_period', { days: 60 })}</ToggleButton>
          <ToggleButton value={90}>{t('days_period', { days: 90 })}</ToggleButton>
        </ToggleButtonGroup>
      </Stack>

      {deadStock.length > 0 && (
        <Alert severity="warning" sx={{ mb: 2 }} icon={false}>
          <strong>{t('total_cash_tied_up', { amount: formatCurrency(totalCashTiedUp) })}</strong> {t('across_products', { count: deadStock.length })}
        </Alert>
      )}

      {isLoading ? (
        <Typography color="text.secondary">{t('loading')}</Typography>
      ) : deadStock.length === 0 ? (
        <Alert severity="success">{t('no_dead_stock', { days: deadStockThreshold })}</Alert>
      ) : (
        <TableContainer sx={{ overflowX: 'auto' }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>{t('product')}</TableCell>
                <TableCell>{t('category')}</TableCell>
                <TableCell align="right">{t('stock_on_hand')}</TableCell>
                <TableCell align="right">{t('cash_tied_up')}</TableCell>
                <TableCell>{t('last_sold')}</TableCell>
                <TableCell align="right">{t('days_since_sale')}</TableCell>
                <TableCell align="center">{t('action')}</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {deadStock.map((item) => (
                <TableRow key={item.productId} hover>
                  <TableCell><Typography variant="body2" fontWeight="medium">{item.productName}</Typography></TableCell>
                  <TableCell><Chip label={item.categoryName || t('uncategorized')} size="small" variant="outlined" /></TableCell>
                  <TableCell align="right">{item.stockQuantity}</TableCell>
                  <TableCell align="right"><Typography variant="body2" color="error.main" fontWeight="medium">{formatCurrency(item.stockValue)}</Typography></TableCell>
                  <TableCell>{item.lastSoldDate ? new Date(item.lastSoldDate).toLocaleDateString() : <Chip label={t('never')} size="small" color="error" />}</TableCell>
                  <TableCell align="right">
                    {item.daysSinceLastSale != null
                      ? <Chip label={`${item.daysSinceLastSale}d`} size="small" color={item.daysSinceLastSale > 90 ? 'error' : item.daysSinceLastSale > 60 ? 'warning' : 'default'} />
                      : <Chip label={t('never_sold')} size="small" color="error" />}
                  </TableCell>
                  <TableCell align="center">
                    <Button size="small" variant="text" onClick={() => navigate(`/products/${item.productId}`)}>{t('view_product')}</Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </Paper>
  );
};

const Products = () => {
  const { t } = useTranslation('inventory');
  const [searchParams] = useSearchParams(); // <-- 1. Hook into search params
  const [tab, setTab] = useState(0);

  // <-- 2. Add useEffect to auto-select the Dead Stock tab if the URL says so
  useEffect(() => {
    const tabParam = searchParams.get('tab');
    if (tabParam === 'dead-stock') {
      setTab(1);
    }
  }, [searchParams]);

  return (
    <Box>
      
      <Tabs value={tab} onChange={(e, v) => setTab(v)} sx={{ mb: 3 }}>
        <Tab label={t('products')} />
        <Tab label={t('dead_stock')} />
      </Tabs>
      {tab === 0 ? <ProductsTab /> : <DeadStockTab />}
    </Box>
  );
};



export default Products;