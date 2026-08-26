import { useParams, useNavigate } from 'react-router-dom';
import {
  Box,
  Typography,
  Paper,
  Grid,
  Button,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Chip,
  Divider,
  Alert,
} from '@mui/material';
import {
  Edit as EditIcon,
  ArrowBack as ArrowBackIcon,
  ShoppingCart as OrderIcon,
  Visibility as ViewSupplierIcon,
} from '@mui/icons-material';
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { productService, reportService } from '../api/services';
import { formatCurrency, formatDateTime } from '../utils/helpers';
import ProductImage from '../components/ProductImage';
import { useTranslation } from 'react-i18next';

const ProductDetail = () => {
  const { t } = useTranslation('inventory');
  const { id } = useParams();
  const navigate = useNavigate();
  const [showAllCostHistory, setShowAllCostHistory] = useState(false);

  const { data: productData, isLoading: productLoading } = useQuery({
    queryKey: ['product', id],
    queryFn: () => productService.getById(id),
    enabled: !!id,
  });

  const { data: suppliersData, isLoading: suppliersLoading } = useQuery({
    queryKey: ['product-suppliers', id],
    queryFn: () => productService.getSuppliers(id),
    enabled: !!id,
  });

  const { data: costHistoryData, isLoading: costHistoryLoading } = useQuery({
    queryKey: ['product-cost-history', id],
    queryFn: () => productService.getCostHistory(id),
    enabled: !!id,
  });

  const { data: priceHistoryData } = useQuery({
    queryKey: ['product-price-history', id],
    queryFn: () => productService.getPriceHistory(id),
    enabled: !!id,
  });

  const { data: topCustomersData } = useQuery({
    queryKey: ['product-top-customers', id],
    queryFn: () => productService.getTopCustomers(id),
    enabled: !!id,
  });

  const { data: salesSummaryData } = useQuery({
    queryKey: ['product-sales-summary', id],
    queryFn: () => productService.getSalesSummary(id),
    enabled: !!id,
  });

  const { data: basketData } = useQuery({
    queryKey: ['product-basket-affinity', id],
    queryFn: () => reportService.getFrequentlyBoughtWith(id, 5),
    enabled: !!id,
  });

  const product = productData?.data;
  const suppliers = suppliersData?.data || [];
  const costHistory = costHistoryData?.data || [];
  const priceHistory = priceHistoryData?.data || [];
  const topCustomers = topCustomersData?.data || [];
  const salesSummary = salesSummaryData?.data;
  const basketAffinity = basketData?.data || [];
  const displayedHistory = showAllCostHistory ? costHistory : costHistory.slice(0, 5);

  const formatDate = (dateStr) => {
    if (!dateStr) return '-';
    return new Date(dateStr).toLocaleDateString();
  };

  if (productLoading) {
    return <Typography>{t('loading_product_details')}</Typography>;
  }

  if (!product) {
    return <Typography color="error">{t('product_not_found')}</Typography>;
  }

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', mb: 3, gap: 2 }}>
        <Button startIcon={<ArrowBackIcon />} onClick={() => navigate('/products')}>
          {t('back_to_products')}
        </Button>
        <Box sx={{ flexGrow: 1 }} />
        <Button
          variant="contained"
          startIcon={<EditIcon />}
          onClick={() => navigate(`/products/${id}/edit`)}
        >
          {t('edit_product')}
        </Button>
      </Box>

      {/* Main product info */}
      <Paper sx={{ p: 3, mb: 3 }}>
        <Grid container spacing={3}>
          <Grid item xs={12} md={9}>
            <Typography variant="h5" gutterBottom fontWeight="bold">
              {product.name}
            </Typography>

            <Typography variant="body2" color="text.secondary" gutterBottom>
              {t('sku_unit', { sku: product.sku, unit: product.unit || '-' })}
            </Typography>

            {product.description && (
              <Typography
                variant="body2"
                color="text.secondary"
                sx={{ mt: 1 }}
              >
                {product.description}
              </Typography>
            )}
          </Grid>

          <Grid
            item
            xs={12}
            md={3}
            sx={{ display: 'flex', justifyContent: { xs: 'flex-start', md: 'flex-end' } }}
          >
            <ProductImage
              productId={product.id}
              hasImage={product.hasImage}
              size={220}
            />
          </Grid>
        </Grid>

        <Divider sx={{ my: 3 }} />

        <Grid container spacing={2}>
          <Grid item xs={6} md={3}>
            <Typography variant="caption" color="text.secondary">
              {t('category')}
            </Typography>
            <Typography variant="body1">
              {product.categoryName || '-'}
            </Typography>
          </Grid>

          <Grid item xs={6} md={3}>
            <Typography variant="caption" color="text.secondary">
              {t('unit_price')}
            </Typography>
            <Typography variant="body1" fontWeight="bold">
              {formatCurrency(product.unitPrice)}
            </Typography>
          </Grid>

          <Grid item xs={6} md={3}>
            <Typography variant="caption" color="text.secondary">
              {t('cost_price')}
            </Typography>
            <Typography variant="body1">
              {product.costPrice
                ? formatCurrency(product.costPrice)
                : '-'}
            </Typography>
          </Grid>

          <Grid item xs={6} md={3}>
            <Typography variant="caption" color="text.secondary">
              {t('tax_rate')}
            </Typography>
            <Typography variant="body1">
              {product.taxRate ? `${product.taxRate}%` : '0%'}
            </Typography>
          </Grid>

          <Grid item xs={6} md={3}>
            <Typography variant="caption" color="text.secondary">
              {t('stock_quantity')}
            </Typography>
            <Typography
              variant="body1"
              fontWeight="bold"
              color={
                product.stockQuantity <= (product.minStockLevel || 0)
                  ? 'error.main'
                  : 'inherit'
              }
            >
              {product.stockQuantity}
            </Typography>
          </Grid>

          <Grid item xs={6} md={3}>
            <Typography variant="caption" color="text.secondary">
              {t('min_stock_level')}
            </Typography>
            <Typography variant="body1">
              {product.minStockLevel || 0}
            </Typography>
          </Grid>

          <Grid item xs={6} md={3}>
            <Typography variant="caption" color="text.secondary">
              {t('created')}
            </Typography>
            <Typography variant="body1">
              {formatDateTime(product.createdAt)}
            </Typography>
          </Grid>

          <Grid item xs={6} md={3}>
            <Typography variant="caption" color="text.secondary">
              {t('last_updated')}
            </Typography>
            <Typography variant="body1">
              {formatDateTime(product.updatedAt)}
            </Typography>
          </Grid>
        </Grid>
      </Paper>

      {/* Section A — Sales Summary cards */}
      <Grid container spacing={2} sx={{ mb: 3 }}>
        <Grid item xs={6} md={3}>
          <Paper sx={{ p: 2 }}>
            <Typography variant="caption" color="text.secondary">{t('total_sold')}</Typography>
            <Typography variant="h5" fontWeight="bold">{salesSummary?.totalQuantitySold ?? 0}</Typography>
          </Paper>
        </Grid>
        <Grid item xs={6} md={3}>
          <Paper sx={{ p: 2 }}>
            <Typography variant="caption" color="text.secondary">{t('total_revenue')}</Typography>
            <Typography variant="h5" fontWeight="bold">{formatCurrency(salesSummary?.totalRevenue)}</Typography>
          </Paper>
        </Grid>
        <Grid item xs={6} md={3}>
          <Paper sx={{ p: 2, bgcolor: 'success.50' }}>
            <Typography variant="caption" color="text.secondary">{t('total_profit')}</Typography>
            <Typography variant="h5" fontWeight="bold" color="success.main">{formatCurrency(salesSummary?.totalProfit)}</Typography>
          </Paper>
        </Grid>
        <Grid item xs={6} md={3}>
          <Paper sx={{ p: 2 }}>
            <Typography variant="caption" color="text.secondary">{t('profit_margin')}</Typography>
            <Typography variant="h5" fontWeight="bold">
              {salesSummary?.profitMarginPercent != null ? `${salesSummary.profitMarginPercent.toFixed(1)}%` : '-'}
            </Typography>
          </Paper>
        </Grid>
      </Grid>

      {/* Purchased From */}
      <Paper sx={{ p: 3, mb: 3 }}>
        <Typography variant="h6" gutterBottom>
          {t('purchased_from')}
        </Typography>

        {suppliersLoading ? (
          <Typography variant="body2" color="text.secondary">{t('loading_supplier_history')}</Typography>
        ) : suppliers.length === 0 ? (
          <Alert severity="info" sx={{ mt: 1 }}>
            {t('no_purchase_history')}
          </Alert>
        ) : (
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>{t('supplier')}</TableCell>
                  <TableCell align="center">{t('times_purchased')}</TableCell>
                  <TableCell align="right">{t('most_recent_unit_cost')}</TableCell>
                  <TableCell align="right">{t('total_qty')}</TableCell>
                  <TableCell align="right">{t('total_spent')}</TableCell>
                  <TableCell>{t('last_purchase_date')}</TableCell>
                  <TableCell align="center">{t('actions')}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {suppliers.map((supplier) => (
                  <TableRow key={supplier.supplierId}>
                    <TableCell>
                      <Typography variant="body2" fontWeight="medium">
                        {supplier.supplierName}
                      </Typography>
                    </TableCell>
                    <TableCell align="center">
                      <Chip label={supplier.timesPurchased} size="small" variant="outlined" />
                    </TableCell>
                    <TableCell align="right">
                      {formatCurrency(supplier.mostRecentUnitCost)}
                    </TableCell>
                    <TableCell align="right">
                      {supplier.totalQuantityPurchased}
                    </TableCell>
                    <TableCell align="right">
                      {formatCurrency(supplier.totalAmountSpent)}
                    </TableCell>
                    <TableCell>
                      {formatDate(supplier.mostRecentPurchaseDate)}
                    </TableCell>
                    <TableCell align="center">
                      <Box sx={{ display: 'flex', gap: 1, justifyContent: 'center' }}>
                        <Button
                          size="small"
                          variant="outlined"
                          startIcon={<OrderIcon />}
                          onClick={() =>
                            navigate(
                              `/purchases/new?supplierId=${supplier.supplierId}&productId=${id}`
                            )
                          }
                        >
                          {t('order_more')}
                        </Button>
                        <Button
                          size="small"
                          variant="text"
                          startIcon={<ViewSupplierIcon />}
                          onClick={() => navigate(`/suppliers/${supplier.supplierId}`)}
                        >
                          {t('view_supplier')}
                        </Button>
                      </Box>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        )}
      </Paper>

      {/* Section B — Selling Price History */}
      <Paper sx={{ p: 3, mb: 3 }}>
        <Typography variant="h6" gutterBottom>Selling Price History</Typography>
        {priceHistory.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            No price changes recorded yet. This only tracks changes made from now on.
          </Typography>
        ) : (
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Date</TableCell>
                  <TableCell align="right">Old Price</TableCell>
                  <TableCell align="right">New Price</TableCell>
                  <TableCell align="right">Change</TableCell>
                  <TableCell>Changed By</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {priceHistory.map((h, idx) => {
                  const diff = h.newPrice - h.oldPrice;
                  return (
                    <TableRow key={idx}>
                      <TableCell>{formatDateTime(h.changedAt)}</TableCell>
                      <TableCell align="right">{formatCurrency(h.oldPrice)}</TableCell>
                      <TableCell align="right">{formatCurrency(h.newPrice)}</TableCell>
                      <TableCell align="right" sx={{ color: diff >= 0 ? 'success.main' : 'error.main' }}>
                        {diff >= 0 ? '+' : ''}{formatCurrency(diff)}
                      </TableCell>
                      <TableCell>{h.changedByUsername}</TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </TableContainer>
        )}
      </Paper>

      {/* Section C — Top Customers */}
      <Paper sx={{ p: 3, mb: 3 }}>
        <Typography variant="h6" gutterBottom>Customers Who Buy This Most</Typography>
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 2 }}>
          Only counts registered customers — walk-in sales and quick-typed names can't be tracked as a repeat customer.
        </Typography>
        {topCustomers.length === 0 ? (
          <Typography variant="body2" color="text.secondary">No registered-customer purchases yet.</Typography>
        ) : (
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Customer</TableCell>
                  <TableCell align="right">Quantity Bought</TableCell>
                  <TableCell align="right">Total Spent</TableCell>
                  <TableCell>Last Purchase</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {topCustomers.map((c) => (
                  <TableRow
                    key={c.customerId}
                    hover
                    sx={{ cursor: 'pointer' }}
                    onClick={() => navigate(`/customers/${c.customerId}`)}
                  >
                    <TableCell>{c.customerName}{c.phone ? ` (${c.phone})` : ''}</TableCell>
                    <TableCell align="right">{c.totalQuantityBought}</TableCell>
                    <TableCell align="right">{formatCurrency(c.totalSpent)}</TableCell>
                    <TableCell>{formatDateTime(c.lastPurchaseDate)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        )}
      </Paper>

      {/* Frequently Bought With */}
      <Paper sx={{ p: 3, mb: 3 }}>
        <Typography variant="h6" gutterBottom>
          Frequently Bought With
        </Typography>
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 2 }}>
          Products most often purchased in the same sale as this one. Count = number of shared sales (not units).
        </Typography>
        {basketAffinity.length === 0 ? (
          <Alert severity="info" sx={{ mt: 1 }}>
            No co-purchase data yet — this product hasn't appeared alongside others in enough sales.
          </Alert>
        ) : (
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Product</TableCell>
                  <TableCell align="right">Shared Sales</TableCell>
                  <TableCell align="center">Actions</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {basketAffinity.map((item) => (
                  <TableRow key={item.productId} hover>
                    <TableCell>{item.productName}</TableCell>
                    <TableCell align="right">
                      <Chip label={item.coOccurrenceCount} size="small" color="primary" variant="outlined" />
                    </TableCell>
                    <TableCell align="center">
                      <Button size="small" variant="text" onClick={() => navigate(`/products/${item.productId}`)}>
                        View
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        )}
      </Paper>

      {/* Cost History */}
      <Paper sx={{ p: 3 }}>
        <Typography variant="h6" gutterBottom>
          Cost History
        </Typography>

        {costHistoryLoading ? (
          <Typography variant="body2" color="text.secondary">Loading cost history...</Typography>
        ) : costHistory.length === 0 ? (
          <Alert severity="info" sx={{ mt: 1 }}>
            No purchase history yet for this product.
          </Alert>
        ) : (
          <>
            <TableContainer>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>Purchase Date</TableCell>
                    <TableCell>Supplier</TableCell>
                    <TableCell align="right">Quantity</TableCell>
                    <TableCell align="right">Unit Cost</TableCell>
                    <TableCell align="right">Current Selling Price</TableCell>
                    <TableCell align="right">Implied Margin %</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {displayedHistory.map((entry, idx) => (
                    <TableRow key={idx}>
                      <TableCell>{formatDate(entry.purchaseDate)}</TableCell>
                      <TableCell>{entry.supplierName}</TableCell>
                      <TableCell align="right">{entry.quantity}</TableCell>
                      <TableCell align="right">{formatCurrency(entry.unitCost)}</TableCell>
                      <TableCell align="right">{formatCurrency(entry.currentSellingPrice)}</TableCell>
                      <TableCell align="right">
                        <Typography color={entry.impliedMarginPercent >= 0 ? 'success.main' : 'error.main'}>
                          {entry.impliedMarginPercent}%
                        </Typography>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
            {costHistory.length > 5 && (
              <Button onClick={() => setShowAllCostHistory(!showAllCostHistory)} sx={{ mt: 1 }}>
                {showAllCostHistory ? 'Show Less' : `Show All (${costHistory.length} entries)`}
              </Button>
            )}
          </>
        )}
      </Paper>
    </Box>
  );
};

export default ProductDetail;
