import { useMemo, useState } from 'react';
import {
  Box, Typography, Paper, Grid, ToggleButton, ToggleButtonGroup, CircularProgress, Stack,
  Table, TableBody, TableCell, TableContainer, TableHead, TableRow,
  Tooltip as MuiTooltip, IconButton, Chip, TextField, Alert, TableSortLabel, Autocomplete,
} from '@mui/material';
import { InfoOutlined } from '@mui/icons-material';
import { useQuery } from '@tanstack/react-query';
import {
  BarChart, Bar, PieChart, Pie, Cell, LineChart, Line,
  XAxis, YAxis, CartesianGrid, Tooltip as RechartsTooltip,
  ResponsiveContainer, Legend,
} from 'recharts';
import { reportService } from '../api/services';
import { formatCurrency } from '../utils/helpers';

const COLORS = ['#1976d2', '#2e7d32', '#ed6c02', '#9c27b0', '#d32f2f'];
const DAY_LABELS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

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

// ─── Top Movers Component ───────────────────────────────────────────────────
const TopMovers = ({ products }) => {
  const withChange = products.filter((p) => p.changePercent != null);
  const gainers = [...withChange].sort((a, b) => b.changePercent - a.changePercent).slice(0, 3);
  const decliners = [...withChange].sort((a, b) => a.changePercent - b.changePercent).slice(0, 3);

  const MoverRow = ({ p, positive }) => (
    <Box sx={{ display: 'flex', justifyContent: 'space-between', py: 0.5 }}>
      <Typography variant="body2">{p.productName}</Typography>
      <Typography variant="body2" color={positive ? 'success.main' : 'error.main'} fontWeight="bold">
        {positive ? '▲' : '▼'} {Math.abs(p.changePercent).toFixed(0)}%
      </Typography>
    </Box>
  );

  return (
    <Grid container spacing={2} sx={{ mt: 2 }}>
      <Grid item xs={12} md={6}>
        <Paper variant="outlined" sx={{ p: 2 }}>
          <Typography variant="subtitle2" color="text.secondary" gutterBottom>📈 Biggest Gainers</Typography>
          {gainers.length === 0 ? (
            <Typography variant="body2" color="text.secondary">Not enough data yet</Typography>
          ) : (
            gainers.map((p) => <MoverRow key={p.productId} p={p} positive />)
          )}
        </Paper>
      </Grid>
      <Grid item xs={12} md={6}>
        <Paper variant="outlined" sx={{ p: 2 }}>
          <Typography variant="subtitle2" color="text.secondary" gutterBottom>📉 Biggest Decliners</Typography>
          {decliners.length === 0 ? (
            <Typography variant="body2" color="text.secondary">Not enough data yet</Typography>
          ) : (
            decliners.map((p) => <MoverRow key={p.productId} p={p} positive={false} />)
          )}
        </Paper>
      </Grid>
    </Grid>
  );
};

