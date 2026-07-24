import { useState } from 'react';
import { Box, Typography, Paper, Table, TableBody, TableCell, TableHead, TableRow, Button, Alert, TablePagination, Chip } from '@mui/material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { productService, inventoryService } from '../api/services';
import { formatDateTime } from '../utils/helpers';
import { useAuth } from '../context/AuthContext';

const VIEW_PRESETS = [
  { value: '', label: 'All Products' },
  { value: 'low-stock', label: 'Low Stock' },
  { value: 'most-sold', label: 'Most Sold' },
  { value: 'least-sold', label: 'Least Sold' },
];

const Inventory = () => {

  const [searchParams, setSearchParams] = useSearchParams();
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);

  const { isManager } = useAuth();
  const queryClient = useQueryClient();

  const view = searchParams.get('view') || '';

  const { data: productsData, isLoading } = useQuery({
    queryKey: ['inventory-products', page, size, view],
    queryFn: () => productService.getAll(page, size, 'name', null, view || null),
  });

  const { data: lowStockData } = useQuery({
    queryKey: ['low-stock'],
    queryFn: () => inventoryService.getLowStock(10),
  });

  const products = productsData?.data?.content || [];
  const totalElements = productsData?.data?.totalElements || 0;
  const lowStock = lowStockData?.data || [];

  const handleViewChange = (newView) => {
    setSearchParams(newView ? { view: newView } : {});
    setPage(0);
  };

  return (
    <Box>

      {lowStock.length > 0 && (
        <Alert severity="warning" sx={{ mb: 3 }}>
          <strong>Low Stock Alert:</strong> {lowStock.length} product(s) below threshold
        </Alert>
      )}

      <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap', mb: 2 }}>
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

      <Paper sx={{ p: 3, mb: 3 }}>
        <Typography variant="h6" gutterBottom>Current Stock</Typography>
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>No</TableCell>
              <TableCell>Product</TableCell>
              <TableCell>SKU</TableCell>
              <TableCell align="right">Stock</TableCell>
              <TableCell align="right">Threshold</TableCell>
              <TableCell>Status</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
          {isLoading ? (
            <TableRow>
              <TableCell colSpan={5} align="center">
                Loading...
              </TableCell>
            </TableRow>
          ) : products.length === 0 ? (
            <TableRow>
              <TableCell colSpan={5} align="center">
                No products found
              </TableCell>
            </TableRow>
          ) : (
            products.map((p,index) => (
              <TableRow key={p.id}>
                <TableCell>{page * size + index + 1}</TableCell>
                <TableCell>{p.name}</TableCell>
                <TableCell>{p.sku}</TableCell>
                <TableCell align="right" sx={{ color: p.stockQuantity <= (p.minStockLevel || 10) ? 'error.main' : 'inherit' }}>{p.stockQuantity}</TableCell>
                <TableCell align="right">{p.minStockLevel || 10}</TableCell>
                <TableCell>
                  {p.stockQuantity === 0 ? 'Out of Stock' : p.stockQuantity <= (p.minStockLevel || 10) ? 'Low Stock' : 'In Stock'}
                </TableCell>
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
            onPageChange={(event, newPage) => setPage(newPage)}
            onRowsPerPageChange={(event) => {
              setSize(parseInt(event.target.value, 10));
              setPage(0);
            }}
            rowsPerPageOptions={[5, 10, 25]}
          />
      </Paper>

      {isManager() && (
        <Button variant="contained" onClick={() => window.location.href = '/inventory/adjust'}>Adjust Stock</Button>
      )}
    </Box>
  );
};


export default Inventory;
