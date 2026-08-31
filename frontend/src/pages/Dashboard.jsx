import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Grid, Paper, Typography, Box, Button, Chip } from '@mui/material';
import { useTheme } from '@mui/material/styles';
import { useQuery } from '@tanstack/react-query';
import { reportService, saleService, inventoryService } from '../api/services';
import { ShoppingCart, Inventory, TrendingUp, Add as AddIcon, TrendingDown as TrendingDownIcon } from '@mui/icons-material';
import { formatDateTime, formatCurrency } from '../utils/helpers';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../context/AuthContext';
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend } from 'recharts';

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
  const theme = useTheme();
  const { t } = useTranslation('dashboard');
  const { isAdmin } = useAuth();
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
    enabled: isAdmin(),
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

  // --- Advanced widgets ---
  const { data: topProductsData } = useQuery({
    queryKey: ['dashboard-top-products', dateRange.startDate, dateRange.endDate],
    queryFn: () => reportService.getTopSellingProducts(5, dateRange.startDate, dateRange.endDate),
  });

  const { data: movementStatsData } = useQuery({
    queryKey: ['dashboard-movement-stats', 14],
    queryFn: () => inventoryService.getMovementStats(14),
  });

  const { data: invSummaryData } = useQuery({
    queryKey: ['inventory-summary'],
    queryFn: () => inventoryService.getSummary(),
  });

  const topProducts = topProductsData?.data || [];
  const movementStats = movementStatsData?.data || null;
  const lowStockItems = invSummaryData?.data?.lowStockItems || [];

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

  return (
    <Box>
      <SetupChecklist />

      <Grid container spacing={3}>
        {isAdmin() && (
          <Grid item xs={12}>
            <Paper sx={{ p: 3 }}>
              <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 2, flexWrap: 'wrap', gap: 1 }}>
                <Typography variant="h6">{t('financial_summary')}</Typography>
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
          </Grid>
        )}

        <Grid item xs={12} sm={6} md={3}>
          <StatCard
            title={t('sales_this_period')}
            value={dailySales.totalTransactions || 0}
            icon={<ShoppingCart />}
            color="primary.main"
            onClick={() => navigate(`/sales?range=${period}`)}
          />
        </Grid>
        <Grid item xs={12} sm={6} md={4}>
          <StatCard
            title={t('products_in_stock')}
            value={inventory.totalProducts || 0}
            icon={<Inventory />}
            color="info.main"
            onClick={() => navigate('/products')}
          />
        </Grid>
        <Grid item xs={12} sm={6} md={5}>
          <StatCard
            title={t('low_stock_alerts')}
            value={inventory.lowStockProductsCount || 0}
            icon={<TrendingUp />}
            color="warning.main"
            onClick={() => navigate('/products?view=low-stock')}
          />
        </Grid>

        <Grid item xs={12}>
          <Paper sx={{ p: 3 }}>
            <Typography variant="h6" gutterBottom>{t('sales_trend_last_7_days')}</Typography>
            <Box sx={{ width: '100%', height: 260 }}>
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={salesTrend} margin={{ top: 24, right: 8, left: 0, bottom: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" vertical={false} />
                  <XAxis dataKey="date" tickLine={false} axisLine={false} />
                  <YAxis tickLine={false} axisLine={false} />
                  <Tooltip formatter={(value) => formatCurrency(value)} />
                  <Bar
                    dataKey="totalSales"
                    fill={theme.palette.primary.main}
                    radius={[4, 4, 0, 0]}
                    maxBarSize={48}
                    label={{
                      position: 'top',
                      fill: theme.palette.text.primary,
                      fontSize: 11,
                      fontWeight: 600,
                      formatter: (value) => formatCurrency(value),
                    }}
                  />
                </BarChart>
              </ResponsiveContainer>
            </Box>
          </Paper>
        </Grid>

        {/* --- Advanced: Stock Movement Flow (last 14 days) --- */}
        {movementStats && (
          <Grid item xs={12}>
            <Paper sx={{ p: 3 }}>
              <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 2, flexWrap: 'wrap', gap: 1 }}>
                <Typography variant="h6">{t('stock_movements_last_14_days')}</Typography>
                <Box sx={{ display: 'flex', gap: 1.5, flexWrap: 'wrap' }}>
                  <Chip size="small" color="success" label={`${t('stock_in')}: ${movementStats.totalIn ?? 0}`} />
                  <Chip size="small" color="error" label={`${t('stock_out')}: ${movementStats.totalOut ?? 0}`} />
                  <Chip
                    size="small"
                    icon={<TrendingDownIcon />}
                    color={(movementStats.netChange ?? 0) >= 0 ? 'primary' : 'warning'}
                    label={`${t('net_change')}: ${(movementStats.netChange ?? 0) >= 0 ? '+' : ''}${movementStats.netChange ?? 0}`}
                  />
                </Box>
              </Box>
              <Box sx={{ width: '100%', height: 240 }}>
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={movementStats.daily || []} margin={{ top: 8, right: 8, left: 0, bottom: 0 }} barGap={4}>
                    <CartesianGrid strokeDasharray="3 3" vertical={false} />
                    <XAxis dataKey="date" tickLine={false} axisLine={false} />
                    <YAxis tickLine={false} axisLine={false} allowDecimals={false} />
                    <Tooltip />
                    <Legend wrapperStyle={{ fontSize: 12 }} />
                    <Bar
                      dataKey="inQty"
                      name={t('stock_in')}
                      fill={theme.palette.success.main}
                      radius={[4, 4, 0, 0]}
                      maxBarSize={28}
                    />
                    <Bar
                      dataKey="outQty"
                      name={t('stock_out')}
                      fill={theme.palette.error.main}
                      radius={[4, 4, 0, 0]}
                      maxBarSize={28}
                    />
                  </BarChart>
                </ResponsiveContainer>
              </Box>
            </Paper>
          </Grid>
        )}

