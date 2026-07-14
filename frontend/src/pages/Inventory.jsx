import { useState } from 'react';
import { Box, Typography, Paper, Table, TableBody, TableCell, TableHead, TableRow, Button, Alert } from '@mui/material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { productService, inventoryService } from '../api/services';
import { formatDateTime } from '../utils/helpers';
import { useAuth } from '../context/AuthContext';

const Inventory = () => {
  const { isManager } = useAuth();
  const queryClient = useQueryClient();

  const { data: productsData } = useQuery({
    queryKey: ['inventory-products'],
    queryFn: () => productService.getAll(0, 100),
  });

  const { data: lowStockData } = useQuery({
    queryKey: ['low-stock'],
    queryFn: () => inventoryService.getLowStock(10),
  });

  const products = productsData?.data?.content || [];
  const lowStock = lowStockData?.data || [];

  return (
    <Box>
      <Typography variant="h4" gutterBottom>Inventory</Typography>

      {lowStock.length > 0 && (
        <Alert severity="warning" sx={{ mb: 3 }}>
          <strong>Low Stock Alert:</strong> {lowStock.length} product(s) below threshold
        </Alert>
      )}

      <Paper sx={{ p: 3, mb: 3 }}>
        <Typography variant="h6" gutterBottom>Current Stock</Typography>
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Product</TableCell>
              <TableCell>SKU</TableCell>
              <TableCell align="right">Stock</TableCell>
              <TableCell align="right">Threshold</TableCell>
              <TableCell>Status</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {products.map((p) => (
              <TableRow key={p.id}>
                <TableCell>{p.name}</TableCell>
                <TableCell>{p.sku}</TableCell>
                <TableCell align="right" sx={{ color: p.stockQuantity <= (p.lowStockThreshold || 10) ? 'error.main' : 'inherit' }}>{p.stockQuantity}</TableCell>
                <TableCell align="right">{p.lowStockThreshold || 10}</TableCell>
                <TableCell>
                  {p.stockQuantity === 0 ? 'Out of Stock' : p.stockQuantity <= (p.lowStockThreshold || 10) ? 'Low Stock' : 'In Stock'}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Paper>

      {isManager() && (
        <Button variant="contained" onClick={() => window.location.href = '/inventory/adjust'}>Adjust Stock</Button>
      )}
    </Box>
  );
};

export default Inventory;
