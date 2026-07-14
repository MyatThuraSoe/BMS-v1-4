import { useState } from 'react';
import { Box, Typography, Paper, Grid, TextField, Button, Table, TableBody, TableCell, TableHead, TableRow, TableContainer } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { reportService } from '../api/services';
import { formatCurrency, formatDate } from '../utils/helpers';

const Reports = () => {
  const [dateRange, setDateRange] = useState({ start: new Date().toISOString().split('T')[0], end: new Date().toISOString().split('T')[0] });
  const [reportType, setReportType] = useState('daily');

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

  const dailySales = dailyData?.data || {};
  const inventory = inventoryData?.data || {};

  return (
    <Box>
      <Typography variant="h4" gutterBottom>Reports</Typography>

      <Paper sx={{ p: 2, mb: 3 }}>
        <Grid container spacing={2}>
          <Grid item xs={12} md={3}>
            <TextField fullWidth select label="Report Type" value={reportType} onChange={(e) => setReportType(e.target.value)} SelectProps={{ native: true }}>
              <option value="daily">Daily Sales</option>
              <option value="inventory">Inventory</option>
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
            <Grid item xs={12} md={4}><Paper sx={{ p: 2, textAlign: 'center' }}><Typography variant="h4">{dailySales.totalSales || 0}</Typography><Typography color="text.secondary">Total Sales</Typography></Paper></Grid>
            <Grid item xs={12} md={4}><Paper sx={{ p: 2, textAlign: 'center' }}><Typography variant="h4">{formatCurrency(dailySales.totalRevenue)}</Typography><Typography color="text.secondary">Total Revenue</Typography></Paper></Grid>
            <Grid item xs={12} md={4}><Paper sx={{ p: 2, textAlign: 'center' }}><Typography variant="h4">{dailySales.itemCount || 0}</Typography><Typography color="text.secondary">Items Sold</Typography></Paper></Grid>
          </Grid>
        </Paper>
      )}

      {reportType === 'inventory' && (
        <Paper sx={{ p: 3 }}>
          <Typography variant="h6" gutterBottom>Inventory Report</Typography>
          <Grid container spacing={3} sx={{ mt: 1 }}>
            <Grid item xs={12} md={4}><Paper sx={{ p: 2, textAlign: 'center' }}><Typography variant="h4">{inventory.totalProducts || 0}</Typography><Typography color="text.secondary">Total Products</Typography></Paper></Grid>
            <Grid item xs={12} md={4}><Paper sx={{ p: 2, textAlign: 'center' }}><Typography variant="h4">{formatCurrency(inventory.totalValue)}</Typography><Typography color="text.secondary">Total Value</Typography></Paper></Grid>
            <Grid item xs={12} md={4}><Paper sx={{ p: 2, textAlign: 'center' }}><Typography variant="h4" color="error">{inventory.lowStockCount || 0}</Typography><Typography color="text.secondary">Low Stock Items</Typography></Paper></Grid>
          </Grid>
        </Paper>
      )}
    </Box>
  );
};

export default Reports;
