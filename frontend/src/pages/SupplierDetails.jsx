import { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Box, Typography, Grid, Card, CardContent, Button, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Paper, Chip, CircularProgress, Divider, TablePagination
} from '@mui/material';
import { ArrowBack as ArrowBackIcon, Edit as EditIcon, Receipt as ReceiptIcon, LocalShipping as LocalShippingIcon } from '@mui/icons-material';
import { useQuery } from '@tanstack/react-query';
import { supplierService, purchaseService } from '../api/services';
import { formatDateTime, formatCurrency } from '../utils/helpers';

const SupplierDetails = () => {
  const { id } = useParams();
  const navigate = useNavigate();

  const [purchasesPage, setPurchasesPage] = useState(0);
  const [purchasesRowsPerPage, setPurchasesRowsPerPage] = useState(10);

  const { data: supplierData, isLoading: supplierLoading } = useQuery({
    queryKey: ['supplier', id],
    queryFn: () => supplierService.getById(id),
  });

  const { data: statsData, isLoading: statsLoading } = useQuery({
    queryKey: ['supplierStats', id],
    queryFn: () => purchaseService.getSupplierStats(id),
  });

  const { data: topProductsData, isLoading: topProductsLoading } = useQuery({
    queryKey: ['supplierTopProducts', id],
    queryFn: () => purchaseService.getSupplierTopProducts(id),
  });

  const { data: purchasesData, isLoading: purchasesLoading } = useQuery({
    queryKey: ['supplierPurchases', id, purchasesPage, purchasesRowsPerPage],
    queryFn: () => purchaseService.getAll(purchasesPage, purchasesRowsPerPage, 'purchaseDate', null, null, null, null, id), // Adjust params based on your actual service signature
  });

  if (supplierLoading) return <Box sx={{ p: 3 }}><CircularProgress /></Box>;

  const supplier = supplierData?.data || {};
  const stats = statsData?.data || {};
  const topProducts = topProductsData?.data || [];
  const purchases = purchasesData?.data?.content || [];
  const totalPurchasesElements = purchasesData?.data?.totalElements || 0;

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
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 3 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 2 }}>
          <Button startIcon={<ArrowBackIcon />} onClick={() => navigate('/suppliers')}>
            Back
          </Button>
          <Typography variant="h4">{supplier.name}</Typography>
        </Box>
        <Button variant="contained" startIcon={<EditIcon />} onClick={() => navigate(`/suppliers/${id}/edit`)}>
          Edit Supplier
        </Button>
      </Box>

      {/* Supplier Info */}
      <Card sx={{ mb: 3 }}>
        <CardContent>
          <Typography variant="h6" gutterBottom>Contact & Business Information</Typography>
          <Divider sx={{ mb: 2 }} />
          <Grid container spacing={3}>
            <Grid item xs={12} md={6}>
              <Typography variant="body2" color="text.secondary">Contact Person</Typography>
              <Typography variant="body1">{supplier.contactPerson || 'N/A'}</Typography>
            </Grid>
            <Grid item xs={12} md={6}>
              <Typography variant="body2" color="text.secondary">Phone</Typography>
              <Typography variant="body1">{supplier.phone || 'N/A'}</Typography>
            </Grid>
            <Grid item xs={12} md={6}>
              <Typography variant="body2" color="text.secondary">Email</Typography>
              <Typography variant="body1">{supplier.email || 'N/A'}</Typography>
            </Grid>
            <Grid item xs={12} md={6}>
              <Typography variant="body2" color="text.secondary">Tax ID</Typography>
              <Typography variant="body1">{supplier.taxId || 'N/A'}</Typography>
            </Grid>
            <Grid item xs={12} md={12}>
              <Typography variant="body2" color="text.secondary">Address</Typography>
              <Typography variant="body1">{supplier.address || 'N/A'}</Typography>
            </Grid>
          </Grid>
        </CardContent>
      </Card>

      {/* Stats */}
      <Typography variant="h6" gutterBottom sx={{ mb: 2 }}>Spending Overview</Typography>
      <Grid container spacing={2} sx={{ mb: 4 }}>
        <Grid item xs={12} sm={6} md={3}>
          <StatCard title="All Time" value={stats.totalSpentAllTime} icon={<LocalShippingIcon color="primary" />} color="primary.main" />
        </Grid>
        <Grid item xs={12} sm={6} md={3}>
          <StatCard title="This Year" value={stats.totalSpentThisYear} icon={<LocalShippingIcon color="info" />} color="info.main" />
        </Grid>
        <Grid item xs={12} sm={6} md={3}>
          <StatCard title="This Month" value={stats.totalSpentThisMonth} icon={<LocalShippingIcon color="success" />} color="success.main" />
        </Grid>
        <Grid item xs={12} sm={6} md={3}>
          <Card sx={{ height: '100%' }}>
            <CardContent>
              <Box sx={{ display: 'flex', alignItems: 'center', mb: 1 }}>
                <ReceiptIcon color="secondary" />
                <Typography variant="body2" color="text.secondary" sx={{ ml: 1 }}>Total Purchase Invoices</Typography>
              </Box>
              <Typography variant="h5" fontWeight="bold" color="secondary.main">
                {stats.totalInvoices || 0}
              </Typography>
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      <Grid container spacing={3}>
        {/* Top Products */}
        <Grid item xs={12} md={6}>
          <Card sx={{ height: '100%' }}>
            <CardContent>
              <Typography variant="h6" gutterBottom>Top 10 Products Supplied</Typography>
              <Divider sx={{ mb: 2 }} />
              {topProductsLoading ? <CircularProgress size={24} /> : topProducts.length === 0 ? (
                <Typography color="text.secondary">No purchase history yet.</Typography>
              ) : (
                <TableContainer>
                  <Table size="small">
                    <TableHead>
                      <TableRow>
                        <TableCell>Product</TableCell>
                        <TableCell align="right">Qty</TableCell>
                        <TableCell align="right">Total Cost</TableCell>
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

        {/* Recent Purchases */}
        <Grid item xs={12} md={6}>
          <Card sx={{ height: '100%' }}>
            <CardContent>
              <Typography variant="h6" gutterBottom>Recent Purchase Invoices</Typography>
              <Divider sx={{ mb: 2 }} />
              {purchasesLoading ? <CircularProgress size={24} /> : purchases.length === 0 ? (
                <Typography color="text.secondary">No invoices found.</Typography>
              ) : (
                <>
                  <TableContainer>
                    <Table size="small">
                      <TableHead>
                        <TableRow>
                          <TableCell>Invoice</TableCell>
                          <TableCell>Date</TableCell>
                          <TableCell align="right">Amount</TableCell>
                          <TableCell align="center">Status</TableCell>
                        </TableRow>
                      </TableHead>
                      <TableBody>
                        {purchases.map((purchase) => (
                          <TableRow key={purchase.id} hover sx={{ cursor: 'pointer' }} onClick={() => navigate(`/purchases/${purchase.id}`)}>
                            <TableCell>{purchase.purchaseNumber}</TableCell>
                            <TableCell>{formatDateTime(purchase.purchaseDate)}</TableCell>
                            <TableCell align="right">{formatCurrency(purchase.totalAmount)}</TableCell>
                            <TableCell align="center">
                              <Chip 
                                label={purchase.paymentStatus || 'Completed'} 
                                size="small" 
                                color="success" 
                                variant="outlined" 
                              />
                            </TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </TableContainer>
                  <TablePagination
                    component="div"
                    count={totalPurchasesElements}
                    page={purchasesPage}
                    rowsPerPage={purchasesRowsPerPage}
                    onPageChange={(e, newPage) => setPurchasesPage(newPage)}
                    onRowsPerPageChange={(e) => { setPurchasesRowsPerPage(parseInt(e.target.value, 10)); setPurchasesPage(0); }}
                    rowsPerPageOptions={[5, 10, 25]}
                  />
                </>
              )}
            </CardContent>
          </Card>
        </Grid>
      </Grid>
    </Box>
  );
};

export default SupplierDetails;