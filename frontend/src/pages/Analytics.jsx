import { useMemo, useState } from 'react';
import { Box, Typography, Paper, Grid, ToggleButton, ToggleButtonGroup, CircularProgress, Stack } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { BarChart, Bar, PieChart, Pie, Cell, LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend } from 'recharts';
import { reportService } from '../api/services';
import { formatCurrency } from '../utils/helpers';

const COLORS = ['#1976d2', '#2e7d32', '#ed6c02', '#9c27b0', '#d32f2f'];

const toDateString = (date) => date.toISOString().split('T')[0];

const getPeriodRange = (period) => {
  const end = new Date();
  const start = new Date();
  if (period === 'WEEK') {
    start.setDate(end.getDate() - 6);
  } else if (period === 'YEAR') {
    start.setFullYear(end.getFullYear() - 1);
  } else {
    start.setMonth(end.getMonth() - 1);
  }
  return { startDate: toDateString(start), endDate: toDateString(end) };
};

const Analytics = () => {
  const [period, setPeriod] = useState('MONTH');
  const periodRange = useMemo(() => getPeriodRange(period), [period]);

  const { data: profitSummaryData, isLoading: summaryLoading } = useQuery({
    queryKey: ['profitSummary', periodRange.startDate, periodRange.endDate],
    queryFn: () => reportService.getProfitSummary(periodRange.startDate, periodRange.endDate),
  });

  const { data: topProductsData, isLoading: topProductsLoading } = useQuery({
    queryKey: ['topProducts', period],
    queryFn: () => reportService.getTopProducts(period),
  });

  const { data: topCategoriesData, isLoading: topCategoriesLoading } = useQuery({
    queryKey: ['topCategories', period],
    queryFn: () => reportService.getTopCategories(period),
  });

  const { data: profitTrendData, isLoading: profitTrendLoading } = useQuery({
    queryKey: ['profitTrend', period],
    queryFn: () => reportService.getProfitTrend(period),
  });

  const summary = profitSummaryData?.data || {};
  const topProducts = topProductsData?.data || [];
  const topCategories = topCategoriesData?.data || [];
  const profitTrend = profitTrendData?.data || [];

  const summaryCards = useMemo(() => [
    { label: 'Total Revenue', value: summary.revenue || 0 },
    { label: 'Total COGS', value: summary.cogs || 0 },
    { label: 'Gross Profit', value: summary.grossProfit || 0 },
    { label: 'Gross Margin %', value: summary.grossMarginPercent || 0, formatter: (value) => `${value}%` },
  ], [summary]);

  const renderChartState = (loading) => loading ? <Box sx={{ display: 'flex', justifyContent: 'center', py: 8 }}><CircularProgress /></Box> : <Typography color="text.secondary">No sales data for this period.</Typography>;

  return (
    <Box>
      <Stack direction={{ xs: 'column', md: 'row' }} justifyContent="space-between" alignItems={{ xs: 'flex-start', md: 'center' }} spacing={2} sx={{ mb: 3 }}>
        <Typography variant="h4">Analytics</Typography>
        <ToggleButtonGroup value={period} exclusive onChange={(_, value) => value && setPeriod(value)}>
          <ToggleButton value="WEEK">Week</ToggleButton>
          <ToggleButton value="MONTH">Month</ToggleButton>
          <ToggleButton value="YEAR">Year</ToggleButton>
        </ToggleButtonGroup>
      </Stack>

      <Grid container spacing={2} sx={{ mb: 3 }}>
        {summaryCards.map((card) => (
          <Grid item xs={12} sm={6} md={3} key={card.label}>
            <Paper sx={{ p: 2 }}>
              <Typography variant="body2" color="text.secondary">{card.label}</Typography>
              <Typography variant="h5" sx={{ fontWeight: 600 }}>
                {card.formatter ? card.formatter(card.value) : formatCurrency(card.value)}
              </Typography>
            </Paper>
          </Grid>
        ))}
      </Grid>

      <Grid container spacing={3}>
        <Grid item xs={12} md={6}>
          <Paper sx={{ p: 3, height: 360 }}>
            <Typography variant="h6" gutterBottom>Top Products</Typography>
            {topProductsLoading ? <Box sx={{ display: 'flex', justifyContent: 'center', py: 8 }}><CircularProgress /></Box> : topProducts.length === 0 ? renderChartState(false) : (
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={topProducts} layout="vertical" margin={{ top: 5, right: 20, left: 10, bottom: 5 }}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis type="number" />
                  <YAxis type="category" dataKey="productName" width={140} />
                  <Tooltip formatter={(value) => formatCurrency(value)} />
                  <Bar dataKey="revenue" fill="#1976d2" />
                </BarChart>
              </ResponsiveContainer>
            )}
          </Paper>
        </Grid>

        <Grid item xs={12} md={6}>
          <Paper sx={{ p: 3, height: 360 }}>
            <Typography variant="h6" gutterBottom>Top Categories</Typography>
            {topCategoriesLoading ? <Box sx={{ display: 'flex', justifyContent: 'center', py: 8 }}><CircularProgress /></Box> : topCategories.length === 0 ? renderChartState(false) : (
              <ResponsiveContainer width="100%" height="100%">
                <PieChart>
                  <Pie data={topCategories} dataKey="revenue" nameKey="categoryName" outerRadius={100}>
                    {topCategories.map((entry, index) => <Cell key={`${entry.categoryName}-${index}`} fill={COLORS[index % COLORS.length]} />)}
                  </Pie>
                  <Tooltip formatter={(value) => formatCurrency(value)} />
                  <Legend />
                </PieChart>
              </ResponsiveContainer>
            )}
          </Paper>
        </Grid>

        <Grid item xs={12}>
          <Paper sx={{ p: 3, height: 360 }}>
            <Typography variant="h6" gutterBottom>Profit Trend</Typography>
            {profitTrendLoading ? <Box sx={{ display: 'flex', justifyContent: 'center', py: 8 }}><CircularProgress /></Box> : profitTrend.length === 0 ? renderChartState(false) : (
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={profitTrend}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis dataKey="periodLabel" />
                  <YAxis />
                  <Tooltip formatter={(value) => formatCurrency(value)} />
                  <Legend />
                  <Line type="monotone" dataKey="revenue" stroke="#1976d2" strokeWidth={2} />
                  <Line type="monotone" dataKey="grossProfit" stroke="#2e7d32" strokeWidth={2} />
                </LineChart>
              </ResponsiveContainer>
            )}
          </Paper>
        </Grid>
      </Grid>
    </Box>
  );
};

export default Analytics;
