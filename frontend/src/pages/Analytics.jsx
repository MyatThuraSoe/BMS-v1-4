import { useState, useEffect } from 'react';
import {
  Box,
  Grid,
  Card,
  CardContent,
  Typography,
  ToggleButtonGroup,
  ToggleButton,
  CircularProgress,
  Alert,
} from '@mui/material';
import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ResponsiveContainer,
  PieChart,
  Pie,
  Cell,
  LineChart,
  Line,
} from 'recharts';
import { reportService } from '../api/services';

const COLORS = [
  '#0088FE',
  '#00C49F',
  '#FFBB28',
  '#FF8042',
  '#8884D8',
  '#82CA9D',
  '#FFC658',
  '#FF6B6B',
  '#4ECDC4',
  '#45B7D1',
];

const Analytics = () => {
  const [period, setPeriod] = useState('MONTH');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  
  // Data states
  const [profitSummary, setProfitSummary] = useState(null);
  const [topProducts, setTopProducts] = useState([]);
  const [topCategories, setTopCategories] = useState([]);
  const [profitTrend, setProfitTrend] = useState([]);

  // Calculate date range for profit summary based on period
  const getDateRangeForPeriod = (periodType) => {
    const today = new Date();
    let startDate;
    
    switch (periodType) {
      case 'WEEK':
        startDate = new Date(today);
        startDate.setDate(today.getDate() - 6);
        break;
      case 'YEAR':
        startDate = new Date(today.getFullYear() - 1, today.getMonth(), today.getDate());
        break;
      case 'MONTH':
      default:
        startDate = new Date(today.getFullYear(), today.getMonth(), 1);
        break;
    }
    
    return {
      startDate: startDate.toISOString().split('T')[0],
      endDate: today.toISOString().split('T')[0],
    };
  };

  useEffect(() => {
    const fetchData = async () => {
      setLoading(true);
      setError(null);
      
      try {
        const { startDate, endDate } = getDateRangeForPeriod(period);
        
        // Fetch all data in parallel
        const [summaryRes, productsRes, categoriesRes, trendRes] = await Promise.all([
          reportService.getProfitSummary(startDate, endDate),
          reportService.getTopProducts(period, 10),
          reportService.getTopCategories(period),
          reportService.getProfitTrend(period, 12),
        ]);
        
        setProfitSummary(summaryRes.data || null);
        setTopProducts(productsRes.data || []);
        setTopCategories(categoriesRes.data || []);
        setProfitTrend(trendRes.data || []);
      } catch (err) {
        console.error('Error fetching analytics data:', err);
        setError('Failed to load analytics data. Please try again.');
        // Set empty states to prevent crashes
        setProfitSummary(null);
        setTopProducts([]);
        setTopCategories([]);
        setProfitTrend([]);
      } finally {
        setLoading(false);
      }
    };

    fetchData();
  }, [period]);

  const handlePeriodChange = (event, newPeriod) => {
    if (newPeriod !== null) {
      setPeriod(newPeriod);
    }
  };

  // Format currency
  const formatCurrency = (value) => {
    if (value === null || value === undefined) return '$0.00';
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
    }).format(value);
  };

  // Format large numbers
  const formatNumber = (value) => {
    if (value === null || value === undefined) return '0';
    return new Intl.NumberFormat('en-US').format(value);
  };

  // Prepare data for Top Products bar chart
  const productsChartData = topProducts.map((item) => ({
    name: item.productName?.length > 15 
      ? item.productName.substring(0, 15) + '...' 
      : item.productName,
    fullName: item.productName,
    revenue: parseFloat(item.revenue) || 0,
    profit: parseFloat(item.grossProfit) || 0,
  }));

  // Prepare data for Top Categories pie chart
  const categoriesChartData = topCategories.map((item) => ({
    name: item.categoryName,
    value: parseFloat(item.revenue) || 0,
  }));

  // Prepare data for Profit Trend line chart
  const trendChartData = profitTrend.map((item) => ({
    period: item.periodLabel,
    revenue: parseFloat(item.revenue) || 0,
    grossProfit: parseFloat(item.grossProfit) || 0,
  }));

  // Summary cards data
  const summaryCards = [
    {
      title: 'Total Revenue',
      value: formatCurrency(profitSummary?.totalRevenue),
      color: '#1976d2',
    },
    {
      title: 'Total COGS',
      value: formatCurrency(profitSummary?.totalCOGS),
      color: '#d32f2f',
    },
    {
      title: 'Gross Profit',
      value: formatCurrency(profitSummary?.totalProfit),
      color: '#388e3c',
    },
    {
      title: 'Gross Margin',
      value: `${(profitSummary?.grossMarginPercent || 0).toFixed(2)}%`,
      color: '#f57c00',
    },
  ];

  if (loading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '60vh' }}>
        <CircularProgress />
      </Box>
    );
  }

  return (
    <Box sx={{ p: 3 }}>
      <Box sx={{ mb: 4, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Typography variant="h4" component="h1" gutterBottom>
          Analytics
        </Typography>
        <ToggleButtonGroup
          value={period}
          exclusive
          onChange={handlePeriodChange}
          color="primary"
          size="small"
        >
          <ToggleButton value="WEEK">Week</ToggleButton>
          <ToggleButton value="MONTH">Month</ToggleButton>
          <ToggleButton value="YEAR">Year</ToggleButton>
        </ToggleButtonGroup>
      </Box>

      {error && (
        <Alert severity="error" sx={{ mb: 3 }}>
          {error}
        </Alert>
      )}

      {/* Summary Cards */}
      <Grid container spacing={3} sx={{ mb: 4 }}>
        {summaryCards.map((card, index) => (
          <Grid item xs={12} sm={6} md={3} key={index}>
            <Card sx={{ height: '100%' }}>
              <CardContent>
                <Typography color="textSecondary" variant="subtitle2" gutterBottom>
                  {card.title}
                </Typography>
                <Typography variant="h4" component="div" sx={{ color: card.color, fontWeight: 'bold' }}>
                  {card.value}
                </Typography>
              </CardContent>
            </Card>
          </Grid>
        ))}
      </Grid>

      {/* Charts Row 1: Top Products & Top Categories */}
      <Grid container spacing={3} sx={{ mb: 4 }}>
        {/* Top Products Bar Chart */}
        <Grid item xs={12} lg={8}>
          <Card>
            <CardContent>
              <Typography variant="h6" gutterBottom>
                Top Products by Revenue
              </Typography>
              {productsChartData.length > 0 ? (
                <ResponsiveContainer width="100%" height={350}>
                  <BarChart data={productsChartData} margin={{ top: 20, right: 30, left: 20, bottom: 60 }}>
                    <CartesianGrid strokeDasharray="3 3" />
                    <XAxis 
                      dataKey="name" 
                      angle={-45}
                      textAnchor="end"
                      interval={0}
                      height={80}
                      tickFormatter={(value, index) => productsChartData[index]?.fullName || value}
                    />
                    <YAxis tickFormatter={(value) => `$${(value / 1000).toFixed(0)}k`} />
                    <Tooltip 
                      formatter={(value, name) => [
                        formatCurrency(value),
                        name === 'revenue' ? 'Revenue' : 'Profit'
                      ]}
                      labelFormatter={(label, index) => productsChartData[index]?.fullName || label}
                    />
                    <Legend />
                    <Bar dataKey="revenue" fill="#1976d2" name="Revenue" />
                    <Bar dataKey="profit" fill="#388e3c" name="Profit" />
                  </BarChart>
                </ResponsiveContainer>
              ) : (
                <Box sx={{ height: 350, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                  <Typography color="textSecondary">No sales data for this period</Typography>
                </Box>
              )}
            </CardContent>
          </Card>
        </Grid>

        {/* Top Categories Pie Chart */}
        <Grid item xs={12} lg={4}>
          <Card>
            <CardContent>
              <Typography variant="h6" gutterBottom>
                Revenue by Category
              </Typography>
              {categoriesChartData.length > 0 ? (
                <ResponsiveContainer width="100%" height={350}>
                  <PieChart>
                    <Pie
                      data={categoriesChartData}
                      cx="50%"
                      cy="50%"
                      labelLine={false}
                      label={({ name, percent }) => `${name}: ${(percent * 100).toFixed(0)}%`}
                      outerRadius={100}
                      fill="#8884d8"
                      dataKey="value"
                    >
                      {categoriesChartData.map((entry, index) => (
                        <Cell key={`cell-${index}`} fill={COLORS[index % COLORS.length]} />
                      ))}
                    </Pie>
                    <Tooltip formatter={(value) => formatCurrency(value)} />
                    <Legend />
                  </PieChart>
                </ResponsiveContainer>
              ) : (
                <Box sx={{ height: 350, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                  <Typography color="textSecondary">No sales data for this period</Typography>
                </Box>
              )}
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      {/* Charts Row 2: Profit Trend */}
      <Grid container spacing={3}>
        <Grid item xs={12}>
          <Card>
            <CardContent>
              <Typography variant="h6" gutterBottom>
                Profit Trend
              </Typography>
              {trendChartData.length > 0 ? (
                <ResponsiveContainer width="100%" height={350}>
                  <LineChart data={trendChartData} margin={{ top: 20, right: 30, left: 20, bottom: 20 }}>
                    <CartesianGrid strokeDasharray="3 3" />
                    <XAxis dataKey="period" />
                    <YAxis tickFormatter={(value) => `$${(value / 1000).toFixed(0)}k`} />
                    <Tooltip 
                      formatter={(value, name) => [
                        formatCurrency(value),
                        name === 'revenue' ? 'Revenue' : 'Gross Profit'
                      ]}
                    />
                    <Legend />
                    <Line 
                      type="monotone" 
                      dataKey="revenue" 
                      stroke="#1976d2" 
                      strokeWidth={2}
                      name="Revenue"
                      activeDot={{ r: 8 }}
                    />
                    <Line 
                      type="monotone" 
                      dataKey="grossProfit" 
                      stroke="#388e3c" 
                      strokeWidth={2}
                      name="Gross Profit"
                    />
                  </LineChart>
                </ResponsiveContainer>
              ) : (
                <Box sx={{ height: 350, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                  <Typography color="textSecondary">No sales data for this period</Typography>
                </Box>
              )}
            </CardContent>
          </Card>
        </Grid>
      </Grid>
    </Box>
  );
};

export default Analytics;
