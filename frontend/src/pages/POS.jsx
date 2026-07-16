import { useState } from 'react';
import {
  Box,
  Typography,
  TextField,
  Button,
  Grid,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  IconButton,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Chip,
  Alert,
  Pagination,
  Autocomplete,
} from '@mui/material';
import {
  Add as AddIcon,
  Remove as RemoveIcon,
  Delete as DeleteIcon,
  Search as SearchIcon,
  ShoppingCart as CartIcon,
  PersonAdd as CustomerIcon,
} from '@mui/icons-material';

import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { productService, customerService, saleService } from '../api/services';
import { useAuth } from '../context/AuthContext';
import { notifySuccess, notifyError, notifyWarning } from '../utils/notify';

import ProductImage from '../components/ProductImage';

const POS = () => {

  const [page, setPage] = useState(0);
  const pageSize = 20;

  const [cart, setCart] = useState([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedCustomer, setSelectedCustomer] = useState(null);
  const [cashAmount, setCashAmount] = useState('');
  const [showCheckoutDialog, setShowCheckoutDialog] = useState(false);
  const [showReceiptDialog, setShowReceiptDialog] = useState(false);
  const [lastSale, setLastSale] = useState(null);
  const [error, setError] = useState('');
  const queryClient = useQueryClient();
  const { user } = useAuth();
  const [verifiedTotals, setVerifiedTotals] = useState(null); // { subtotal, taxAmount, totalAmount } from last successful verify

  // Fetch products
  const { data: productsData, isLoading } = useQuery({
    queryKey: ['products-pos', page],
    queryFn: () => productService.getAll(page, pageSize),
    keepPreviousData: true,
  });
  const products = productsData?.data?.content || [];
  const totalPages = productsData?.data?.totalPages || 0;

  // Fetch customers
  const { data: customersData } = useQuery({
    queryKey: ['customers-pos'],
    queryFn: () => customerService.getAll(0, 100),
  });
  const customers = customersData?.data?.content || [];

  // Filter products by search
  const filteredProducts = products.filter(
    (p) =>
      p.name?.toLowerCase().includes(searchQuery.toLowerCase()) ||
      p.sku?.toLowerCase().includes(searchQuery.toLowerCase()) ||
      p.barcode?.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const addToCart = (product) => {
    const existingItem = cart.find((item) => item.productId === product.id);
    if (existingItem) {
      if (existingItem.quantity >= product.stockQuantity) {
        setError(`Cannot add more. Only ${product.stockQuantity} available.`);
        return;
      }
      setCart(
        cart.map((item) =>
          item.productId === product.id
            ? { ...item, quantity: item.quantity + 1 }
            : item
        )
      );
    } else {
      setCart([...cart, {
        productId: product.id,
        name: product.name,
        price: product.unitPrice,
        taxRate: product.taxRate,
        quantity: 1,
        stockQuantity: product.stockQuantity
    }]);
    }
    setError('');
  };

  const updateQuantity = (productId, delta) => {
    setCart(
      cart
        .map((item) => {
          if (item.productId === productId) {
            const newQty = item.quantity + delta;
            if (newQty <= 0) return null;
            if (newQty > item.stockQuantity) {
              setError(`Cannot exceed available stock: ${item.stockQuantity}`);
              return item;
            }
            return { ...item, quantity: newQty };
          }
          return item;
        })
        .filter(Boolean)
    );
    setError('');
  };

  const removeFromCart = (productId) => {
    setCart(cart.filter((item) => item.productId !== productId));
  };

  const clearCart = () => {
    setCart([]);
    setSelectedCustomer(null);
    setCashAmount('');
    setError('');
  };

  const subtotal = cart.reduce(
      (sum, item) => sum + item.price * item.quantity,
      0
  );

  const tax = cart.reduce(
      (sum, item) =>
          sum + (item.price * item.quantity * item.taxRate) / 100,
      0
  );

  const total = subtotal + tax;
  const change = cashAmount ? parseFloat(cashAmount) - total : 0;

  const displaySubtotal = verifiedTotals?.subtotal ?? subtotal;

  const displayTax = verifiedTotals?.taxAmount ?? tax;

  const displayTotal = verifiedTotals?.totalAmount ?? total;// fallback only before first verify
  const displayChange = cashAmount ? parseFloat(cashAmount) - displayTotal : 0;

  const createSaleMutation = useMutation({
    mutationFn: async (saleData) => {
      const response = await saleService.create(saleData);
      return response;
    },
    onSuccess: (response) => {
      setLastSale(response.data);
      setShowCheckoutDialog(false);
      setShowReceiptDialog(true);
      clearCart();
      queryClient.invalidateQueries(['products-pos']);
    },
    onError: (err) => {
      const message = err.response?.data?.message || '';
      if (message.includes('Insufficient stock') || message.includes('less than total amount')) {
        notifyWarning('Prices or stock changed while you were checking out. Re-checking your cart...');
        setShowCheckoutDialog(false);
        verifyCartMutation.mutate(cart); // re-verify and refresh in place, same pattern as above
      } else {
        notifyError(err.friendlyMessage || message || 'Failed to create sale');
      }
    },
  });

  const verifyCartMutation = useMutation({
    mutationFn: (cartItems) => saleService.verifyCart(cartItems),
    onSuccess: (response) => {
      const result = response.data;

      if (result.valid) {
        // Nothing changed — proceed straight to the confirm dialog with authoritative totals
        setVerifiedTotals({
          subtotal: result.subtotal,
          taxAmount: result.taxAmount,
          totalAmount: result.totalAmount,
        });
        setShowCheckoutDialog(true);
        return;
      }

      // Something changed — update the SAME cart array in place, don't touch item selection
      setCart((prevCart) =>
        prevCart.map((cartItem) => {
          const fresh = result.items.find((i) => i.productId === cartItem.productId);
          if (!fresh) return cartItem;
          return {
            ...cartItem,
            price: fresh.unitPrice,
            taxRate: fresh.taxRate,
            stockQuantity: fresh.availableStock,
            // clamp quantity down if stock dropped below what's in the cart
            quantity: fresh.insufficientStock
              ? Math.min(cartItem.quantity, fresh.availableStock)
              : cartItem.quantity,
          };
        })
      );

      notifyWarning(`Some items changed: ${result.messages.join(' | ')}. Please review and checkout again.`);
      // Do NOT open the confirm dialog yet — let them see the corrected cart first.
    },
    onError: (err) => {
      notifyError(err.friendlyMessage || 'Could not verify cart. Please try again.');
    },
  });

  const handleCheckout = () => {
    if (cart.length === 0) {
      setError('Cart is empty');
      return;
    }
    if (!cashAmount || parseFloat(cashAmount) <= 0) {
      setError('Enter a cash amount');
      return;
    }
    verifyCartMutation.mutate(cart); // opens the dialog itself on success, via onSuccess above
  };

  const confirmCheckout = () => {
    const saleData = {
      items: cart.map((item) => ({
        productId: item.productId,
        quantity: item.quantity,
        price: item.price,
      })),
      customerId: selectedCustomer?.id,
      paymentMethod: 'CASH',
      amountPaid: parseFloat(cashAmount),
    };
    createSaleMutation.mutate(saleData);
  };

  const formatCurrency = (amount) => {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
    }).format(amount || 0);
  };

  const handlePrintReceipt = () => {
    if (lastSale?.invoiceNumber) {
      window.open(`/api/receipts/invoice/${lastSale.invoiceNumber}/print`, '_blank');
    }
  };

  const handleDownloadPdf = () => {
    if (lastSale?.invoiceNumber) {
      window.open(`/api/receipts/invoice/${lastSale.invoiceNumber}/pdf`, '_blank');
    }
  };

  return (
    <Box>
      <Typography variant="h4" gutterBottom>
        Point of Sale
      </Typography>

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError('')}>
          {error}
        </Alert>
      )}

      <Grid container spacing={2}>
        {/* Products Section */}
        <Grid item xs={12} md={8}>
          <Paper sx={{ p: 2, mb: 2 }}>
            <TextField
              fullWidth
              placeholder="Search by name, SKU, or barcode..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              InputProps={{
                startAdornment: <SearchIcon sx={{ mr: 1, color: 'text.secondary' }} />,
              }}
              sx={{ mb: 2 }}
            />
            <Grid container spacing={1}>
              {filteredProducts.map((product) => (
                <Grid item xs={6} sm={4} md={3} key={product.id}>
                  
                  
                  <Paper
                    onClick={() => addToCart(product)}
                    sx={{
                      p: 2,
                      cursor: 'pointer',
                      '&:hover': { bgcolor: 'action.hover' },
                      opacity: product.stockQuantity <= 0 ? 0.5 : 1,
                    }}
                  >
                    <ProductImage productId={product.id} hasImage={product.hasImage} size={50} />
                    <Typography variant="body2" noWrap>
                      {product.name}
                    </Typography>
                    <Typography variant="body2" color="primary" sx={{ fontWeight: 'bold' }}>
                      {formatCurrency(product.unitPrice)}
                    </Typography>
                    <Typography variant="caption" color={product.stockQuantity <= 10 ? 'error' : 'text.secondary'}>
                      Stock: {product.stockQuantity}
                    </Typography>
                  </Paper>
                </Grid>
              ))}
            </Grid>
          </Paper>
          <Box
            sx={{
              display: 'flex',
              justifyContent: 'center',
              mt: 2,
            }}
          >
            <Pagination
                page={page + 1}
                count={totalPages}
                color="primary"
                onChange={(e, value) => setPage(value - 1)}
            />
          </Box>
        </Grid>

        {/* Cart Section */}
        <Grid item xs={12} md={4}>
          <Paper sx={{ p: 2, height: '100%', display: 'flex', flexDirection: 'column' }}>
            <Typography variant="h6" gutterBottom>
              <CartIcon sx={{ verticalAlign: 'middle', mr: 1 }} />
              Cart
            </Typography>

            <Box sx={{ flexGrow: 1, overflow: 'auto' }}>
              {cart.length === 0 ? (
                <Typography color="text.secondary" align="center" sx={{ py: 4 }}>
                  Cart is empty
                </Typography>
              ) : (
                <TableContainer>
                  <Table size="small">
                    <TableHead>
                      <TableRow>
                        <TableCell>Item</TableCell>
                        <TableCell align="right">Qty</TableCell>
                        <TableCell align="right">Action</TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {cart.map((item) => (
                        <TableRow key={item.productId}>
                          <TableCell>
                            <Typography variant="body2" noWrap>
                              {item.name}
                            </Typography>
                            <Typography variant="caption" color="text.secondary">
                              {formatCurrency(item.price)}
                            </Typography>
                          </TableCell>
                          <TableCell align="right">
                            <IconButton
                              size="small"
                              onClick={() => updateQuantity(item.productId, -1)}
                            >
                              <RemoveIcon fontSize="small" />
                            </IconButton>
                            {item.quantity}
                            <IconButton
                              size="small"
                              onClick={() => updateQuantity(item.productId, 1)}
                            >
                              <AddIcon fontSize="small" />
                            </IconButton>
                          </TableCell>
                          <TableCell align="right">
                            <IconButton
                              size="small"
                              onClick={() => removeFromCart(item.productId)}
                              color="error"
                            >
                              <DeleteIcon fontSize="small" />
                            </IconButton>
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </TableContainer>
              )}
            </Box>

            <Box sx={{ borderTop: 1, borderColor: 'divider', pt: 2, mt: 2 }}>
              <Autocomplete
                options={customers}
                getOptionLabel={(c) => `${c.firstName} ${c.lastName}`}
                value={selectedCustomer}
                onChange={(e, val) => setSelectedCustomer(val)}
                renderInput={(params) => (
                  <TextField
                    {...params}
                    label="Customer (Optional)"
                    size="small"
                    InputProps={{
                      ...params.InputProps,
                      startAdornment: <CustomerIcon sx={{ mr: 1, color: 'text.secondary' }} />,
                    }}
                  />
                )}
                sx={{ mb: 2 }}
              />

              <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 1 }}>
                <Typography>Subtotal:</Typography>
                <Typography>{formatCurrency(subtotal)}</Typography>
              </Box>
              <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 1 }}>
                <Typography>Tax:</Typography>
                <Typography>{formatCurrency(displayTax)}</Typography>
            </Box>
              <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 2 }}>
                <Typography variant="h6">Total:</Typography>
                <Typography variant="h6" color="primary">
                  {formatCurrency(displayTotal)}
                </Typography>
              </Box>

              <TextField
                fullWidth
                label="Cash Amount"
                type="number"
                value={cashAmount}
                onChange={(e) => setCashAmount(e.target.value)}
                size="small"
                sx={{ mb: 1 }}
              />

              {cashAmount && (
                <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 2 }}>
                  <Typography>Change:</Typography>
                  <Typography color={change < 0 ? 'error' : 'success'}>
                    {formatCurrency(displayChange)}
                  </Typography>
                </Box>
              )}

              <Button
                fullWidth
                variant="contained"
                size="large"
                onClick={handleCheckout}
                disabled={cart.length === 0}
              >
                Checkout
              </Button>
              <Button
                fullWidth
                variant="outlined"
                onClick={clearCart}
                sx={{ mt: 1 }}
              >
                Clear Cart
              </Button>
            </Box>
          </Paper>
        </Grid>
      </Grid>

      {/* Checkout Confirmation Dialog */}
      <Dialog open={showCheckoutDialog} onClose={() => setShowCheckoutDialog(false)}>
        <DialogTitle>Confirm Checkout</DialogTitle>
        <DialogContent>
          <Typography>Items: {cart.length}</Typography>
          <Typography>Total: {formatCurrency(displayTotal)}</Typography>
          <Typography>Cash: {formatCurrency(parseFloat(cashAmount) || 0)}</Typography>
          <Typography>Change: {formatCurrency(displayChange)}</Typography>
          {selectedCustomer && (
            <Typography>Customer: {selectedCustomer.firstName} {selectedCustomer.lastName}</Typography>
          )}
          {parseFloat(cashAmount) < displayTotal && (
            <Alert severity="error" sx={{ mt: 2 }}>
              Cash amount is less than the total. Please collect {formatCurrency(displayTotal - (parseFloat(cashAmount) || 0))} more.
            </Alert>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setShowCheckoutDialog(false)}>Cancel</Button>
          <Button
            onClick={confirmCheckout}
            variant="contained"
            color="primary"
            disabled={parseFloat(cashAmount) < displayTotal}
          >
            Confirm
          </Button>
        </DialogActions>
      </Dialog>

      {/* Receipt Dialog */}
      <Dialog open={showReceiptDialog} onClose={() => setShowReceiptDialog(false)} maxWidth="sm" fullWidth>
        <DialogTitle>Receipt</DialogTitle>
        <DialogContent>
          {lastSale && (
            <Box sx={{ p: 2, fontFamily: 'monospace', bgcolor: '#f5f5f5' }}>
              <Typography variant="h6" align="center">BMS v1</Typography>
              <Typography variant="body2" align="center">Invoice: {lastSale.invoiceNumber}</Typography>
              <Typography variant="body2" align="center">
                {new Date(lastSale.saleDate).toLocaleString()}
              </Typography>
              <Typography variant="body2" align="center">
                Cashier: {user?.username}
              </Typography>
              <Typography variant="body2" align="center">
                Customer: {lastSale.customerName || 'Walk-in'}
              </Typography>
              <br />
              {lastSale.items?.map((item, idx) => (
                <Box key={idx} sx={{ display: 'flex', justifyContent: 'space-between' }}>
                  <Typography variant="body2">
                    {item.productName} x{item.quantity}
                  </Typography>
                  <Typography variant="body2">
                    {formatCurrency(item.totalPrice)}
                  </Typography>
                </Box>
              ))}
              <br />
              <Box sx={{ display: 'flex', justifyContent: 'space-between' }}>
                <Typography variant="body2">Total:</Typography>
                <Typography variant="body2">{formatCurrency(lastSale.totalAmount)}</Typography>
              </Box>
              <Box sx={{ display: 'flex', justifyContent: 'space-between' }}>
                <Typography variant="body2">Paid:</Typography>
                <Typography variant="body2">{formatCurrency(lastSale.amountPaid)}</Typography>
              </Box>
              <Box sx={{ display: 'flex', justifyContent: 'space-between' }}>
                <Typography variant="body2">Change:</Typography>
                <Typography variant="body2">
                  {formatCurrency(lastSale.amountPaid - lastSale.totalAmount)}
                </Typography>
              </Box>
            </Box>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setShowReceiptDialog(false)}>Close</Button>
          <Button onClick={handleDownloadPdf} variant="outlined">
            Download PDF
          </Button>
          <Button onClick={handlePrintReceipt} variant="contained">
            Print
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default POS;
