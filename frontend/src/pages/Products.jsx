import { useState, useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  Box, Typography, Table, TableBody, TableCell, TableContainer, TableHead, TableRow,
  Paper, Button, IconButton, TextField, TablePagination, Dialog, DialogTitle, DialogContent,
  DialogActions, Chip, Alert, Tabs, Tab, ToggleButton, ToggleButtonGroup, Stack,
  FormControl, Select, MenuItem, InputLabel,
} from '@mui/material';
import { Add as AddIcon, Edit as EditIcon, Delete as DeleteIcon, Search as SearchIcon, Visibility as VisibilityIcon, Build as AdjustIcon } from '@mui/icons-material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { productService, categoryService, inventoryService, reportService } from '../api/services';
import { formatCurrency, formatDateTime } from '../utils/helpers';
import { useAuth } from '../context/AuthContext';
import ProductImage from '../components/ProductImage';

const VIEW_PRESETS = [
  { value: '', label: 'All Products' },
  { value: 'most-sold', label: 'Most Sold' },
  { value: 'least-sold', label: 'Least Sold' },
  { value: 'low-stock', label: 'Low Stock' },
];

const stockStatus = (p) => {
  if (p.stockQuantity === 0) return { label: 'Out of Stock', color: 'error' };
  if (p.stockQuantity <= (p.minStockLevel || 10)) return { label: 'Low Stock', color: 'warning' };
  return { label: 'In Stock', color: 'success' };
};

