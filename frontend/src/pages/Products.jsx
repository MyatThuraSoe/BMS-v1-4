import { useState, useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  Box,
  Typography,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Paper,
  Button,
  IconButton,
  TextField,
  TablePagination,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Chip,
} from '@mui/material';
import { Add as AddIcon, Edit as EditIcon, Delete as DeleteIcon, Search as SearchIcon, Visibility as VisibilityIcon } from '@mui/icons-material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { productService, categoryService } from '../api/services';
import { formatCurrency, formatDateTime } from '../utils/helpers';
import { useAuth } from '../context/AuthContext';
import ProductImage from '../components/ProductImage';
import { FormControl, Select, MenuItem, InputLabel } from '@mui/material';

const VIEW_PRESETS = [
  { value: '', label: 'All Products' },
  { value: 'most-sold', label: 'Most Sold' },
  { value: 'least-sold', label: 'Least Sold' },
  { value: 'low-stock', label: 'Low Stock' },
];

const Products = () => {
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
      if (debouncedSearch) {
        return productService.search(debouncedSearch, page, size);
      }
      return productService.getAll(page, size, 'createdAt', categoryId || null, view || null);
    },
  });

  const handleViewChange = (newView) => {
    setSearchParams(newView ? { view: newView } : {});
    setPage(0);
  };

  const deleteMutation = useMutation({
    mutationFn: (id) => productService.delete(id),
    onSuccess: () => {
      queryClient.invalidateQueries(['products']);
      setDeleteDialogOpen(false);
    },
    onError: () => {
      setDeleteDialogOpen(false);
    },
  });

  const handleDelete = () => {
    if (selectedProduct) {
      deleteMutation.mutate(selectedProduct.id);
    }
  };

  const products = productsData?.data?.content || [];
  const totalElements = productsData?.data?.totalElements || 0;

  return (
    <Box>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 3 }}>
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
        {isManager() && (
          <Button variant="contained" startIcon={<AddIcon />} onClick={() => navigate('/products/new')}>
            Add Product
          </Button>
        )}
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
              onChange={(e) => {
                setCategoryId(e.target.value);
                setPage(0);
              }}
            >
              <MenuItem value="">All Categories</MenuItem>
              {categories.map((c) => (
                <MenuItem key={c.id} value={c.id}>
                  {c.name}
                </MenuItem>
              ))}
            </Select>
          </FormControl>
        </Box>
      </Paper>

      <TableContainer component={Paper}>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>No</TableCell>
              <TableCell>Name</TableCell>
              <TableCell>Image</TableCell>
              <TableCell>SKU</TableCell>
              <TableCell>Category</TableCell>
              <TableCell align="right">Price</TableCell>
              <TableCell align="right">Stock</TableCell>
              <TableCell>Created</TableCell>
              {isManager() && <TableCell align="right">Actions</TableCell>}
            </TableRow>
          </TableHead>
          <TableBody>
            {isLoading ? (
              <TableRow>
                <TableCell colSpan={7 + (isManager() ? 1 : 0)} align="center">Loading...</TableCell>
              </TableRow>
            ) : products.length === 0 ? (
              <TableRow>
                <TableCell colSpan={7 + (isManager() ? 1 : 0)} align="center">No products found</TableCell>
              </TableRow>
            ) : (
              products.map((product, index) => (
                <TableRow key={product.id}>
                  <TableCell>{page * size + index + 1}</TableCell>
                  <TableCell>{product.name}</TableCell>
                  <TableCell>
                    <ProductImage productId={product.id} hasImage={product.hasImage} size={48} />
                  </TableCell>
                  <TableCell>{product.sku}</TableCell>
                  <TableCell>{product.categoryName || '-'}</TableCell>
                  <TableCell align="right">{formatCurrency(product.unitPrice)}</TableCell>
                  <TableCell align="right">
                    <Typography color={product.stockQuantity <= 10 ? 'error' : 'inherit'}>
                      {product.stockQuantity}
                    </Typography>
                  </TableCell>
                  <TableCell>{formatDateTime(product.createdAt)}</TableCell>
                  {isManager() && (
                    <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                      <IconButton size="small" onClick={() => navigate(`/products/${product.id}`)} title="View Details">
                        <VisibilityIcon />
                      </IconButton>
                      <IconButton size="small" onClick={() => navigate(`/products/${product.id}/edit`)} title="Edit">
                        <EditIcon />
                      </IconButton>
                      <IconButton
                        size="small"
                        color="error"
                        onClick={() => {
                          setSelectedProduct(product);
                          setDeleteDialogOpen(true);
                        }}
                        title="Delete"
                      >
                        <DeleteIcon />
                      </IconButton>
                    </TableCell>
                  )}
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
        <TablePagination
          component="div"
          count={totalElements}
          page={page}
          rowsPerPage={size}
          onPageChange={(e, newPage) => setPage(newPage)}
          onRowsPerPageChange={(e) => {
            setSize(parseInt(e.target.value));
            setPage(0);
          }}
          rowsPerPageOptions={[5, 10, 25]}
        />
      </TableContainer>

      <Dialog open={deleteDialogOpen} onClose={() => setDeleteDialogOpen(false)}>
        <DialogTitle>Confirm Delete</DialogTitle>
        <DialogContent>
          Are you sure you want to delete "{selectedProduct?.name}"?
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteDialogOpen(false)}>Cancel</Button>
          <Button onClick={handleDelete} color="error" variant="contained">Delete</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default Products;
