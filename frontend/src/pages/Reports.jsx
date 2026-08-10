import { useState, useMemo } from 'react';
import {
  Box, Typography, Paper, Grid, TextField, Button, Table, TableBody, TableCell,
  TableHead, TableRow, TableContainer, MenuItem, ToggleButton, ToggleButtonGroup,
  Stack, CircularProgress, Chip, TableSortLabel,
} from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { reportService } from '../api/services';
import { formatCurrency, formatDate } from '../utils/helpers';

import { useNavigate } from 'react-router-dom';

const toDateStr = (date) => date.toISOString().split('T')[0];

const Reports = () => {
  const { t } = useTranslation('reports');
  const [dateRange, setDateRange] = useState({ start: new Date().toISOString().split('T')[0], end: new Date().toISOString().split('T')[0] });
  const [reportType, setReportType] = useState('daily');
  const navigate = useNavigate();

  // Cashier sort state
  const [cashierSort, setCashierSort] = useState('totalSales');
  const [cashierSortDir, setCashierSortDir] = useState('desc');

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

  const dailySales = dailyData?.data || {};
  const inventory = inventoryData?.data || {};
  const cashierRaw = cashierData?.data || [];

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

  return (
    <Box>
      <Typography variant="h4" gutterBottom>{t('reports_title')}</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>{t('reports_subtitle')}</Typography>

      <Paper sx={{ p: 2, mb: 3 }}>
        <Grid container spacing={2}>
          <Grid item xs={12} md={3}>
            <TextField fullWidth select label={t('report_type')} value={reportType} onChange={(e) => setReportType(e.target.value)} SelectProps={{ native: true }}>
              <option value="daily">{t('report_type_daily')}</option>
              <option value="inventory">{t('report_type_inventory')}</option>
              <option value="cashier">{t('report_type_cashier')}</option>
            </TextField>
          </Grid>
          <Grid item xs={12} md={3}>
            <TextField fullWidth label={t('start_date')} type="date" value={dateRange.start} onChange={(e) => setDateRange({ ...dateRange, start: e.target.value })} InputLabelProps={{ shrink: true }} />
          </Grid>
          <Grid item xs={12} md={3}>
            <TextField fullWidth label={t('end_date')} type="date" value={dateRange.end} onChange={(e) => setDateRange({ ...dateRange, end: e.target.value })} InputLabelProps={{ shrink: true }} />
          </Grid>
          <Grid item xs={12} md={3}>
            <Button fullWidth variant="contained" sx={{ height: '100%' }}>{t('generate')}</Button>
          </Grid>
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
          <Typography variant="h6" gutterBottom>{t('inventory_report_title')}</Typography>
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
          </Stack>

          {cashierLoading ? (
            <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}><CircularProgress /></Box>
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
    </Box>
  );
};

export default Reports;