// ─── Sales Timing Heatmap ───────────────────────────────────────────────────
const SalesHeatmap = ({ data }) => {
  const grid = useMemo(() => {
    const g = {};
    for (let d = 1; d <= 7; d++) {
      for (let h = 0; h <= 23; h++) {
        g[`${d}_${h}`] = 0;
      }
    }
    for (const row of data) {
      g[`${row.dayOfWeek}_${row.hourOfDay}`] = row.transactionCount;
    }
    return g;
  }, [data]);

  const maxCount = useMemo(() => Math.max(1, ...data.map((r) => r.transactionCount)), [data]);

  const activeHours = useMemo(() => {
    const hours = new Set(data.map((r) => r.hourOfDay));
    if (hours.size === 0) return Array.from({ length: 24 }, (_, i) => i);
    const min = Math.max(0, Math.min(...hours) - 1);
    const max = Math.min(23, Math.max(...hours) + 1);
    return Array.from({ length: max - min + 1 }, (_, i) => min + i);
  }, [data]);

  const getColor = (count) => {
    if (count === 0) return '#f5f5f5';
    const intensity = count / maxCount;
    const r = Math.round(25 + (1 - intensity) * 180);
    const g = Math.round(118 + (1 - intensity) * 100);
    const b = Math.round(210 * (1 - intensity * 0.7));
    return `rgb(${r},${g},${b})`;
  };

  return (
    <Box sx={{ overflowX: 'auto' }}>
      <table style={{ borderCollapse: 'collapse', width: '100%' }}>
        <thead>
          <tr>
            <th style={{ width: 40, fontSize: 11, color: '#666', textAlign: 'right', paddingRight: 8 }}>Hour</th>
            {DAY_LABELS.map((d) => (
              <th key={d} style={{ fontSize: 11, color: '#666', textAlign: 'center', padding: '4px 2px' }}>{d}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {activeHours.map((h) => (
            <tr key={h}>
              <td style={{ fontSize: 10, color: '#999', textAlign: 'right', paddingRight: 8, paddingBottom: 2 }}>
                {h.toString().padStart(2, '0')}:00
              </td>
              {Array.from({ length: 7 }, (_, di) => di + 1).map((d) => {
                const count = grid[`${d}_${h}`];
                return (
                  <MuiTooltip
                    key={d}
                    title={`${DAY_LABELS[d - 1]} ${h.toString().padStart(2, '0')}:00 — ${count} transaction${count !== 1 ? 's' : ''}`}
                  >
                    <td
                      style={{
                        backgroundColor: getColor(count),
                        width: 'calc((100% - 48px) / 7)',
                        height: 20,
                        border: '1px solid #fff',
                        cursor: 'default',
                        textAlign: 'center',
                        fontSize: 9,
                        color: count > maxCount * 0.6 ? '#fff' : 'transparent',
                      }}
                    >
                      {count > 0 ? count : ''}
                    </td>
                  </MuiTooltip>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mt: 1, justifyContent: 'flex-end' }}>
        <Typography variant="caption" color="text.secondary">Low</Typography>
        {[0.1, 0.3, 0.5, 0.7, 0.9].map((v) => (
          <Box key={v} sx={{ width: 16, height: 16, backgroundColor: getColor(v * maxCount), border: '1px solid #eee', borderRadius: 0.5 }} />
        ))}
        <Typography variant="caption" color="text.secondary">High</Typography>
      </Box>
    </Box>
  );
};

// ─── Main Analytics Page ─────────────────────────────────────────────────────
const Analytics = () => {
  const [period, setPeriod] = useState('MONTH');
  const [compareMode, setCompareMode] = useState('PREVIOUS_PERIOD');
  const [compareCategoryIds, setCompareCategoryIds] = useState([]);
  
  const periodRange = useMemo(() => getPeriodRange(period), [period]);

  const [heatmapStart, setHeatmapStart] = useState(() => {
    const d = new Date(); d.setMonth(d.getMonth() - 1);
    return toDateString(d);
  });
  const [heatmapEnd, setHeatmapEnd] = useState(() => toDateString(new Date()));

  const [ltvSort, setLtvSort] = useState('totalSpent');
  const [ltvSortDir, setLtvSortDir] = useState('desc');

  const { data: profitSummaryData, isLoading: summaryLoading } = useQuery({
    queryKey: ['profitSummary', periodRange.startDate, periodRange.endDate],
    queryFn: () => reportService.getProfitSummary(periodRange.startDate, periodRange.endDate),
  });

  const { data: topProductsData, isLoading: topProductsLoading } = useQuery({
    queryKey: ['topProducts', period, compareMode],
    queryFn: () => reportService.getTopProducts(period, 10, compareMode),
  });

  const { data: topCategoriesData, isLoading: topCategoriesLoading } = useQuery({
    queryKey: ['topCategories', period, compareMode],
    queryFn: () => reportService.getTopCategories(period, compareMode),
  });

  const { data: profitTrendData, isLoading: profitTrendLoading } = useQuery({
    queryKey: ['profitTrend', period],
    queryFn: () => reportService.getProfitTrend(period),
  });

  const { data: profitBySupplierData, isLoading: profitBySupplierLoading } = useQuery({
    queryKey: ['profitBySupplier', periodRange.startDate, periodRange.endDate],
    queryFn: () => reportService.getProfitBySupplier(periodRange.startDate, periodRange.endDate),
  });

  const { data: salesTimingData, isLoading: salesTimingLoading } = useQuery({
    queryKey: ['salesTiming', heatmapStart, heatmapEnd],
    queryFn: () => reportService.getSalesTiming(heatmapStart, heatmapEnd),
    enabled: !!heatmapStart && !!heatmapEnd,
  });

  const { data: retentionData, isLoading: retentionLoading } = useQuery({
    queryKey: ['customerRetention'],
    queryFn: () => reportService.getCustomerRetention(),
  });

  const { data: ltvData, isLoading: ltvLoading } = useQuery({
    queryKey: ['customerLtv'],
    queryFn: () => reportService.getCustomerLifetimeValue(),
  });

  const { data: categoryComparisonData, isLoading: categoryComparisonLoading } = useQuery({
    queryKey: ['categoryComparison', compareCategoryIds, period],
    queryFn: () => reportService.compareCategories(compareCategoryIds, period),
    enabled: compareCategoryIds.length >= 2,
  });

  const summary = profitSummaryData?.data || {};
  const topProducts = topProductsData?.data || [];
  const topCategories = topCategoriesData?.data || [];
  const profitTrend = profitTrendData?.data || [];
  const profitBySupplier = profitBySupplierData?.data || [];
  const salesTiming = salesTimingData?.data || [];
  const retention = retentionData?.data;
  const ltvRaw = ltvData?.data || [];

  // Derive available categories from topCategories for the Autocomplete
  // (Note: If you have a separate "getAllCategories" service, use that instead for a complete list)
  const availableCategories = useMemo(() => 
    topCategories
      .filter(c => c.categoryId != null)
      .map(c => ({ id: c.categoryId, name: c.categoryName })), 
    [topCategories]
  );

  const sortedLtv = useMemo(() => {
    const arr = [...ltvRaw];
    arr.sort((a, b) => {
      const av = a[ltvSort] ?? 0;
      const bv = b[ltvSort] ?? 0;
      if (typeof av === 'string') return ltvSortDir === 'asc' ? av.localeCompare(bv) : bv.localeCompare(av);
      return ltvSortDir === 'asc' ? Number(av) - Number(bv) : Number(bv) - Number(av);
    });
    return arr;
  }, [ltvRaw, ltvSort, ltvSortDir]);

  const handleLtvSort = (col) => {
    if (ltvSort === col) setLtvSortDir(d => d === 'asc' ? 'desc' : 'asc');
    else { setLtvSort(col); setLtvSortDir('desc'); }
  };

  const summaryCards = useMemo(() => [
    { label: 'Total Revenue', value: summary.revenue || 0 },
    { label: 'Total COGS', value: summary.cogs || 0 },
    { label: 'Gross Profit', value: summary.grossProfit || 0 },
    { label: 'Gross Margin %', value: summary.grossMarginPercent || 0, formatter: (value) => `${value}%` },
  ], [summary]);

  const renderChartState = (loading) => loading
    ? <Box sx={{ display: 'flex', justifyContent: 'center', py: 8 }}><CircularProgress /></Box>
    : <Typography color="text.secondary">No sales data for this period.</Typography>;

  return (
    <Box>
      <Stack direction={{ xs: 'column', md: 'row' }} justifyContent="space-between" alignItems={{ xs: 'flex-start', md: 'center' }} spacing={2} sx={{ mb: 3 }}>
        <Box>
          <Typography variant="h4">Analytics</Typography>
          <Typography variant="body2" color="text.secondary">Business trends and deeper analysis (Admin)</Typography>
        </Box>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
          <ToggleButtonGroup value={period} exclusive onChange={(_, value) => value && setPeriod(value)}>
            <ToggleButton value="WEEK">Week</ToggleButton>
            <ToggleButton value="MONTH">Month</ToggleButton>
            <ToggleButton value="YEAR">Year</ToggleButton>
          </ToggleButtonGroup>
          <ToggleButtonGroup value={compareMode} exclusive onChange={(e, v) => v && setCompareMode(v)} size="small">
            <ToggleButton value="PREVIOUS_PERIOD">vs Previous Period</ToggleButton>
            <ToggleButton value="YEAR_AGO">vs Same Period Last Year</ToggleButton>
          </ToggleButtonGroup>
        </Stack>
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
        {/* Top Products + Top Movers */}
        <Grid item xs={12} md={6}>
          <Paper sx={{ p: 3 }}>
            <Typography variant="h6" gutterBottom>Top Products</Typography>
            {topProductsLoading ? <Box sx={{ display: 'flex', justifyContent: 'center', py: 8 }}><CircularProgress /></Box> : topProducts.length === 0 ? renderChartState(false) : (
              <ResponsiveContainer width="100%" height={250}>
                <BarChart data={topProducts} layout="vertical" margin={{ top: 5, right: 20, left: 10, bottom: 5 }}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis type="number" />
                  <YAxis type="category" dataKey="productName" width={140} />
                  <RechartsTooltip formatter={(value) => formatCurrency(value)} />
                  <Bar dataKey="revenue" fill="#1976d2" />
                </BarChart>
              </ResponsiveContainer>
            )}
            <TopMovers products={topProducts} />
          </Paper>
        </Grid>

        {/* Top Categories */}
        <Grid item xs={12} md={6}>
          <Paper sx={{ p: 3, height: 360 }}>
            <Typography variant="h6" gutterBottom>Top Categories</Typography>
            {topCategoriesLoading ? <Box sx={{ display: 'flex', justifyContent: 'center', py: 8 }}><CircularProgress /></Box> : topCategories.length === 0 ? renderChartState(false) : (
              <ResponsiveContainer width="100%" height="100%">
                <PieChart>
                  <Pie data={topCategories} dataKey="revenue" nameKey="categoryName" outerRadius={100}>
                    {topCategories.map((entry, index) => <Cell key={`${entry.categoryName}-${index}`} fill={COLORS[index % COLORS.length]} />)}
                  </Pie>
                  <RechartsTooltip formatter={(value) => formatCurrency(value)} />
                  <Legend />
                </PieChart>
              </ResponsiveContainer>
            )}
          </Paper>
        </Grid>

        {/* Compare Categories */}
        <Grid item xs={12}>
          <Paper sx={{ p: 3 }}>
            <Typography variant="h6" gutterBottom>Compare Categories</Typography>
            <Autocomplete
              multiple
              options={availableCategories}
              getOptionLabel={(c) => c.name}
              onChange={(e, selected) => setCompareCategoryIds(selected.map((c) => c.id))}
              renderInput={(params) => <TextField {...params} label="Select 2 or more categories" size="small" />}
              sx={{ mb: 2 }}
            />
            {compareCategoryIds.length < 2 ? (
              <Typography variant="body2" color="text.secondary">Pick at least two categories to compare.</Typography>
            ) : categoryComparisonLoading ? (
              <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}><CircularProgress /></Box>
            ) : (
              <ResponsiveContainer width="100%" height={300}>
                <LineChart data={categoryComparisonData?.data || []}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis dataKey="date" />
                  <YAxis />
                  <RechartsTooltip formatter={(value) => formatCurrency(value)} />
                  <Legend />
                  {compareCategoryIds.map((id, idx) => (
                    <Line
                      key={id}
                      type="monotone"
                      dataKey={`category_${id}`}
                      name={availableCategories.find((c) => c.id === id)?.name || `Category ${id}`}
                      stroke={COLORS[idx % COLORS.length]}
                      strokeWidth={2}
                    />
                  ))}
                </LineChart>
              </ResponsiveContainer>
            )}
          </Paper>
        </Grid>

        {/* Profit Trend */}
        <Grid item xs={12}>
          <Paper sx={{ p: 3, height: 360 }}>
            <Typography variant="h6" gutterBottom>Profit Trend</Typography>
            {profitTrendLoading ? <Box sx={{ display: 'flex', justifyContent: 'center', py: 8 }}><CircularProgress /></Box> : profitTrend.length === 0 ? renderChartState(false) : (
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={profitTrend}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis dataKey="periodLabel" />
                  <YAxis />
                  <RechartsTooltip formatter={(value) => formatCurrency(value)} />
                  <Legend />
                  <Line type="monotone" dataKey="revenue" stroke="#1976d2" strokeWidth={2} />
                  <Line type="monotone" dataKey="grossProfit" stroke="#2e7d32" strokeWidth={2} />
                </LineChart>
              </ResponsiveContainer>
            )}
          </Paper>
        </Grid>

        {/* Sales Timing Heatmap */}
        <Grid item xs={12}>
          <Paper sx={{ p: 3 }}>
            <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} spacing={2} sx={{ mb: 2 }}>
              <Box>
                <Typography variant="h6">Sales Timing Heatmap</Typography>
                <Typography variant="body2" color="text.secondary">
                  Transaction count by day of week and hour — darker = busier
                </Typography>
              </Box>
              <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap">
                <TextField
                  label="From"
                  type="date"
                  size="small"
                  value={heatmapStart}
                  onChange={(e) => setHeatmapStart(e.target.value)}
                  InputLabelProps={{ shrink: true }}
                  sx={{ width: 150 }}
                />
                <TextField
                  label="To"
                  type="date"
                  size="small"
                  value={heatmapEnd}
                  onChange={(e) => setHeatmapEnd(e.target.value)}
                  InputLabelProps={{ shrink: true }}
                  sx={{ width: 150 }}
                />
              </Stack>
            </Stack>
            {salesTimingLoading ? (
              <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}><CircularProgress /></Box>
            ) : salesTiming.length === 0 ? (
              <Alert severity="info">No sales data found for this period. Try a wider date range.</Alert>
            ) : (
              <SalesHeatmap data={salesTiming} />
            )}
          </Paper>
        </Grid>

        {/* Profit by Supplier */}
        <Grid item xs={12}>
          <Paper sx={{ p: 3 }}>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 2 }}>
              <Typography variant="h6">Profit by Supplier (Estimate)</Typography>
              <MuiTooltip title="Based on blended average cost, not exact per-batch tracking. Actual recorded sale profit uses weighted-average costPriceAtSale.">
                <IconButton size="small"><InfoOutlined fontSize="small" /></IconButton>
              </MuiTooltip>
            </Box>
            {profitBySupplierLoading ? (
              <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}><CircularProgress /></Box>
            ) : profitBySupplier.length === 0 ? (
              <Typography color="text.secondary">No supplier data for this period.</Typography>
            ) : (
              <TableContainer>
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell>Supplier</TableCell>
                      <TableCell align="right">Supplied (Cost)</TableCell>
                      <TableCell align="right">Est. Revenue</TableCell>
                      <TableCell align="right">Est. Profit</TableCell>
                      <TableCell align="right">Est. Margin</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {profitBySupplier.map((s) => (
                      <TableRow key={s.supplierId}>
                        <TableCell>{s.supplierName}</TableCell>
                        <TableCell align="right">{formatCurrency(s.totalSuppliedCost)}</TableCell>
                        <TableCell align="right">{formatCurrency(s.estimatedRevenue)}</TableCell>
                        <TableCell align="right">{formatCurrency(s.estimatedProfit)}</TableCell>
                        <TableCell align="right">
                          <Chip label={`${s.estimatedMarginPercent}%`} size="small"
                            color={s.estimatedMarginPercent >= 0 ? 'success' : 'error'} variant="outlined" />
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
            )}
          </Paper>
        </Grid>

        {/* Customer Retention */}
        <Grid item xs={12} md={4}>
          <Paper sx={{ p: 3, height: '100%' }}>
            <Typography variant="h6" gutterBottom>Customer Retention</Typography>
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 2 }}>
              Only counts sales tied to registered customers. Walk-in / quick-typed names have no stable identity across visits.
            </Typography>
            {retentionLoading ? (
              <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}><CircularProgress /></Box>
            ) : !retention ? (
              <Typography color="text.secondary">No retention data available.</Typography>
            ) : (
              <>
                <Grid container spacing={2} sx={{ mb: 3 }}>
                  <Grid item xs={6}>
                    <Paper variant="outlined" sx={{ p: 2, textAlign: 'center' }}>
                      <Typography variant="h4" color="success.main" fontWeight="bold">{retention.returningCount}</Typography>
                      <Typography variant="body2" color="text.secondary">Returning This Month</Typography>
                    </Paper>
                  </Grid>
                  <Grid item xs={6}>
                    <Paper variant="outlined" sx={{ p: 2, textAlign: 'center' }}>
                      <Typography variant="h4" color="warning.main" fontWeight="bold">{retention.lapsedCount}</Typography>
                      <Typography variant="body2" color="text.secondary">Lapsed This Month</Typography>
                    </Paper>
                  </Grid>
                  <Grid item xs={6}>
                    <Paper variant="outlined" sx={{ p: 2, textAlign: 'center' }}>
                      <Typography variant="h5">{retention.activeLastMonth}</Typography>
                      <Typography variant="body2" color="text.secondary">Active Last Month</Typography>
                    </Paper>
                  </Grid>
                  <Grid item xs={6}>
                    <Paper variant="outlined" sx={{ p: 2, textAlign: 'center' }}>
                      <Typography variant="h5">{retention.activeThisMonth}</Typography>
                      <Typography variant="body2" color="text.secondary">Active This Month</Typography>
                    </Paper>
                  </Grid>
                </Grid>

                {retention.lapsedCustomers?.length > 0 && (
                  <>
                    <Typography variant="subtitle2" gutterBottom>
                      Lapsed Customers — sorted by value (follow up first)
                    </Typography>
                    <TableContainer sx={{ maxHeight: 300 }}>
                      <Table size="small" stickyHeader>
                        <TableHead>
                          <TableRow>
                            <TableCell>Customer</TableCell>
                            <TableCell>Phone</TableCell>
                            <TableCell align="right">Total Spend</TableCell>
                            <TableCell>Last Purchase</TableCell>
                          </TableRow>
                        </TableHead>
                        <TableBody>
                          {retention.lapsedCustomers.map((c) => (
                            <TableRow key={c.customerId} hover>
                              <TableCell>{c.customerName}</TableCell>
                              <TableCell>{c.phone || '—'}</TableCell>
                              <TableCell align="right">{formatCurrency(c.totalHistoricalSpend)}</TableCell>
                              <TableCell>
                                {c.lastPurchaseDate ? new Date(c.lastPurchaseDate).toLocaleDateString() : '—'}
                              </TableCell>
                            </TableRow>
                          ))}
                        </TableBody>
                      </Table>
                    </TableContainer>
                  </>
                )}
              </>
            )}
          </Paper>
        </Grid>

        {/* Customer Lifetime Value */}
        <Grid item xs={12} md={8}>
          <Paper sx={{ p: 3 }}>
            <Typography variant="h6" gutterBottom>Customer Lifetime Value</Typography>
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 2 }}>
              Only counts registered customers. Click column headers to sort.
            </Typography>
            {ltvLoading ? (
              <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}><CircularProgress /></Box>
            ) : sortedLtv.length === 0 ? (
              <Typography color="text.secondary">No registered-customer purchase data yet.</Typography>
            ) : (
              <TableContainer sx={{ maxHeight: 400 }}>
                <Table size="small" stickyHeader>
                  <TableHead>
                    <TableRow>
                      <TableCell>Customer</TableCell>
                      <TableCell align="right">
                        <TableSortLabel active={ltvSort === 'totalSpent'} direction={ltvSort === 'totalSpent' ? ltvSortDir : 'desc'} onClick={() => handleLtvSort('totalSpent')}>
                          Total Spent
                        </TableSortLabel>
                      </TableCell>
                      <TableCell align="right">
                        <TableSortLabel active={ltvSort === 'visitCount'} direction={ltvSort === 'visitCount' ? ltvSortDir : 'desc'} onClick={() => handleLtvSort('visitCount')}>
                          Visits
                        </TableSortLabel>
                      </TableCell>
                      <TableCell align="right">
                        <TableSortLabel active={ltvSort === 'averageBasketSize'} direction={ltvSort === 'averageBasketSize' ? ltvSortDir : 'desc'} onClick={() => handleLtvSort('averageBasketSize')}>
                          Avg Basket
                        </TableSortLabel>
                      </TableCell>
                      <TableCell>First Purchase</TableCell>
                      <TableCell>Last Purchase</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {sortedLtv.map((c) => (
                      <TableRow key={c.customerId} hover>
                        <TableCell>
                          <Typography variant="body2" fontWeight="medium">{c.customerName}</Typography>
                          {c.phone && <Typography variant="caption" color="text.secondary">{c.phone}</Typography>}
                        </TableCell>
                        <TableCell align="right">{formatCurrency(c.totalSpent)}</TableCell>
                        <TableCell align="right">{c.visitCount}</TableCell>
                        <TableCell align="right">{formatCurrency(c.averageBasketSize)}</TableCell>
                        <TableCell>{c.firstPurchaseDate ? new Date(c.firstPurchaseDate).toLocaleDateString() : '—'}</TableCell>
                        <TableCell>{c.lastPurchaseDate ? new Date(c.lastPurchaseDate).toLocaleDateString() : '—'}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
            )}
          </Paper>
        </Grid>
      </Grid>
    </Box>
  );
};

export default Analytics;  