const ProductsTab = () => {
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
      queryClient.invalidateQueries(['products']);
      queryClient.invalidateQueries(['low-stock']);
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
          <strong>Low Stock Alert:</strong> {lowStock.length} product(s) below threshold — click to view
        </Alert>
      )}

      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 3, flexWrap: 'wrap', gap: 2 }}>
        <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap' }}>
          {VIEW_PRESETS.map((preset) => (
            <Chip
              key={preset.value}
              label={preset.label}
              onClick={() => handleViewChange(preset.value)}
              color={view === preset.value ? 'primary' : 'default'}
              variant={view === preset.value ? 'filled' : 'outlined'}
            />
          ))}
        </Box>
        <Stack direction="row" spacing={1}>
          {isManager() && (
            <Button variant="outlined" startIcon={<AdjustIcon />} onClick={() => navigate('/inventory/adjust')}>
              Adjust Stock
            </Button>
          )}
          {isManager() && (
            <Button variant="contained" startIcon={<AddIcon />} onClick={() => navigate('/products/new')}>
              Add Product
            </Button>
          )}
        </Stack>
      </Box>

      <Paper sx={{ mb: 2, p: 2 }}>
        <Box sx={{ display: 'flex', gap: 2, alignItems: 'center', flexWrap: 'wrap' }}>
          <Box sx={{ flex: 1, minWidth: 220 }}>
            <TextField
              fullWidth
              placeholder="Search products..."
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              InputProps={{ startAdornment: <SearchIcon sx={{ mr: 1, color: 'text.secondary' }} /> }}
              size="small"
            />
          </Box>
          <FormControl sx={{ minWidth: 240 }} size="small">
            <InputLabel id="category-filter-label">Category</InputLabel>
            <Select
              labelId="category-filter-label"
              label="Category"
              value={categoryId}
              onChange={(e) => { setCategoryId(e.target.value); setPage(0); }}
            >
              <MenuItem value="">All Categories</MenuItem>
              {categories.map((c) => <MenuItem key={c.id} value={c.id}>{c.name}</MenuItem>)}
            </Select>
          </FormControl>
        </Box>
      </Paper>

      <TableContainer component={Paper}>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>No</TableCell>
              <TableCell>Image</TableCell>
              <TableCell>Name</TableCell>
              <TableCell>SKU</TableCell>
              <TableCell>Category</TableCell>
              <TableCell align="right">Price</TableCell>
              <TableCell align="right">Stock</TableCell>
              <TableCell align="right">Threshold</TableCell>
              <TableCell>Status</TableCell>
              {isManager() && <TableCell align="right">Actions</TableCell>}
            </TableRow>
          </TableHead>
          <TableBody>
            {isLoading ? (
              <TableRow><TableCell colSpan={9 + (isManager() ? 1 : 0)} align="center">Loading...</TableCell></TableRow>
            ) : products.length === 0 ? (
              <TableRow><TableCell colSpan={9 + (isManager() ? 1 : 0)} align="center">No products found</TableCell></TableRow>
            ) : (
              products.map((product, index) => {
                const status = stockStatus(product);
                return (
                  <TableRow key={product.id}>
                    <TableCell>{page * size + index + 1}</TableCell>
                    <TableCell><ProductImage productId={product.id} hasImage={product.hasImage} size={48} /></TableCell>
                    <TableCell>{product.name}</TableCell>
                    <TableCell>{product.sku}</TableCell>
                    <TableCell>{product.categoryName || '-'}</TableCell>
                    <TableCell align="right">{formatCurrency(product.unitPrice)}</TableCell>
                    <TableCell align="right">
                      <Typography color={product.stockQuantity <= (product.minStockLevel || 10) ? 'error' : 'inherit'}>
                        {product.stockQuantity}
                      </Typography>
                    </TableCell>
                    <TableCell align="right">{product.minStockLevel || 10}</TableCell>
                    <TableCell><Chip size="small" label={status.label} color={status.color} /></TableCell>
                    {isManager() && (
                      <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                        <IconButton size="small" onClick={() => navigate(`/products/${product.id}`)} title="View Details"><VisibilityIcon /></IconButton>
                        <IconButton size="small" onClick={() => navigate(`/products/${product.id}/edit`)} title="Edit"><EditIcon /></IconButton>
                        <IconButton size="small" color="error" onClick={() => { setSelectedProduct(product); setDeleteDialogOpen(true); }} title="Delete"><DeleteIcon /></IconButton>
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
        <DialogTitle>Confirm Delete</DialogTitle>
        <DialogContent>Are you sure you want to delete "{selectedProduct?.name}"?</DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteDialogOpen(false)}>Cancel</Button>
          <Button onClick={() => deleteMutation.mutate(selectedProduct.id)} color="error" variant="contained">Delete</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

const DeadStockTab = () => {
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
          <Typography variant="h6">Dead Stock / Slow-Moving Inventory</Typography>
          <Typography variant="body2" color="text.secondary">
            Products with stock on hand that haven't sold within the threshold period.
          </Typography>
        </Box>
        <ToggleButtonGroup size="small" value={deadStockThreshold} exclusive onChange={(_, val) => val && setDeadStockThreshold(val)}>
          <ToggleButton value={30}>30 days</ToggleButton>
          <ToggleButton value={60}>60 days</ToggleButton>
          <ToggleButton value={90}>90 days</ToggleButton>
        </ToggleButtonGroup>
      </Stack>

      {deadStock.length > 0 && (
        <Alert severity="warning" sx={{ mb: 2 }} icon={false}>
          <strong>Total cash tied up in slow-moving stock: {formatCurrency(totalCashTiedUp)}</strong> across {deadStock.length} product(s)
        </Alert>
      )}

      {isLoading ? (
        <Typography color="text.secondary">Loading...</Typography>
      ) : deadStock.length === 0 ? (
        <Alert severity="success">No dead stock found for the last {deadStockThreshold} days — all products with stock have recent sales activity.</Alert>
      ) : (
        <TableContainer sx={{ overflowX: 'auto' }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Product</TableCell>
                <TableCell>Category</TableCell>
                <TableCell align="right">Stock on Hand</TableCell>
                <TableCell align="right">Cash Tied Up</TableCell>
                <TableCell>Last Sold</TableCell>
                <TableCell align="right">Days Since Sale</TableCell>
                <TableCell align="center">Action</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {deadStock.map((item) => (
                <TableRow key={item.productId} hover>
                  <TableCell><Typography variant="body2" fontWeight="medium">{item.productName}</Typography></TableCell>
                  <TableCell><Chip label={item.categoryName || 'Uncategorized'} size="small" variant="outlined" /></TableCell>
                  <TableCell align="right">{item.stockQuantity}</TableCell>
                  <TableCell align="right"><Typography variant="body2" color="error.main" fontWeight="medium">{formatCurrency(item.stockValue)}</Typography></TableCell>
                  <TableCell>{item.lastSoldDate ? new Date(item.lastSoldDate).toLocaleDateString() : <Chip label="Never" size="small" color="error" />}</TableCell>
                  <TableCell align="right">
                    {item.daysSinceLastSale != null
                      ? <Chip label={`${item.daysSinceLastSale}d`} size="small" color={item.daysSinceLastSale > 90 ? 'error' : item.daysSinceLastSale > 60 ? 'warning' : 'default'} />
                      : <Chip label="Never sold" size="small" color="error" />}
                  </TableCell>
                  <TableCell align="center">
                    <Button size="small" variant="text" onClick={() => navigate(`/products/${item.productId}`)}>View Product</Button>
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
      <Typography variant="h4" gutterBottom>Products & Inventory</Typography>
      <Tabs value={tab} onChange={(e, v) => setTab(v)} sx={{ mb: 3 }}>
        <Tab label="Products" />
        <Tab label="Dead Stock" />
      </Tabs>
      {tab === 0 ? <ProductsTab /> : <DeadStockTab />}
    </Box>
  );
};



export default Products;