import { useNavigate } from 'react-router-dom';
import { Grid, Paper, Typography, Box, Button } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { reportService, saleService, productService } from '../api/services';
import { ShoppingCart, AttachMoney, Inventory, TrendingUp, Add as AddIcon } from '@mui/icons-material';
import { formatDateTime } from '../utils/helpers';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';

const StatCard = ({ title, value, icon, color }) => (
  <Paper sx={{ p: 3, display: 'flex', alignItems: 'center', height: '100%' }}>
    <Box sx={{ flexShrink: 0, mr: 2, color, fontSize: 48 }}>{icon}</Box>
    <Box>
      <Typography variant="body2" color="text.secondary">{title}</Typography>
      <Typography variant="h4" component="div" sx={{ fontWeight: 'bold' }}>
        {value}
      </Typography>
    </Box>
  </Paper>
);

const Dashboard = () => {
  const navigate = useNavigate();
  const today = new Date().toISOString().split('T')[0];

  const { data: dailySalesData } = useQuery({
    queryKey: ['dailySales', today],
    queryFn: () => reportService.getDailySales(today),
    enabled: true,
  });

  const { data: inventoryData } = useQuery({
    queryKey: ['inventoryReport'],
    queryFn: () => reportService.getInventoryReport(),
    enabled: true,
  });

  const { data: recentSalesData } = useQuery({
    queryKey: ['recentSales'],
    queryFn: () => saleService.getAll(0, 5, 'saleDate'),
    enabled: true,
  });

  const { data: salesTrendData } = useQuery({
    queryKey: ['salesTrend', 7],
    queryFn: () => reportService.getSalesTrend(7),
    enabled: true,
  });

  const dailySales = dailySalesData?.data || {
    totalTransactions: 0,
    totalRevenue: 0,
    averageTransactionValue: 0,
  };

  const inventory = inventoryData?.data || {
    totalProducts: 0,
    totalInventoryValue: 0,
    lowStockProductsCount: 0,
  };
  const recentSales = recentSalesData?.data?.content || [];
  const salesTrend = salesTrendData?.data || [];

  const formatCurrency = (amount) => {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
    }).format(amount || 0);
  };

  const formatDate = (dateStr) => {
    const date = new Date(dateStr);
    return date.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
  };

  return (
    <Box>
      <Typography variant="h4" gutterBottom sx={{ mb: 4 }}>
        Dashboard
      </Typography>

      <Grid container spacing={3}>
        <Grid item xs={12} sm={6} md={3}>
          <StatCard
            title="Sales Today"
            value={dailySales.totalTransactions || 0}
            icon={<ShoppingCart />}
            color="primary.main"
          />
        </Grid>
        <Grid item xs={12} sm={6} md={3}>
          <StatCard
            title="Today's Revenue"
            value={formatCurrency(dailySales.totalRevenue || 0)}
            icon={<AttachMoney />}
            color="success.main"
          />
        </Grid>
        <Grid item xs={12} sm={6} md={3}>
          <StatCard
            title="Products in Stock"
            value={inventory.totalProducts || 0}
            icon={<Inventory />}
            color="info.main"
          />
        </Grid>
        <Grid item xs={12} sm={6} md={3}>
          <StatCard
            title="Low Stock Alerts"
            value={inventory.lowStockProductsCount || 0}
            icon={<TrendingUp />}
            color="warning.main"
          />
        </Grid>
      </Grid>

      <Grid container spacing={3} sx={{ mt: 2 }}>
        <Grid item xs={12} md={8}>
          <Paper sx={{ p: 3 }}>
            <Typography variant="h6" gutterBottom>Sales Trend (Last 7 Days)</Typography>
            {salesTrend.length === 0 ? (
              <Typography variant="body2" color="text.secondary">
                No sales data available for the last 7 days.
              </Typography>
            ) : (
              <ResponsiveContainer width="100%" height={250}>
                <LineChart data={salesTrend}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis dataKey="date" tickFormatter={formatDate} />
                  <YAxis />
                  <Tooltip formatter={(value) => formatCurrency(value)} />
                  <Line type="monotone" dataKey="totalSales" stroke="#1976d2" strokeWidth={2} />
                </LineChart>
              </ResponsiveContainer>
            )}
          </Paper>
        </Grid>
        <Grid item xs={12} md={4}>
          <Paper sx={{ p: 3 }}>
            <Typography variant="h6" gutterBottom>Quick Actions</Typography>
            <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
              <Button variant="contained" startIcon={<AddIcon />} onClick={() => navigate('/pos')}>
                New Sale
              </Button>
              <Button variant="outlined" startIcon={<AddIcon />} onClick={() => navigate('/products/new')}>
                New Product
              </Button>
              <Button variant="outlined" startIcon={<AddIcon />} onClick={() => navigate('/purchases/new')}>
                New Purchase
              </Button>
              <Button variant="outlined" startIcon={<AddIcon />} onClick={() => navigate('/customers/new')}>
                New Customer
              </Button>
            </Box>
          </Paper>
        </Grid>
      </Grid>

      <Grid container spacing={3} sx={{ mt: 2 }}>
        <Grid item xs={12}>
          <Paper sx={{ p: 3 }}>
            <Typography variant="h6" gutterBottom>Recent Activity</Typography>
            {recentSales.length === 0 ? (
              <Typography variant="body2" color="text.secondary">
                No recent activity to display.
              </Typography>
            ) : (
              <Box>
                {recentSales.map((sale) => (
                  <Box key={sale.id} sx={{ display: 'flex', justifyContent: 'space-between', py: 1, borderBottom: '1px solid #eee' }}>
                    <Box>
                      <Typography variant="body2" fontWeight="medium">{sale.invoiceNumber}</Typography>
                      <Typography variant="caption" color="text.secondary">{formatDateTime(sale.saleDate)}</Typography>
                    </Box>
                    <Typography variant="body2" fontWeight="medium">{formatCurrency(sale.totalAmount)}</Typography>
                  </Box>
                ))}
              </Box>
            )}
          </Paper>
        </Grid>
      </Grid>
    </Box>
  );
};

export default Dashboard;
