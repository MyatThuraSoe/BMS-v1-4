import { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Box, Typography, Grid, Card, CardContent, Button, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Paper, Chip, CircularProgress, Divider
} from '@mui/material';
import { ArrowBack as ArrowBackIcon, Edit as EditIcon, Receipt as ReceiptIcon, ShoppingCart as ShoppingCartIcon } from '@mui/icons-material';
import { useQuery } from '@tanstack/react-query';
import { customerService, saleService } from '../api/services';
import { formatDateTime, formatCurrency } from '../utils/helpers';
import CustomerSpendingHeatmap from '../components/CustomerSpendingHeatmap';


const CustomerDetails = () => {
  const { id } = useParams();
  const navigate = useNavigate();

  const { data: customerData, isLoading: customerLoading } = useQuery({
    queryKey: ['customer', id],
    queryFn: () => customerService.getById(id),
  });

  const { data: statsData, isLoading: statsLoading } = useQuery({
    queryKey: ['customerStats', id],
    queryFn: () => saleService.getCustomerStats(id),
  });

  const { data: topProductsData, isLoading: topProductsLoading } = useQuery({
    queryKey: ['customerTopProducts', id],
    queryFn: () => saleService.getCustomerTopProducts(id),
  });

  const { data: salesData, isLoading: salesLoading } = useQuery({
    queryKey: ['customerSales', id],
    queryFn: () => saleService.getAll(0, 10, 'saleDate', null, null, null, id, null),
  });

  if (customerLoading) return <Box sx={{ p: 3 }}><CircularProgress /></Box>;

  const customer = customerData?.data || {};
  const stats = statsData?.data || {};
  const topProducts = topProductsData?.data || [];
  const sales = salesData?.data?.content || [];

  const StatCard = ({ title, value, icon, color }) => (
    <Card sx={{ height: '100%' }}>
      <CardContent>
        <Box sx={{ display: 'flex', alignItems: 'center', mb: 1 }}>
          {icon}
          <Typography variant="body2" color="text.secondary" sx={{ ml: 1 }}>{title}</Typography>
        </Box>
        <Typography variant="h5" fontWeight="bold" color={color}>
          {value !== undefined ? formatCurrency(value) : '-'}
        </Typography>
      </CardContent>
    </Card>
  );

  return (
    <Box sx={{ p: 3 }}>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 3 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 2 }}>
          <Button startIcon={<ArrowBackIcon />} onClick={() => navigate('/customers')}>
            Back
          </Button>
          <Typography variant="h4">
            {customer.firstName} {customer.lastName}
            {customer.isQuickAdd && <Chip label="Quick Add" size="small" color="warning" sx={{ ml: 1 }} />}
          </Typography>
        </Box>
        <Button variant="contained" startIcon={<EditIcon />} onClick={() => navigate(`/customers/${id}/edit`)}>
          Edit Customer
        </Button>
      </Box>

      {/* Customer Info */}
      <Card sx={{ mb: 3 }}>
        <CardContent>
          <Typography variant="h6" gutterBottom>Contact & Address Information</Typography>
          <Divider sx={{ mb: 2 }} />
          <Grid container spacing={3}>
            <Grid item xs={12} md={6}>
              <Typography variant="body2" color="text.secondary">Phone</Typography>
              <Typography variant="body1">{customer.phone || 'N/A'}</Typography>
            </Grid>
            <Grid item xs={12} md={6}>
              <Typography variant="body2" color="text.secondary">Email</Typography>
              <Typography variant="body1">{customer.email || 'N/A'}</Typography>
            </Grid>
            <Grid item xs={12} md={6}>
              <Typography variant="body2" color="text.secondary">Address</Typography>
              <Typography variant="body1">{customer.address || 'N/A'}</Typography>
            </Grid>
            <Grid item xs={12} md={6}>
              <Typography variant="body2" color="text.secondary">Customer Code</Typography>
              <Typography variant="body1">{customer.customerCode || 'N/A'}</Typography>
            </Grid>
          </Grid>
        </CardContent>
      </Card>

      {/* Stats */}
      <Typography variant="h6" gutterBottom sx={{ mb: 2 }}>Spending Overview</Typography>
      <Grid container spacing={2} sx={{ mb: 4 }}>
        <Grid item xs={12} sm={6} md={2.4}>
          <StatCard title="All Time" value={stats.totalSpentAllTime} icon={<ShoppingCartIcon color="primary" />} color="primary.main" />
        </Grid>
        <Grid item xs={12} sm={6} md={2.4}>
          <StatCard title="This Year" value={stats.totalSpentThisYear} icon={<ShoppingCartIcon color="info" />} color="info.main" />
        </Grid>
        <Grid item xs={12} sm={6} md={2.4}>
          <StatCard title="This Month" value={stats.totalSpentThisMonth} icon={<ShoppingCartIcon color="success" />} color="success.main" />
        </Grid>
        <Grid item xs={12} sm={6} md={2.4}>
          <StatCard title="This Week" value={stats.totalSpentThisWeek} icon={<ShoppingCartIcon color="warning" />} color="warning.main" />
        </Grid>
        <Grid item xs={12} sm={6} md={2.4}>
          <Card sx={{ height: '100%' }}>
            <CardContent>
              <Box sx={{ display: 'flex', alignItems: 'center', mb: 1 }}>
                <ReceiptIcon color="secondary" />
                <Typography variant="body2" color="text.secondary" sx={{ ml: 1 }}>Total Invoices</Typography>
              </Box>
              <Typography variant="h5" fontWeight="bold" color="secondary.main">
                {stats.totalInvoices || 0}
              </Typography>
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      {/* Spending Heatmap */}
    <Box sx={{ mb: 4 }}>
        <CustomerSpendingHeatmap customerId={id} />
    </Box>

      <Grid container spacing={3}>
        {/* Top Products */}
        <Grid item xs={12} md={6}>
          <Card sx={{ height: '100%' }}>
            <CardContent>
              <Typography variant="h6" gutterBottom>Top 10 Favorite Products</Typography>
              <Divider sx={{ mb: 2 }} />
              {topProductsLoading ? <CircularProgress size={24} /> : topProducts.length === 0 ? (
                <Typography color="text.secondary">No purchase history yet.</Typography>
              ) : (
                <TableContainer>
                  <Table size="small">
                    <TableHead>
                      <TableRow>
                        <TableCell>Product</TableCell>
                        <TableCell align="right">Qty</TableCell>
                        <TableCell align="right">Total Spent</TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {topProducts.map((p, idx) => (
                        <TableRow key={p.productId}>
                          <TableCell>
                            <Typography variant="body2" fontWeight="medium">{idx + 1}. {p.productName}</Typography>
                          </TableCell>
                          <TableCell align="right">{p.totalQuantity}</TableCell>
                          <TableCell align="right">{formatCurrency(p.totalAmount)}</TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </TableContainer>
              )}
            </CardContent>
          </Card>
        </Grid>

        {/* Recent Invoices */}
        <Grid item xs={12} md={6}>
          <Card sx={{ height: '100%' }}>
            <CardContent>
              <Typography variant="h6" gutterBottom>Recent Invoices</Typography>
              <Divider sx={{ mb: 2 }} />
              {salesLoading ? <CircularProgress size={24} /> : sales.length === 0 ? (
                <Typography color="text.secondary">No invoices found.</Typography>
              ) : (
                <TableContainer>
                  <Table size="small">
                    <TableHead>
                      <TableRow>
                        <TableCell>Invoice</TableCell>
                        <TableCell>Date</TableCell>
                        <TableCell align="right">Amount</TableCell>
                        <TableCell align="center">Status</TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {sales.map((sale) => (
                        <TableRow key={sale.id} hover sx={{ cursor: 'pointer' }} onClick={() => navigate(`/sales/${sale.id}`)}>
                          <TableCell>{sale.invoiceNumber}</TableCell>
                          <TableCell>{formatDateTime(sale.saleDate)}</TableCell>
                          <TableCell align="right">{formatCurrency(sale.totalAmount)}</TableCell>
                          <TableCell align="center">
                            <Chip 
                              label={sale.isVoided ? 'Voided' : 'Completed'} 
                              size="small" 
                              color={sale.isVoided ? 'error' : 'success'} 
                              variant="outlined" 
                            />
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </TableContainer>
              )}
            </CardContent>
          </Card>
        </Grid>
      </Grid>
    </Box>
  );
};

export default CustomerDetails;