import { useState, useMemo } from 'react';
import {
  Box, Typography, Paper, Grid, TextField, Button, Table, TableBody, TableCell,
  TableHead, TableRow, TableContainer, Stack, CircularProgress, Chip, TableSortLabel, Alert,
} from '@mui/material';
import { Download } from '@mui/icons-material';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { ComposedChart, Bar, Line, XAxis, YAxis, CartesianGrid, Tooltip as ChartTooltip, ResponsiveContainer } from 'recharts';
import { reportService } from '../api/services';
import { formatCurrency, formatDate, toLocalDateString, downloadCsv } from '../utils/helpers';

import { useNavigate } from 'react-router-dom';
import SalesHeatmap from '../components/SalesHeatmap';

const toDateStr = (date) => toLocalDateString(date);

const ReportTypes = ['daily', 'inventory', 'cashier', 'trend', 'products', 'deadstock', 'ltv', 'salesTiming', 'retention'];

const Reports = () => {
  const { t } = useTranslation('reports');
  const [dateRange, setDateRange] = useState({ start: toDateStr(new Date()), end: toDateStr(new Date()) });
  const [reportType, setReportType] = useState('daily');
  const [trendDays, setTrendDays] = useState(7);
  const [topLimit, setTopLimit] = useState(10);
  const [deadStockDays, setDeadStockDays] = useState(30);
  const navigate = useNavigate();

  // Cashier sort state
  const [cashierSort, setCashierSort] = useState('totalSales');
  const [cashierSortDir, setCashierSortDir] = useState('desc');

  // LTV sort state
  const [ltvSort, setLtvSort] = useState('totalSpent');
  const [ltvSortDir, setLtvSortDir] = useState('desc');

  const { data: dailyData } = useQuery({
    queryKey: ['dailySales', dateRange.start],
    queryFn: () => reportService.getDailySales(dateRange.start),
    enabled: reportType === 'daily',
  });

  const { data: inventoryData } = useQuery({
    queryKey: ['inventoryReport'],
    queryFn: () => reportService.getInventoryReport(),
    enabled: reportType === 'inventory',
  });

  const { data: cashierData, isLoading: cashierLoading } = useQuery({
    queryKey: ['cashierPerformance', dateRange.start, dateRange.end],
    queryFn: () => reportService.getCashierPerformance(dateRange.start, dateRange.end),
    enabled: reportType === 'cashier',
  });

  const { data: trendData, isLoading: trendLoading } = useQuery({
    queryKey: ['salesTrend', trendDays],
    queryFn: () => reportService.getSalesTrend(trendDays),
    enabled: reportType === 'trend',
  });

  const { data: productsData, isLoading: productsLoading } = useQuery({
    queryKey: ['topSellingProducts', topLimit, dateRange.start, dateRange.end],
    queryFn: () => reportService.getTopSellingProducts(topLimit, dateRange.start, dateRange.end),
    enabled: reportType === 'products',
  });

  const { data: deadStockData, isLoading: deadStockLoading } = useQuery({
    queryKey: ['deadStock', deadStockDays],
    queryFn: () => reportService.getDeadStock(deadStockDays),
    enabled: reportType === 'deadstock',
  });

  const { data: salesTimingData, isLoading: salesTimingLoading } = useQuery({
    queryKey: ['salesTiming', dateRange.start, dateRange.end],
    queryFn: () => reportService.getSalesTiming(dateRange.start, dateRange.end),
    enabled: reportType === 'salesTiming',
  });

  const { data: ltvData, isLoading: ltvLoading } = useQuery({
    queryKey: ['customerLtv'],
    queryFn: () => reportService.getCustomerLifetimeValue(),
    enabled: reportType === 'ltv',
  });

  const { data: retentionData, isLoading: retentionLoading } = useQuery({
    queryKey: ['customerRetention'],
    queryFn: () => reportService.getCustomerRetention(),
    enabled: reportType === 'retention',
  });

  const dailySales = dailyData?.data || {};
  const inventory = inventoryData?.data || {};
  const cashierRaw = cashierData?.data || [];
  const trendRaw = trendData?.data || [];
  const productsRaw = productsData?.data || [];
  const deadStockRaw = deadStockData?.data || [];
  const salesTiming = salesTimingData?.data || [];
  const ltvRaw = ltvData?.data || [];
  const retention = retentionData?.data;

  // Sort cashier table
  const cashierRows = useMemo(() => {
    const arr = [...cashierRaw];
    arr.sort((a, b) => {
      const av = Number(a[cashierSort]) || 0;
      const bv = Number(b[cashierSort]) || 0;
      return cashierSortDir === 'asc' ? av - bv : bv - av;
    });
    return arr;
  }, [cashierRaw, cashierSort, cashierSortDir]);

  const handleCashierSort = (col) => {
    if (cashierSort === col) setCashierSortDir(d => d === 'asc' ? 'desc' : 'asc');
    else { setCashierSort(col); setCashierSortDir('desc'); }
  };

  // Sort LTV table
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

  const exportCsv = (filename, rows, headers) => downloadCsv(filename, rows, headers);

  const spinner = <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}><CircularProgress /></Box>;

  const showRange = reportType === 'cashier' || reportType === 'products' || reportType === 'salesTiming';
  const showSingleDate = reportType === 'daily';
  const showTrendDays = reportType === 'trend';
  const showThreshold = reportType === 'deadstock';

  return (
    <Box>
      
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>{t('reports_subtitle')}</Typography>

      <Paper sx={{ p: 2, mb: 3 }}>
        <Grid container spacing={2} alignItems="center">
          <Grid item xs={12} md={3}>
            <TextField fullWidth select label={t('report_type')} value={reportType} onChange={(e) => setReportType(e.target.value)} SelectProps={{ native: true }}>
              {ReportTypes.map((rt) => <option key={rt} value={rt}>{t(`report_type_${rt}`)}</option>)}
            </TextField>
          </Grid>
          {showRange && (
            <>
              <Grid item xs={12} md={3}>
                <TextField fullWidth label={t('start_date')} type="date" value={dateRange.start} onChange={(e) => setDateRange({ ...dateRange, start: e.target.value })} InputLabelProps={{ shrink: true }} />
              </Grid>
              <Grid item xs={12} md={3}>
                <TextField fullWidth label={t('end_date')} type="date" value={dateRange.end} onChange={(e) => setDateRange({ ...dateRange, end: e.target.value })} InputLabelProps={{ shrink: true }} />
              </Grid>
            </>
          )}
          {showSingleDate && (
            <Grid item xs={12} md={3}>
              <TextField fullWidth label={t('start_date')} type="date" value={dateRange.start} onChange={(e) => setDateRange({ ...dateRange, start: e.target.value })} InputLabelProps={{ shrink: true }} />
            </Grid>
          )}
          {showTrendDays && (
            <Grid item xs={12} md={3}>
              <TextField fullWidth label={t('days')} type="number" inputProps={{ min: 1, max: 90 }} value={trendDays} onChange={(e) => setTrendDays(Number(e.target.value) || 7)} />
            </Grid>
          )}
          {showThreshold && (
            <Grid item xs={12} md={3}>
              <TextField fullWidth label={t('dead_stock_threshold')} type="number" inputProps={{ min: 1, max: 999 }} value={deadStockDays} onChange={(e) => setDeadStockDays(Number(e.target.value) || 30)} />
            </Grid>
          )}
        </Grid>
      </Paper>

      {reportType === 'daily' && (
        <Paper sx={{ p: 3 }}>
          <Typography variant="h6" gutterBottom>{t('daily_sales_report_date', { date: formatDate(dateRange.start) })}</Typography>
          <Grid container spacing={3} sx={{ mt: 1 }}>
          <Grid item xs={12} md={4}>
            <Paper sx={{ p: 2, textAlign: 'center' }}>
              <Typography variant="h4">
                {dailySales.totalTransactions || 0}
              </Typography>
              <Typography color="text.secondary">
                {t('sales_today')}
              </Typography>
            </Paper>
          </Grid>

          <Grid item xs={12} md={4}>
            <Paper sx={{ p: 2, textAlign: 'center', cursor: 'pointer' }} onClick={() => navigate('/dashboard')}>
              <Typography variant="body2" color="text.secondary">
                {t('see_dashboard_revenue')}
              </Typography>
              <Typography variant="caption" color="primary">{t('go_to_dashboard')}</Typography>
            </Paper>
          </Grid>

          <Grid item xs={12} md={4}>
            <Paper sx={{ p: 2, textAlign: 'center' }}>
              <Typography variant="h4">
                {formatCurrency(dailySales.averageTransactionValue || 0)}
              </Typography>
              <Typography color="text.secondary">
                {t('average_sale')}
              </Typography>
            </Paper>
          </Grid>
        </Grid>
        </Paper>
      )}

      {reportType === 'inventory' && (
        <Paper sx={{ p: 3 }}>
          <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} spacing={1} sx={{ mb: 1 }}>
            <Typography variant="h6">{t('inventory_report_title')}</Typography>
            {(inventory.lowStockItems || []).length > 0 && (
              <Button size="small" startIcon={<Download />} onClick={() => exportCsv(
                'low-stock-items',
                (inventory.lowStockItems || []).map((it) => ({ name: it.productName, stock: it.currentStock, min: it.minStockLevel, shortage: it.shortage })),
                [t('product_name'), t('current_stock'), t('min_stock_level'), t('shortage')],
              )}>{t('export_csv')}</Button>
            )}
          </Stack>
          <Grid container spacing={3} sx={{ mt: 1 }}>
          <Grid item xs={12} md={4}>
            <Paper sx={{ p: 2, textAlign: 'center' }}>
              <Typography variant="h4">
                {inventory.totalProducts || 0}
              </Typography>
              <Typography color="text.secondary">
                {t('total_products')}
              </Typography>
            </Paper>
          </Grid>

          <Grid item xs={12} md={4}>
            <Paper sx={{ p: 2, textAlign: 'center' }}>
              <Typography variant="h4">
                {formatCurrency(inventory.totalInventoryValue || 0)}
              </Typography>
              <Typography color="text.secondary">
                {t('total_inventory_value')}
              </Typography>
            </Paper>
          </Grid>

          <Grid item xs={12} md={4}>
            <Paper sx={{ p: 2, textAlign: 'center' }}>
              <Typography variant="h4" color="error">
                {inventory.lowStockProductsCount || 0}
              </Typography>
              <Typography color="text.secondary">
                {t('low_stock_products')}
              </Typography>
            </Paper>
          </Grid>
        </Grid>

        {(inventory.lowStockItems || []).length > 0 && (
          <TableContainer sx={{ mt: 3 }}>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>{t('product_name')}</TableCell>
                  <TableCell align="right">{t('current_stock')}</TableCell>
                  <TableCell align="right">{t('min_stock_level')}</TableCell>
                  <TableCell align="right">{t('shortage')}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {(inventory.lowStockItems || []).map((it) => (
                  <TableRow key={it.productId} hover>
                    <TableCell>{it.productName}</TableCell>
                    <TableCell align="right">{it.currentStock}</TableCell>
                    <TableCell align="right">{it.minStockLevel}</TableCell>
                    <TableCell align="right"><Chip label={it.shortage} size="small" color="error" variant="outlined" /></TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        )}
        </Paper>
      )}

      {/* ------------------------------------------------------------------ */}
      {/* §6 Cashier Performance — fairness-adjusted view                    */}
      {/* ------------------------------------------------------------------ */}
      {reportType === 'cashier' && (
        <Paper sx={{ p: 3 }}>
          <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} spacing={1} sx={{ mb: 2 }}>
            <Box>
              <Typography variant="h6">{t('cashier_performance_title')}</Typography>
              <Typography variant="body2" color="text.secondary">
                {t('cashier_sort_hint')}
              </Typography>
            </Box>
            {cashierRows.length > 0 && (
              <Button size="small" startIcon={<Download />} onClick={() => exportCsv(
                'cashier-performance',
                cashierRows.map((r) => ({ id: r.cashierId, sales: r.totalSales, txn: r.transactionCount, avg: r.averageTransactionValue, items: r.totalItems })),
                [t('cashier_id'), t('total_sales'), t('transactions'), t('avg_transaction_value'), t('total_items')],
              )}>{t('export_csv')}</Button>
            )}
          </Stack>

          {cashierLoading ? (
            spinner
          ) : cashierRows.length === 0 ? (
            <Typography color="text.secondary">{t('no_sales_data')}</Typography>
          ) : (
            <TableContainer>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>{t('cashier_id')}</TableCell>
                    <TableCell align="right">
                      <TableSortLabel
                        active={cashierSort === 'totalSales'}
                        direction={cashierSort === 'totalSales' ? cashierSortDir : 'desc'}
                        onClick={() => handleCashierSort('totalSales')}
                      >
                        {t('total_sales')}
                      </TableSortLabel>
                    </TableCell>
                    <TableCell align="right">
                      <TableSortLabel
                        active={cashierSort === 'transactionCount'}
                        direction={cashierSort === 'transactionCount' ? cashierSortDir : 'desc'}
                        onClick={() => handleCashierSort('transactionCount')}
                      >
                        {t('transactions')}
                      </TableSortLabel>
                    </TableCell>
                    <TableCell align="right">
                      <TableSortLabel
                        active={cashierSort === 'averageTransactionValue'}
                        direction={cashierSort === 'averageTransactionValue' ? cashierSortDir : 'desc'}
                        onClick={() => handleCashierSort('averageTransactionValue')}
                      >
                        {t('avg_transaction_value')}
                      </TableSortLabel>
                    </TableCell>
                    <TableCell align="right">
                      <TableSortLabel
                        active={cashierSort === 'averageItemsPerSale'}
                        direction={cashierSort === 'averageItemsPerSale' ? cashierSortDir : 'desc'}
                        onClick={() => handleCashierSort('averageItemsPerSale')}
                      >
                        {t('avg_items_per_sale')}
                      </TableSortLabel>
                    </TableCell>
                    <TableCell align="right">{t('total_items')}</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {cashierRows.map((row, idx) => (
                    <TableRow key={row.cashierId ?? idx} hover>
                      <TableCell>
                        <Chip label={t('cashier_label', { id: row.cashierId })} size="small" variant="outlined" />
                      </TableCell>
                      <TableCell align="right">{formatCurrency(row.totalSales)}</TableCell>
                      <TableCell align="right">{row.transactionCount}</TableCell>
                      <TableCell align="right">
                        <Typography
                          variant="body2"
                          fontWeight={cashierSort === 'averageTransactionValue' ? 'bold' : 'normal'}
                          color={cashierSort === 'averageTransactionValue' ? 'primary.main' : 'inherit'}
                        >
                          {formatCurrency(row.averageTransactionValue)}
                        </Typography>
                      </TableCell>
                      <TableCell align="right">
                        <Typography
                          variant="body2"
                          fontWeight={cashierSort === 'averageItemsPerSale' ? 'bold' : 'normal'}
                          color={cashierSort === 'averageItemsPerSale' ? 'primary.main' : 'inherit'}
                        >
                          {typeof row.averageItemsPerSale === 'number' ? row.averageItemsPerSale.toFixed(2) : '—'}
                        </Typography>
                      </TableCell>
                      <TableCell align="right">{row.totalItems}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </Paper>
      )}

      {/* ------------------------------------------------------------------ */}
      {/* Sales Trend — last N days                                          */}
      {/* ------------------------------------------------------------------ */}
      {reportType === 'trend' && (
        <Paper sx={{ p: 3 }}>
          <Typography variant="h6" gutterBottom>{t('sales_trend_title')}</Typography>
          {trendLoading ? spinner : trendRaw.length === 0 ? (
            <Typography color="text.secondary">{t('no_sales_data')}</Typography>
          ) : (
            <ResponsiveContainer width="100%" height={320}>
              <ComposedChart data={trendRaw} margin={{ top: 5, right: 10, left: 10, bottom: 5 }}>
                <CartesianGrid strokeDasharray="3 3" />
                <XAxis dataKey="date" tickFormatter={(d) => String(d).slice(5)} />
                <YAxis yAxisId="rev" tickFormatter={(v) => formatCurrency(v)} width={90} />
                <YAxis yAxisId="count" orientation="right" />
                <ChartTooltip
                  labelFormatter={(label) => formatDate(label)}
                  formatter={(value, name) => (name === t('total_revenue') ? formatCurrency(value) : value)}
                />
                <Bar yAxisId="count" dataKey="transactionCount" name={t('transactions')} fill="#ed6c02" radius={[3, 3, 0, 0]} />
                <Line yAxisId="rev" type="monotone" dataKey="totalSales" name={t('total_revenue')} stroke="#1976d2" strokeWidth={2} dot={false} />
              </ComposedChart>
            </ResponsiveContainer>
          )}
        </Paper>
      )}

      {/* ------------------------------------------------------------------ */}
      {/* Top Selling Products                                               */}
      {/* ------------------------------------------------------------------ */}
      {reportType === 'products' && (
        <Paper sx={{ p: 3 }}>
          <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} spacing={1} sx={{ mb: 2 }}>
            <Typography variant="h6">{t('report_type_products')}</Typography>
            {productsRaw.length > 0 && (
              <Button size="small" startIcon={<Download />} onClick={() => exportCsv(
                'top-selling-products',
                productsRaw.map((p, i) => ({ rank: i + 1, name: p.productName, qty: p.totalQuantitySold, revenue: p.totalRevenue })),
                [t('rank'), t('product_name'), t('quantity_sold'), t('total_revenue')],
              )}>{t('export_csv')}</Button>
            )}
          </Stack>
          {productsLoading ? spinner : productsRaw.length === 0 ? (
            <Typography color="text.secondary">{t('no_sales_data')}</Typography>
          ) : (
            <TableContainer>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell width={48}>{t('rank')}</TableCell>
                    <TableCell>{t('product_name')}</TableCell>
                    <TableCell align="right">{t('quantity_sold')}</TableCell>
                    <TableCell align="right">{t('total_revenue')}</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {productsRaw.map((p, i) => (
                    <TableRow key={p.productId ?? i} hover>
                      <TableCell><Chip label={i + 1} size="small" color={i < 3 ? 'primary' : 'default'} /></TableCell>
                      <TableCell>{p.productName}</TableCell>
                      <TableCell align="right">{p.totalQuantitySold}</TableCell>
                      <TableCell align="right">{formatCurrency(p.totalRevenue)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </Paper>
      )}

      {/* ------------------------------------------------------------------ */}
      {/* Dead Stock                                                         */}
      {/* ------------------------------------------------------------------ */}
      {reportType === 'deadstock' && (
        <Paper sx={{ p: 3 }}>
          <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} spacing={1} sx={{ mb: 2 }}>
            <Typography variant="h6">{t('dead_stock_title')}</Typography>
            {deadStockRaw.length > 0 && (
              <Button size="small" startIcon={<Download />} onClick={() => exportCsv(
                'dead-stock',
                deadStockRaw.map((d) => ({ name: d.productName, category: d.categoryName, stock: d.stockQuantity, value: d.stockValue, lastSold: d.lastSoldDate ? new Date(d.lastSoldDate).toISOString().slice(0, 10) : '', days: d.daysSinceLastSale })),
                [t('product_name'), t('category'), t('current_stock'), t('inventory_value'), t('last_sold_date'), t('days_since_last_sale')],
              )}>{t('export_csv')}</Button>
            )}
          </Stack>
          {deadStockLoading ? spinner : deadStockRaw.length === 0 ? (
            <Alert severity="info">{t('no_sales_data_wider_range')}</Alert>
          ) : (
            <TableContainer>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>{t('product_name')}</TableCell>
                    <TableCell>{t('category')}</TableCell>
                    <TableCell align="right">{t('current_stock')}</TableCell>
                    <TableCell align="right">{t('inventory_value')}</TableCell>
                    <TableCell>{t('last_sold_date')}</TableCell>
                    <TableCell align="right">{t('days_since_last_sale')}</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {deadStockRaw.map((d) => (
                    <TableRow key={d.productId} hover>
                      <TableCell>{d.productName}</TableCell>
                      <TableCell><Chip label={d.categoryName || t('uncategorized')} size="small" variant="outlined" /></TableCell>
                      <TableCell align="right">{d.stockQuantity}</TableCell>
                      <TableCell align="right">{formatCurrency(d.stockValue)}</TableCell>
                      <TableCell>{d.lastSoldDate ? formatDate(d.lastSoldDate) : '—'}</TableCell>
                      <TableCell align="right"><Chip label={d.daysSinceLastSale} size="small" color={d.daysSinceLastSale >= 90 ? 'error' : 'warning'} variant="outlined" /></TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </Paper>
      )}

      {/* ------------------------------------------------------------------ */}
      {/* Customer Lifetime Value                                            */}
      {/* ------------------------------------------------------------------ */}
      {reportType === 'ltv' && (
        <Paper sx={{ p: 3 }}>
          <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} spacing={1} sx={{ mb: 1 }}>
            <Box>
              <Typography variant="h6">{t('customer_lifetime_value')}</Typography>
              <Typography variant="caption" color="text.secondary">{t('ltv_description')}</Typography>
            </Box>
            {sortedLtv.length > 0 && (
              <Button size="small" startIcon={<Download />} onClick={() => exportCsv(
                'customer-lifetime-value',
                sortedLtv.map((c) => ({ name: c.customerName, phone: c.phone, spent: c.totalSpent, visits: c.visitCount, basket: c.averageBasketSize, first: c.firstPurchaseDate ? new Date(c.firstPurchaseDate).toISOString().slice(0, 10) : '', last: c.lastPurchaseDate ? new Date(c.lastPurchaseDate).toISOString().slice(0, 10) : '' })),
                [t('customer'), t('phone'), t('total_spend'), t('visits'), t('avg_basket'), t('first_purchase'), t('last_purchase')],
              )}>{t('export_csv')}</Button>
            )}
          </Stack>
          {ltvLoading ? spinner : sortedLtv.length === 0 ? (
            <Typography color="text.secondary">{t('no_ltv_data')}</Typography>
          ) : (
            <TableContainer>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>{t('customer')}</TableCell>
                    <TableCell align="right">
                      <TableSortLabel active={ltvSort === 'totalSpent'} direction={ltvSort === 'totalSpent' ? ltvSortDir : 'desc'} onClick={() => handleLtvSort('totalSpent')}>
                        {t('total_spent')}
                      </TableSortLabel>
                    </TableCell>
                    <TableCell align="right">
                      <TableSortLabel active={ltvSort === 'visitCount'} direction={ltvSort === 'visitCount' ? ltvSortDir : 'desc'} onClick={() => handleLtvSort('visitCount')}>
                        {t('visits')}
                      </TableSortLabel>
                    </TableCell>
                    <TableCell align="right">
                      <TableSortLabel active={ltvSort === 'averageBasketSize'} direction={ltvSort === 'averageBasketSize' ? ltvSortDir : 'desc'} onClick={() => handleLtvSort('averageBasketSize')}>
                        {t('avg_basket')}
                      </TableSortLabel>
                    </TableCell>
                    <TableCell>{t('first_purchase')}</TableCell>
                    <TableCell>{t('last_purchase')}</TableCell>
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
                      <TableCell>{c.firstPurchaseDate ? formatDate(c.firstPurchaseDate) : '—'}</TableCell>
                      <TableCell>{c.lastPurchaseDate ? formatDate(c.lastPurchaseDate) : '—'}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </Paper>
      )}

      {/* ------------------------------------------------------------------ */}
      {/* Sales Timing Heatmap                                               */}
      {/* ------------------------------------------------------------------ */}
      {reportType === 'salesTiming' && (
        <Paper sx={{ p: 3 }}>
          <Typography variant="h6" gutterBottom>{t('sales_timing_heatmap')}</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>{t('heatmap_description')}</Typography>
          {salesTimingLoading ? spinner : salesTiming.length === 0 ? (
            <Alert severity="info">{t('no_sales_data_wider_range')}</Alert>
          ) : (
            <SalesHeatmap data={salesTiming} />
          )}
        </Paper>
      )}

      {/* ------------------------------------------------------------------ */}
      {/* Customer Retention                                                 */}
      {/* ------------------------------------------------------------------ */}
      {reportType === 'retention' && (
        <Box>
          <Paper sx={{ p: 3 }}>
            <Typography variant="h6" gutterBottom>{t('customer_retention')}</Typography>
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 2 }}>
              {t('retention_description')}
            </Typography>
            {retentionLoading ? spinner : !retention ? (
              <Typography color="text.secondary">{t('no_retention_data')}</Typography>
            ) : (
              <Grid container spacing={2} sx={{ mb: 3 }}>
                <Grid item xs={6} md={3}>
                  <Paper variant="outlined" sx={{ p: 2, textAlign: 'center' }}>
                    <Typography variant="h4" color="success.main" fontWeight="bold">{retention.returningCount}</Typography>
                    <Typography variant="body2" color="text.secondary">{t('returning_this_month')}</Typography>
                  </Paper>
                </Grid>
                <Grid item xs={6} md={3}>
                  <Paper variant="outlined" sx={{ p: 2, textAlign: 'center' }}>
                    <Typography variant="h4" color="warning.main" fontWeight="bold">{retention.lapsedCount}</Typography>
                    <Typography variant="body2" color="text.secondary">{t('lapsed_this_month')}</Typography>
                  </Paper>
                </Grid>
                <Grid item xs={6} md={3}>
                  <Paper variant="outlined" sx={{ p: 2, textAlign: 'center' }}>
                    <Typography variant="h5">{retention.activeLastMonth}</Typography>
                    <Typography variant="body2" color="text.secondary">{t('active_last_month')}</Typography>
                  </Paper>
                </Grid>
                <Grid item xs={6} md={3}>
                  <Paper variant="outlined" sx={{ p: 2, textAlign: 'center' }}>
                    <Typography variant="h5">{retention.activeThisMonth}</Typography>
                    <Typography variant="body2" color="text.secondary">{t('active_this_month')}</Typography>
                  </Paper>
                </Grid>
              </Grid>
            )}
          </Paper>

          {!retentionLoading && retention?.lapsedCustomers?.length > 0 && (
            <Paper sx={{ p: 3, mt: 3 }}>
              <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} spacing={1} sx={{ mb: 2 }}>
                <Typography variant="h6">{t('lapsed_customers_sorted')}</Typography>
                <Button size="small" startIcon={<Download />} onClick={() => exportCsv(
                  'lapsed-customers',
                  retention.lapsedCustomers.map((c) => ({ name: c.customerName, phone: c.phone, spent: c.totalHistoricalSpend, last: c.lastPurchaseDate ? new Date(c.lastPurchaseDate).toISOString().slice(0, 10) : '' })),
                  [t('customer'), t('phone'), t('total_spend'), t('last_purchase')],
                )}>{t('export_csv')}</Button>
              </Stack>
              <TableContainer sx={{ maxHeight: 340 }}>
                <Table size="small" stickyHeader>
                  <TableHead>
                    <TableRow>
                      <TableCell>{t('customer')}</TableCell>
                      <TableCell>{t('phone')}</TableCell>
                      <TableCell align="right">{t('total_spend')}</TableCell>
                      <TableCell>{t('last_purchase')}</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {retention.lapsedCustomers.map((c) => (
                      <TableRow key={c.customerId} hover>
                        <TableCell>{c.customerName}</TableCell>
                        <TableCell>{c.phone || '—'}</TableCell>
                        <TableCell align="right">{formatCurrency(c.totalHistoricalSpend)}</TableCell>
                        <TableCell>{c.lastPurchaseDate ? formatDate(c.lastPurchaseDate) : '—'}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
            </Paper>
          )}
        </Box>
      )}
    </Box>
  );
};

export default Reports;