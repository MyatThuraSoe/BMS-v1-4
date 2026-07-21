import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Box, Typography, Paper, Table, TableBody, TableCell, TableHead, TableRow, Button, Alert, TablePagination, Chip, } from '@mui/material';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { productService, inventoryService } from '../api/services';
import { formatDateTime } from '../utils/helpers';
import { useAuth } from '../context/AuthContext';


const Inventory = () => {

  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const [activeTab, setActiveTab] = useState(0);

  const navigate = useNavigate();
  const { isManager } = useAuth();
  const queryClient = useQueryClient();

  const { data: productsData, isLoading } = useQuery({
    queryKey: ['inventory-products', page, size],
    queryFn: () => productService.getAll(page, size, 'name'),
  });

  const { data: lowStockData } = useQuery({
    queryKey: ['low-stock'],
    queryFn: () => inventoryService.getLowStock(10),
  });

  const { data: reorderData, isLoading: loadingReorder } = useQuery({
    queryKey: ['reorder-suggestions'],
    queryFn: () => inventoryService.getReorderSuggestions(),
    enabled: activeTab === 1 && isManager(),
  });

  const products = productsData?.data?.content || [];
  const totalElements = productsData?.data?.totalElements || 0;
  const lowStock = lowStockData?.data || [];
  const reorderSuggestions = reorderData?.data || [];

  const handleCreatePurchaseOrder = (suggestion) => {
    // Pre-fill purchase form with supplier and product info
    const params = new URLSearchParams({
      supplierId: suggestion.lastSupplierId,
      productId: suggestion.productId,
      quantity: suggestion.suggestedReorderQuantity,
    });
    navigate(`/purchases/new?${params.toString()}`);
  };

  

  return (
    <Box>
      {lowStock.length > 0 && activeTab === 0 && (
        <Alert severity="warning" sx={{ mb: 3 }}>
          <strong>Low Stock Alert:</strong> {lowStock.length} product(s) below threshold
        </Alert>
      )}

      <Box sx={{ borderBottom: 1, borderColor: 'divider', mb: 3 }}>
        <Button
          variant={activeTab === 0 ? 'contained' : 'text'}
          onClick={() => setActiveTab(0)}
          sx={{ mr: 1 }}
        >
          Current Stock
        </Button>
        {isManager() && (
          <Button
            variant={activeTab === 1 ? 'contained' : 'text'}
            onClick={() => setActiveTab(1)}
          >
            Reorder Suggestions
          </Button>
        )}
      </Box>

      {activeTab === 0 && (
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
                <TableCell colSpan={6} align="center">
                  Loading...
                </TableCell>
              </TableRow>
            ) : products.length === 0 ? (
              <TableRow>
                <TableCell colSpan={6} align="center">
                  No products found
                </TableCell>
              </TableRow>
            ) : (
              products.map((p,index) => (
                <TableRow key={p.id}>
                  <TableCell>{page * size + index + 1}</TableCell>
                  <TableCell>{p.name}</TableCell>
                  <TableCell>{p.sku}</TableCell>
                  <TableCell align="right" sx={{ color: p.stockQuantity <= (p.lowStockThreshold || 10) ? 'error.main' : 'inherit' }}>{p.stockQuantity}</TableCell>
                  <TableCell align="right">{p.lowStockThreshold || 10}</TableCell>
                  <TableCell>
                    {p.stockQuantity === 0 ? 'Out of Stock' : p.stockQuantity <= (p.lowStockThreshold || 10) ? 'Low Stock' : 'In Stock'}
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
      )}

      {activeTab === 1 && isManager() && (
        <Paper sx={{ p: 3 }}>
          <Typography variant="h6" gutterBottom>Reorder Suggestions</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            Based on sales velocity over the last 30 days and targeting 14 days of stock coverage.
          </Typography>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Product</TableCell>
                <TableCell align="right">Current Stock</TableCell>
                <TableCell align="right">Avg Daily Sales</TableCell>
                <TableCell align="right">Days Until Stockout</TableCell>
                <TableCell align="right">Suggested Qty</TableCell>
                <TableCell>Last Supplier</TableCell>
                <TableCell align="right">Last Cost</TableCell>
                <TableCell>Action</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {loadingReorder ? (
                <TableRow>
                  <TableCell colSpan={8} align="center">Loading...</TableCell>
                </TableRow>
              ) : reorderSuggestions.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={8} align="center">No reorder suggestions available</TableCell>
                </TableRow>
              ) : (
                reorderSuggestions.map((suggestion) => (
                  <TableRow key={suggestion.productId}>
                    <TableCell>{suggestion.productName}</TableCell>
                    <TableCell align="right">
                      <Typography color={suggestion.currentStock <= suggestion.minStockLevel ? 'error' : 'inherit'}>
                        {suggestion.currentStock}
                      </Typography>
                    </TableCell>
                    <TableCell align="right">{suggestion.averageDailySales?.toFixed(1) || 'N/A'}</TableCell>
                    <TableCell align="right">
                      {suggestion.daysUntilStockout !== null ? (
                        <Chip
                          label={`${suggestion.daysUntilStockout.toFixed(1)} days`}
                          size="small"
                          color={
                            suggestion.daysUntilStockout < 7 ? 'error' :
                            suggestion.daysUntilStockout < 14 ? 'warning' : 'success'
                          }
                        />
                      ) : (
                        <Chip label="N/A" size="small" />
                      )}
                    </TableCell>
                    <TableCell align="right">
                      <Typography variant="subtitle2" fontWeight="bold">
                        {suggestion.suggestedReorderQuantity}
                      </Typography>
                    </TableCell>
                    <TableCell>{suggestion.lastSupplierName || '-'}</TableCell>
                    <TableCell align="right">
                      {suggestion.lastPurchaseUnitCost ? `$${suggestion.lastPurchaseUnitCost.toFixed(2)}` : '-'}
                    </TableCell>
                    <TableCell>
                      <Button
                        size="small"
                        variant="contained"
                        onClick={() => handleCreatePurchaseOrder(suggestion)}
                        disabled={!suggestion.lastSupplierId}
                      >
                        Create PO
                      </Button>
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </Paper>
      )}

      {isManager() && activeTab === 0 && (
        <Button variant="contained" onClick={() => window.location.href = '/inventory/adjust'}>Adjust Stock</Button>
      )}
    </Box>
  );
};


export default Inventory;
