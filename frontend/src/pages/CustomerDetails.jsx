import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Box, Typography, Grid, Card, CardContent, Button, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Paper, Chip, CircularProgress, Divider
} from '@mui/material';
import { ArrowBack as ArrowBackIcon, Edit as EditIcon, Receipt as ReceiptIcon, ShoppingCart as ShoppingCartIcon } from '@mui/icons-material';
import { useQuery } from '@tanstack/react-query';
import { customerService, saleService, arService } from '../api/services';
import { formatDate, formatDateTime, formatCurrency } from '../utils/helpers';
import CustomerSpendingHeatmap from '../components/CustomerSpendingHeatmap';


const CustomerDetails = () => {
  const { id } = useParams();
  const navigate = useNavigate();
  const { t } = useTranslation('customers');

  const { data: customerData, isLoading: customerLoading } = useQuery({
    queryKey: ['customer', id],
    queryFn: () => customerService.getById(id),
  });

  const { data: statsData, isLoading: statsLoading } = useQuery({
    queryKey: ['customerStats', id],
    queryFn: () => saleService.getCustomerStats(id),
  });

  const { data: topProductsData, isLoading: topProductsLoading } = useQuery({
    queryKey: ['customerTopProducts', id],
    queryFn: () => saleService.getCustomerTopProducts(id),
  });

  const { data: salesData, isLoading: salesLoading } = useQuery({
    queryKey: ['customerSales', id],
    queryFn: () => saleService.getAll(0, 10, 'saleDate', null, null, null, id, null),
  });

  const { data: arHistoryData, isLoading: arLoading } = useQuery({
    queryKey: ['customerArHistory', id],
    queryFn: () => arService.getCustomerHistory(id, 0, 50),
  });

  if (customerLoading) return <Box sx={{ p: 3 }}><CircularProgress /></Box>;

  const customer = customerData?.data || {};
  const stats = statsData?.data || {};
  const topProducts = topProductsData?.data || [];
  const sales = salesData?.data?.content || [];
  const arInvoices = arHistoryData?.data?.content || [];
  const today = new Date();

  const StatCard = ({ title, value, icon, color }) => (
    <Card sx={{ height: '100%' }}>
      <CardContent>
        <Box sx={{ display: 'flex', alignItems: 'center', mb: 1 }}>
          {icon}
          <Typography variant="body2" color="text.secondary" sx={{ ml: 1 }}>{title}</Typography>
        </Box>
        <Typography variant="h5" fontWeight="bold" color={color}>
          {value !== undefined ? formatCurrency(value) : '-'}
        </Typography>
      </CardContent>
    </Card>
  );

  return (
    <Box sx={{ p: 3 }}>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 3, flexWrap: 'wrap', gap: 2 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, flexWrap: 'wrap', minWidth: 0 }}>
          <Button startIcon={<ArrowBackIcon />} onClick={() => navigate('/customers')}>
            {t('back')}
          </Button>
          <Typography variant="h4" sx={{ minWidth: 0 }}>
            {customer.firstName} {customer.lastName}
            {customer.isQuickAdd && <Chip label={t('quick_add')} size="small" color="warning" sx={{ ml: 1 }} />}
          </Typography>
        </Box>
        <Button variant="contained" startIcon={<EditIcon />} onClick={() => navigate(`/customers/${id}/edit`)}>
          {t('edit_customer')}
        </Button>
      </Box>

      {/* Customer Info */}
      <Card sx={{ mb: 3 }}>
        <CardContent>
          <Typography variant="h6" gutterBottom>{t('contact_address_info')}</Typography>
          <Divider sx={{ mb: 2 }} />
          <Grid container spacing={3}>
            <Grid item xs={12} md={6}>
              <Typography variant="body2" color="text.secondary">{t('phone')}</Typography>
              <Typography variant="body1">{customer.phone || t('n_a')}</Typography>
            </Grid>
            <Grid item xs={12} md={6}>
              <Typography variant="body2" color="text.secondary">{t('email')}</Typography>
              <Typography variant="body1">{customer.email || t('n_a')}</Typography>
            </Grid>
            <Grid item xs={12} md={6}>
              <Typography variant="body2" color="text.secondary">{t('address')}</Typography>
              <Typography variant="body1">{customer.address || t('n_a')}</Typography>
            </Grid>
            <Grid item xs={12} md={6}>
              <Typography variant="body2" color="text.secondary">{t('city')}</Typography>
              <Typography variant="body1">{customer.city || t('n_a')}</Typography>
            </Grid>
            <Grid item xs={12} md={6}>
              <Typography variant="body2" color="text.secondary">{t('customer_code')}</Typography>
              <Typography variant="body1">{customer.customerCode || t('n_a')}</Typography>
            </Grid>
            <Grid item xs={12} md={6}>
              <Typography variant="body2" color="text.secondary">{t('credit_limit')}</Typography>
              <Typography variant="body1">{customer.creditLimit != null ? formatCurrency(customer.creditLimit) : t('n_a')}</Typography>
            </Grid>
            <Grid item xs={12} md={6}>
              <Typography variant="body2" color="text.secondary">{t('current_balance')}</Typography>
              <Typography variant="body1" color={(customer.currentBalance || 0) > 0 ? 'error.main' : 'text.primary'}>
                {formatCurrency(customer.currentBalance || 0)}
              </Typography>
            </Grid>
            <Grid item xs={12} md={6}>
              <Typography variant="body2" color="text.secondary">{t('available_credit')}</Typography>
              <Typography variant="body1" color="primary.main">
                {formatCurrency((customer.creditLimit || 0) - (customer.currentBalance || 0))}
              </Typography>
            </Grid>
          </Grid>
        </CardContent>
      </Card>

      {/* Stats */}
      <Typography variant="h6" gutterBottom sx={{ mb: 2 }}>{t('spending_overview')}</Typography>
      <Grid container spacing={2} sx={{ mb: 4 }}>
        <Grid item xs={12} sm={6} md={2.4}>
          <StatCard title={t('all_time')} value={stats.totalSpentAllTime} icon={<ShoppingCartIcon color="primary" />} color="primary.main" />
        </Grid>
        <Grid item xs={12} sm={6} md={2.4}>
          <StatCard title={t('this_year')} value={stats.totalSpentThisYear} icon={<ShoppingCartIcon color="info" />} color="info.main" />
        </Grid>
        <Grid item xs={12} sm={6} md={2.4}>
          <StatCard title={t('this_month')} value={stats.totalSpentThisMonth} icon={<ShoppingCartIcon color="success" />} color="success.main" />
        </Grid>
        <Grid item xs={12} sm={6} md={2.4}>
          <StatCard title={t('this_week')} value={stats.totalSpentThisWeek} icon={<ShoppingCartIcon color="warning" />} color="warning.main" />
        </Grid>
        <Grid item xs={12} sm={6} md={2.4}>
          <Card sx={{ height: '100%' }}>
            <CardContent>
              <Box sx={{ display: 'flex', alignItems: 'center', mb: 1 }}>
                <ReceiptIcon color="secondary" />
                <Typography variant="body2" color="text.secondary" sx={{ ml: 1 }}>{t('total_invoices')}</Typography>
              </Box>
              <Typography variant="h5" fontWeight="bold" color="secondary.main">
                {stats.totalInvoices || 0}
              </Typography>
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      {/* Accounts Receivable */}
      <Card sx={{ mb: 4 }}>
        <CardContent>
          <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 1, mb: 1 }}>
            <Typography variant="h6">{t('accounts_receivable')}</Typography>
            <Typography variant="h6" color={(customer.currentBalance || 0) > 0 ? 'error.main' : 'success.main'}>
              {t('total_debt')}: {formatCurrency(customer.currentBalance || 0)}
            </Typography>
          </Box>
          <Divider sx={{ mb: 2 }} />
          {arLoading ? <CircularProgress size={24} /> : arInvoices.length === 0 ? (
            <Typography color="text.secondary">{t('no_ar_history')}</Typography>
          ) : (
            <TableContainer>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>{t('invoice')}</TableCell>
                    <TableCell>{t('date')}</TableCell>
                    <TableCell>{t('due_date')}</TableCell>
                    <TableCell align="right">{t('amount')}</TableCell>
                    <TableCell align="right">{t('amount_paid')}</TableCell>
                    <TableCell align="right">{t('balance_due')}</TableCell>
                    <TableCell align="center">{t('status')}</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {arInvoices.map((row) => {
                    const overdue = row.paymentStatus !== 'PAID' && row.dueDate && new Date(row.dueDate) < today;
                    return (
                      <TableRow key={row.invoiceId} hover>
                        <TableCell>{row.invoiceNumber}</TableCell>
                        <TableCell>{formatDateTime(row.saleDate)}</TableCell>
                        <TableCell>
                          {row.dueDate ? formatDate(row.dueDate) : '-'}
                          {overdue && <Typography variant="caption" color="error.main" sx={{ display: 'block' }}>{t('overdue')}</Typography>}
                        </TableCell>
                        <TableCell align="right">{formatCurrency(row.totalAmount)}</TableCell>
                        <TableCell align="right">{formatCurrency(row.amountPaid)}</TableCell>
                        <TableCell align="right" sx={{ fontWeight: 'bold', color: row.balanceDue > 0 ? 'error.main' : 'text.primary' }}>
                          {formatCurrency(row.balanceDue)}
                        </TableCell>
                        <TableCell align="center">
                          <Chip size="small" color={row.paymentStatus === 'PAID' ? 'success' : 'warning'} label={t(`payment_status_${row.paymentStatus}`)} />
                        </TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </CardContent>
      </Card>

      {/* Spending Heatmap */}
    <Box sx={{ mb: 4 }}>
        <CustomerSpendingHeatmap customerId={id} />
    </Box>

      <Grid container spacing={3}>
        {/* Top Products */}
        <Grid item xs={12} md={6}>
          <Card sx={{ height: '100%' }}>
            <CardContent>
              <Typography variant="h6" gutterBottom>{t('top_products')}</Typography>
              <Divider sx={{ mb: 2 }} />
              {topProductsLoading ? <CircularProgress size={24} /> : topProducts.length === 0 ? (
                <Typography color="text.secondary">{t('no_purchase_history')}</Typography>
              ) : (
                <TableContainer>
                  <Table size="small">
                    <TableHead>
                      <TableRow>
                        <TableCell>{t('product')}</TableCell>
                        <TableCell align="right">{t('qty')}</TableCell>
                        <TableCell align="right">{t('total_spent')}</TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {topProducts.map((p, idx) => (
                        <TableRow key={p.productId}>
                          <TableCell>
                            <Typography variant="body2" fontWeight="medium">{idx + 1}. {p.productName}</Typography>
                          </TableCell>
                          <TableCell align="right">{p.totalQuantity}</TableCell>
                          <TableCell align="right">{formatCurrency(p.totalAmount)}</TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </TableContainer>
              )}
            </CardContent>
          </Card>
        </Grid>

        {/* Recent Invoices */}
        <Grid item xs={12} md={6}>
          <Card sx={{ height: '100%' }}>
            <CardContent>
              <Typography variant="h6" gutterBottom>{t('recent_invoices')}</Typography>
              <Divider sx={{ mb: 2 }} />
              {salesLoading ? <CircularProgress size={24} /> : sales.length === 0 ? (
                <Typography color="text.secondary">{t('no_invoices_found')}</Typography>
              ) : (
                <TableContainer>
                  <Table size="small">
                    <TableHead>
                      <TableRow>
                        <TableCell>{t('invoice')}</TableCell>
                        <TableCell>{t('date')}</TableCell>
                        <TableCell align="right">{t('amount')}</TableCell>
                        <TableCell align="center">{t('status')}</TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {sales.map((sale) => (
                        <TableRow key={sale.id} hover sx={{ cursor: 'pointer' }} onClick={() => navigate(`/sales/${sale.id}`)}>
                          <TableCell>{sale.invoiceNumber}</TableCell>
                          <TableCell>{formatDateTime(sale.saleDate)}</TableCell>
                          <TableCell align="right">{formatCurrency(sale.totalAmount)}</TableCell>
                          <TableCell align="center">
                            <Chip 
                              label={sale.isVoided ? t('status_voided') : t('status_completed')} 
                              size="small" 
                              color={sale.isVoided ? 'error' : 'success'} 
                              variant="outlined" 
                            />
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </TableContainer>
              )}
            </CardContent>
          </Card>
        </Grid>
      </Grid>
    </Box>
  );
};

export default CustomerDetails;