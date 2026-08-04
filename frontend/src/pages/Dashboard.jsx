import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Grid, Paper, Typography, Box, Button } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { reportService, saleService } from '../api/services';
import { ShoppingCart, AttachMoney, Inventory, TrendingUp, Add as AddIcon } from '@mui/icons-material';
import { formatDateTime } from '../utils/helpers';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';

import SetupChecklist from '../components/SetupChecklist';

import PeriodToggle from '../components/PeriodToggle';
import FinancialSummaryCards from '../components/FinancialSummaryCards';


const StatCard = ({ title, value, icon, color, onClick }) => (
  <Paper
    onClick={onClick}
    sx={{
      p: 3,
      display: 'flex',
      alignItems: 'center',
      height: '100%',
      cursor: onClick ? 'pointer' : 'default',
      transition: 'box-shadow 0.2s',
      '&:hover': onClick ? { boxShadow: 4 } : {},
    }}
  >
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

  const [period, setPeriod] = useState('today');
  const [dateRange, setDateRange] = useState(() => {
    const today = new Date().toISOString().split('T')[0];
    return { startDate: today, endDate: today };
  });

  const handlePeriodChange = (newPeriod, startDate, endDate) => {
    setPeriod(newPeriod);
    setDateRange({ startDate, endDate });
  };

  const { data: financialSummaryData } = useQuery({
    queryKey: ['financialSummary', dateRange.startDate, dateRange.endDate],
    queryFn: () => reportService.getFinancialSummary(dateRange.startDate, dateRange.endDate),
    enabled: true,
  });
  const financialSummary = financialSummaryData?.data;

  const { data: dailySalesData } = useQuery({
    queryKey: ['dailySales', today],
    queryFn: () => reportService.getDailySales(today),
  });

  const { data: inventoryData } = useQuery({
    queryKey: ['inventoryReport'],
    queryFn: () => reportService.getInventoryReport(),
  });

  const { data: recentSalesData } = useQuery({
    queryKey: ['recentSales'],
    queryFn: () => saleService.getAll(0, 5, 'saleDate'),
  });

  const { data: salesTrendData } = useQuery({
    queryKey: ['salesTrend', 7],
    queryFn: () => reportService.getSalesTrend(7),
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

  return (
    <Box>
      <Box sx={{ mb: 2 }}>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>Today at a glance</Typography>
      </Box>
      {/* Placed at the top to naturally span full width without breaking Grid */}
      <SetupChecklist />
      
      <Paper sx={{ p: 3, mb: 3 }}>
        <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 2, flexWrap: 'wrap', gap: 1 }}>
          <Typography variant="h6">Financial Summary</Typography>
          <PeriodToggle period={period} onChange={handlePeriodChange} />
        </Box>
        <FinancialSummaryCards
          summary={financialSummary}
          onCardClick={(key) => {
            if (key === 'revenue') navigate(`/sales?range=${period}`);
            else navigate(`/accounting?startDate=${dateRange.startDate}&endDate=${dateRange.endDate}`);
          }}
        />
      </Paper>

      <Grid container spacing={3}>
        <Grid item xs={12} sm={6} md={3}>
          <StatCard
            title="Sales This Period"
            value={dailySales.totalTransactions || 0}
            icon={<ShoppingCart />}
            color="primary.main"
            onClick={() => navigate(`/sales?range=${period}`)}
          />
        </Grid>
        <Grid item xs={12} sm={6} md={4}>
          <StatCard
            title="Products in Stock"
            value={inventory.totalProducts || 0}
            icon={<Inventory />}
            color="info.main"
            onClick={() => navigate('/products')}
          />
        </Grid>
        <Grid item xs={12} sm={6} md={5}>
          <StatCard
            title="Low Stock Alerts"
            value={inventory.lowStockProductsCount || 0}
            icon={<TrendingUp />}
            color="warning.main"
            onClick={() => navigate('/products?view=low-stock')}
          />
        </Grid>
      </Grid>

      <Grid container spacing={3} sx={{ mt: 2 }}>
        <Grid item xs={12} md={6}>
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

        <Grid item xs={12} md={6}>
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

        <Grid item xs={12}>
          <Paper sx={{ p: 3 }}>
            <Typography variant="h6" gutterBottom>Sales Trend (Last 7 Days)</Typography>
            <Box sx={{ width: '100%', height: 260 }}>
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={salesTrend}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis dataKey="date" />
                  <YAxis />
                  <Tooltip formatter={(value) => formatCurrency(value)} />
                  <Line type="monotone" dataKey="totalSales" stroke="#1976d2" strokeWidth={2} />
                </LineChart>
              </ResponsiveContainer>
            </Box>
          </Paper>
        </Grid>
      </Grid>
    </Box>
  );
};

export default Dashboard;