{/* --- Advanced: Top Selling Products (follows selected period) --- */}
        <Grid item xs={12} md={6}>
          <Paper sx={{ p: 3 }}>
            <Typography variant="h6" gutterBottom>{t('top_selling_products')}</Typography>
            {topProducts.length === 0 ? (
              <Typography variant="body2" color="text.secondary">{t('no_data')}</Typography>
            ) : (
              <Box sx={{ width: '100%', height: 240 }}>
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart
                    data={topProducts}
                    layout="vertical"
                    margin={{ top: 4, right: 24, left: 8, bottom: 0 }}
                  >
                    <CartesianGrid strokeDasharray="3 3" horizontal={false} />
                    <XAxis type="number" tickLine={false} axisLine={false} />
                    <YAxis
                      type="category"
                      dataKey="productName"
                      width={130}
                      tickLine={false}
                      axisLine={false}
                      tick={{ fontSize: 11 }}
                    />
                    <Tooltip formatter={(value) => formatCurrency(value)} />
                    <Bar
                      dataKey="totalRevenue"
                      fill={theme.palette.secondary.main}
                      radius={[0, 4, 4, 0]}
                      maxBarSize={20}
                    />
                  </BarChart>
                </ResponsiveContainer>
              </Box>
            )}
          </Paper>
        </Grid>

        {/* --- Recent Activity --- */}
        <Grid item xs={12} md={6}>
          <Paper sx={{ p: 3 }}>
            <Typography variant="h6" gutterBottom>{t('recent_activity')}</Typography>
            {recentSales.length === 0 ? (
              <Typography variant="body2" color="text.secondary">
                {t('no_recent_activity')}
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

        {/* --- Advanced: Low Stock Watchlist --- */}
        <Grid item xs={12} md={6}>
          <Paper sx={{ p: 3 }}>
            <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 1 }}>
              <Typography variant="h6">{t('low_stock_watchlist')}</Typography>
              <Button size="small" onClick={() => navigate('/products?view=low-stock')}>
                {t('view_all')}
              </Button>
            </Box>
            {lowStockItems.length === 0 ? (
              <Typography variant="body2" color="text.secondary">
                {t('all_stock_healthy')}
              </Typography>
            ) : (
              <Box>
                {lowStockItems.slice(0, 6).map((item) => (
                  <Box
                    key={item.productId}
                    onClick={() => navigate(`/products/${item.productId}/edit`)}
                    sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', py: 1, borderBottom: '1px solid #eee', cursor: 'pointer' }}
                  >
                    <Box sx={{ minWidth: 0 }}>
                      <Typography variant="body2" fontWeight="medium" noWrap>{item.productName}</Typography>
                      <Typography variant="caption" color="text.secondary">{item.sku}</Typography>
                    </Box>
                    <Box sx={{ textAlign: 'right', flexShrink: 0 }}>
                      <Typography
                        variant="body2"
                        fontWeight="bold"
                        color={item.stockQuantity <= 0 ? 'error.main' : 'warning.main'}
                      >
                        {item.stockQuantity} / {item.minStockLevel}
                      </Typography>
                      <Typography variant="caption" color="error.main">
                        -{item.shortage}
                      </Typography>
                    </Box>
                  </Box>
                ))}
              </Box>
            )}
          </Paper>
        </Grid>

        <Grid item xs={12} md={6}>
          <Paper sx={{ p: 3 }}>
            <Typography variant="h6" gutterBottom>{t('quick_actions')}</Typography>
            <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
              <Button variant="contained" startIcon={<AddIcon />} onClick={() => navigate('/pos')}>
                {t('new_sale')}
              </Button>
              <Button variant="outlined" startIcon={<AddIcon />} onClick={() => navigate('/products/new')}>
                {t('new_product')}
              </Button>
              <Button variant="outlined" startIcon={<AddIcon />} onClick={() => navigate('/purchases/new')}>
                {t('new_purchase')}
              </Button>
              <Button variant="outlined" startIcon={<AddIcon />} onClick={() => navigate('/customers/new')}>
                {t('new_customer')}
              </Button>
            </Box>
          </Paper>
        </Grid>
      </Grid>
    </Box>
  );
};

export default Dashboard;
