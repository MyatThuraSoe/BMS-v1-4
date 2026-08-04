import { useState, useMemo } from 'react';
import {
  Box, Typography, Paper, Grid, TextField, Button, Table, TableBody, TableCell,
  TableHead, TableRow, TableContainer, MenuItem, ToggleButton, ToggleButtonGroup,
  Stack, CircularProgress, Chip, TableSortLabel,
} from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { reportService } from '../api/services';
import { formatCurrency, formatDate } from '../utils/helpers';

import { useNavigate } from 'react-router-dom';

const toDateStr = (date) => date.toISOString().split('T')[0];

const Reports = () => {
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
      <Typography variant="h4" gutterBottom>Reports</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>Operational reports — daily sales, inventory, cashier performance</Typography>

      <Paper sx={{ p: 2, mb: 3 }}>
        <Grid container spacing={2}>
          <Grid item xs={12} md={3}>
            <TextField fullWidth select label="Report Type" value={reportType} onChange={(e) => setReportType(e.target.value)} SelectProps={{ native: true }}>
              <option value="daily">Daily Sales</option>
              <option value="inventory">Inventory</option>
              <option value="cashier">Cashier Performance</option>
            </TextField>
          </Grid>
          <Grid item xs={12} md={3}>
            <TextField fullWidth label="Start Date" type="date" value={dateRange.start} onChange={(e) => setDateRange({ ...dateRange, start: e.target.value })} InputLabelProps={{ shrink: true }} />
          </Grid>
          <Grid item xs={12} md={3}>
            <TextField fullWidth label="End Date" type="date" value={dateRange.end} onChange={(e) => setDateRange({ ...dateRange, end: e.target.value })} InputLabelProps={{ shrink: true }} />
          </Grid>
          <Grid item xs={12} md={3}>
            <Button fullWidth variant="contained" sx={{ height: '100%' }}>Generate</Button>
          </Grid>
        </Grid>
      </Paper>

      {reportType === 'daily' && (
        <Paper sx={{ p: 3 }}>
          <Typography variant="h6" gutterBottom>Daily Sales Report - {formatDate(dateRange.start)}</Typography>
          <Grid container spacing={3} sx={{ mt: 1 }}>
          <Grid item xs={12} md={4}>
            <Paper sx={{ p: 2, textAlign: 'center' }}>
              <Typography variant="h4">
                {dailySales.totalTransactions || 0}
              </Typography>
              <Typography color="text.secondary">
                Sales Today
              </Typography>
            </Paper>
          </Grid>

          <Grid item xs={12} md={4}>
            <Paper sx={{ p: 2, textAlign: 'center', cursor: 'pointer' }} onClick={() => navigate('/dashboard')}>
              <Typography variant="body2" color="text.secondary">
                For Revenue and Profit, see the Dashboard
              </Typography>
              <Typography variant="caption" color="primary">Go to Dashboard →</Typography>
            </Paper>
          </Grid>

          <Grid item xs={12} md={4}>
            <Paper sx={{ p: 2, textAlign: 'center' }}>
              <Typography variant="h4">
                {formatCurrency(dailySales.averageTransactionValue || 0)}
              </Typography>
              <Typography color="text.secondary">
                Average Sale
              </Typography>
            </Paper>
          </Grid>
        </Grid>
        </Paper>
      )}

      {reportType === 'inventory' && (
        <Paper sx={{ p: 3 }}>
          <Typography variant="h6" gutterBottom>Inventory Report</Typography>
          <Grid container spacing={3} sx={{ mt: 1 }}>
          <Grid item xs={12} md={4}>
            <Paper sx={{ p: 2, textAlign: 'center' }}>
              <Typography variant="h4">
                {inventory.totalProducts || 0}
              </Typography>
              <Typography color="text.secondary">
                Total Products
              </Typography>
            </Paper>
          </Grid>

          <Grid item xs={12} md={4}>
            <Paper sx={{ p: 2, textAlign: 'center' }}>
              <Typography variant="h4">
                {formatCurrency(inventory.totalInventoryValue || 0)}
              </Typography>
              <Typography color="text.secondary">
                Total Inventory Value
              </Typography>
            </Paper>
          </Grid>

          <Grid item xs={12} md={4}>
            <Paper sx={{ p: 2, textAlign: 'center' }}>
              <Typography variant="h4" color="error">
                {inventory.lowStockProductsCount || 0}
              </Typography>
              <Typography color="text.secondary">
                Low Stock Products
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
              <Typography variant="h6">Cashier Performance</Typography>
              <Typography variant="body2" color="text.secondary">
                Click column headers to re-sort. "Avg Transaction Value" is the fairness-adjusted view — it's not affected by hours worked.
              </Typography>
            </Box>
          </Stack>

          {cashierLoading ? (
            <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}><CircularProgress /></Box>
          ) : cashierRows.length === 0 ? (
            <Typography color="text.secondary">No sales data for this period.</Typography>
          ) : (
            <TableContainer>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>Cashier ID</TableCell>
                    <TableCell align="right">
                      <TableSortLabel
                        active={cashierSort === 'totalSales'}
                        direction={cashierSort === 'totalSales' ? cashierSortDir : 'desc'}
                        onClick={() => handleCashierSort('totalSales')}
                      >
                        Total Sales
                      </TableSortLabel>
                    </TableCell>
                    <TableCell align="right">
                      <TableSortLabel
                        active={cashierSort === 'transactionCount'}
                        direction={cashierSort === 'transactionCount' ? cashierSortDir : 'desc'}
                        onClick={() => handleCashierSort('transactionCount')}
                      >
                        Transactions
                      </TableSortLabel>
                    </TableCell>
                    <TableCell align="right">
                      <TableSortLabel
                        active={cashierSort === 'averageTransactionValue'}
                        direction={cashierSort === 'averageTransactionValue' ? cashierSortDir : 'desc'}
                        onClick={() => handleCashierSort('averageTransactionValue')}
                      >
                        Avg Transaction Value
                      </TableSortLabel>
                    </TableCell>
                    <TableCell align="right">
                      <TableSortLabel
                        active={cashierSort === 'averageItemsPerSale'}
                        direction={cashierSort === 'averageItemsPerSale' ? cashierSortDir : 'desc'}
                        onClick={() => handleCashierSort('averageItemsPerSale')}
                      >
                        Avg Items / Sale
                      </TableSortLabel>
                    </TableCell>
                    <TableCell align="right">Total Items</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {cashierRows.map((row, idx) => (
                    <TableRow key={row.cashierId ?? idx} hover>
                      <TableCell>
                        <Chip label={`Cashier #${row.cashierId}`} size="small" variant="outlined" />
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
