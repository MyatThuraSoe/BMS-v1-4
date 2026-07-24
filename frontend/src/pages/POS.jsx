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
  Popper,
  List,
  ListItem,
  ListItemButton,
  Switch,
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

import { useAuth } from '../context/AuthContext';
import { notifySuccess, notifyError, notifyWarning } from '../utils/notify';

import ProductImage from '../components/ProductImage';
import ShopLogo from '../components/ShopLogo';


import { productService, customerService, saleService, categoryService, receiptService, shopInfoService } from '../api/services';
import { printReceiptViaQZ, isQZSupported } from '../utils/bluetoothPrinter'; // Add QZ Tray import

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
  const [registeredMode, setRegisteredMode] = useState(false); // toggle: off = plain name, on = search registered
  const [customerNameInput, setCustomerNameInput] = useState(''); // used only when registeredMode is off
  const [customerInputText, setCustomerInputText] = useState('');
  const queryClient = useQueryClient();
  const { user } = useAuth();
  const [verifiedTotals, setVerifiedTotals] = useState(null); // { subtotal, taxAmount, totalAmount } from last successful verify

  // Shop info (for receipt branding)
  const { data: shopInfoData } = useQuery({
    queryKey: ['shopInfo'],
    queryFn: () => shopInfoService.get(),
    enabled: true,
  });

  const shopInfo = shopInfoData?.data;

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
  const [selectedCategory, setSelectedCategory] = useState('');

  const { data: categoriesData } = useQuery({
    queryKey: ['categories-pos'],
    queryFn: () => categoryService.getAll(0, 100),
    enabled: true,
  });

  const categories = categoriesData?.data?.content || [];

  const filteredProducts = products.filter(
    (p) => {
      const matchesSearch =
        p.name?.toLowerCase().includes(searchQuery.toLowerCase()) ||
        p.sku?.toLowerCase().includes(searchQuery.toLowerCase()) ||
        p.barcode?.toLowerCase().includes(searchQuery.toLowerCase());

      const matchesCategory = !selectedCategory || String(p.categoryId) === String(selectedCategory);
      return matchesSearch && matchesCategory;
    }
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
    setCustomerNameInput('');
    setRegisteredMode(false);
    setCashAmount('');
    setError('');
    setVerifiedTotals(0);
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
      customerId: registeredMode ? (selectedCustomer?.id ?? null) : null,
      customerName: registeredMode ? null : (customerNameInput.trim() || null),
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

  // 👇 FIXED: Fetches HTML with JWT token, then opens it in a new window
  const handlePrintReceipt = async () => {
    if (lastSale?.invoiceNumber) {
      try {
        // This uses apiClient, which sends the JWT token automatically
        const htmlContent = await receiptService.getPrintHtml(lastSale.invoiceNumber);
        const printWindow = window.open('', '_blank');
        if (printWindow) {
          printWindow.document.write(htmlContent);
          printWindow.document.close();
          // Wait for the HTML to render before triggering the browser print dialog
          printWindow.onload = () => {
            printWindow.print();
          };
        }
      } catch (err) {
        notifyError('Failed to load print view. Please try again.');
      }
    }
  };

  // 👇 FIXED: Fetches PDF as a blob with JWT token, then opens it
  const handleDownloadPdf = async () => {
    if (lastSale?.invoiceNumber) {
      try {
        const blob = await receiptService.downloadReceipt(lastSale.invoiceNumber, 'pdf');
        const url = window.URL.createObjectURL(new Blob([blob]));
        window.open(url, '_blank');
      } catch (err) {
        notifyError('Failed to download PDF.');
      }
    }
  };

  // 👇 NEW: Print directly to thermal printer via QZ Tray using the JSON data you already have!
  const handleQZPrint = async () => {
    if (lastSale) {
      try {
        // lastSale contains the exact same data structure as the receipt
        await printReceiptViaQZ(lastSale, shopInfo || {});
        notifySuccess('Receipt sent to printer!');
      } catch (err) {
        // Error is already notified inside the utility
      }
    }
  };

  return (
    <Box>
      <Typography variant="h4" gutterBottom>
        Available Products
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
            <Box sx={{ display: 'flex', gap: 1, mb: 2, flexWrap: 'wrap' }}>
              <Chip
                label="All"
                clickable
                color={!selectedCategory ? 'primary' : 'default'}
                variant={!selectedCategory ? 'filled' : 'outlined'}
                onClick={() => setSelectedCategory('')}
              />
              {categories.map((c) => (
                <Chip
                  key={c.id}
                  label={c.name}
                  clickable
                  color={selectedCategory && String(selectedCategory) === String(c.id) ? 'primary' : 'default'}
                  variant={selectedCategory && String(selectedCategory) === String(c.id) ? 'filled' : 'outlined'}
                  onClick={() => setSelectedCategory(String(c.id))}
                />
              ))}
            </Box>
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
                          <TableCell align="right">Price</TableCell>
                          <TableCell align="center">Qty</TableCell>
                          <TableCell align="right">Total</TableCell>
                          <TableCell align="center">Action</TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {cart.map((item) => (
                        <TableRow key={item.productId}>
                          <TableCell>{item.name}</TableCell>

                          <TableCell align="right">
                              {formatCurrency(item.price)}
                          </TableCell>

                          <TableCell align="center">
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
                              <strong>
                                  {formatCurrency(item.price * item.quantity)}
                              </strong>
                          </TableCell>

                          <TableCell align="center">
                              <IconButton
                                  color="error"
                                  size="small"
                                  onClick={() => removeFromCart(item.productId)}
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
              <Box sx={{ display: 'flex', gap: 1, mb: 2, alignItems: 'center' }}>
                {registeredMode ? (
                  <Autocomplete
                    fullWidth
                    options={customers}
                    getOptionLabel={(c) => `${c.firstName} ${c.lastName}${c.phone ? ' (' + c.phone + ')' : ''}`}
                    value={selectedCustomer}
                    onChange={(e, val) => setSelectedCustomer(val)}
                    inputValue={customerInputText}
                    onInputChange={(e, newValue) => setCustomerInputText(newValue)}
                    renderInput={(params) => (
                      <TextField
                        {...params}
                        label="Search registered customer"
                        size="small"
                        InputProps={{
                          ...params.InputProps,
                          startAdornment: <CustomerIcon sx={{ mr: 1, color: 'text.secondary' }} />,
                        }}
                      />
                    )}
                    noOptionsText={
                      <Box sx={{ p: 1 }}>
                        <Typography variant="body2" color="text.secondary">No match found.</Typography>
                        <Button size="small" onClick={() => setRegisteredMode(false)}>
                          Add as unregistered instead
                        </Button>
                      </Box>
                    }
                  />
                ) : (
                  <TextField
                    fullWidth
                    size="small"
                    label="Customer name (optional)"
                    placeholder="Leave blank for Walk-in"
                    value={customerNameInput}
                    onChange={(e) => setCustomerNameInput(e.target.value)}
                    InputProps={{
                      startAdornment: <CustomerIcon sx={{ mr: 1, color: 'text.secondary' }} />,
                    }}
                  />
                )}
                <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
                  <Switch
                    checked={registeredMode}
                    onChange={(e) => {
                      setRegisteredMode(e.target.checked);
                      // Clear whichever side isn't active, so stale state can't leak into the sale
                      if (e.target.checked) { setCustomerNameInput(''); }
                      else { setSelectedCustomer(null); }
                    }}
                    size="small"
                  />
                  <Typography variant="caption" color="text.secondary">
                    {registeredMode ? 'Registered' : 'Unregistered'}
                  </Typography>
                </Box>
              </Box>
              {selectedCustomer && registeredMode && (
                <Chip
                  label={`${selectedCustomer.firstName} ${selectedCustomer.lastName}${selectedCustomer.phone ? ' (' + selectedCustomer.phone + ')' : ''}`}
                  onDelete={() => setSelectedCustomer(null)}
                  color="primary"
                  size="small"
                  sx={{ mb: 2 }}
                />
              )}

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
              <Box sx={{ display: 'flex', justifyContent: 'center', mb: 1 }}>
                <ShopLogo size={56} />
              </Box>
              <Typography variant="h6" align="center">{shopInfo?.shopName || 'Shop'}</Typography>

              <Typography variant="body2" align="center">Invoice: {lastSale.invoiceNumber}</Typography>

              <Typography variant="body2" align="center">
                {new Date(lastSale.saleDate).toLocaleString()}
              </Typography>
              {/* <Typography variant="body2" align="center">
                Cashier: {user?.username}
              </Typography> */}
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
                <DialogActions sx={{ p: 2, flexDirection: 'column', gap: 1 }}>
          <Box sx={{ display: 'flex', gap: 1, width: '100%' }}>
            <Button onClick={handleDownloadPdf} variant="outlined" fullWidth>
              Download PDF
            </Button>
            <Button onClick={handlePrintReceipt} variant="outlined" fullWidth>
              Print (Browser)
            </Button>
          </Box>
          
          {/* 👇 QZ Tray Thermal Printer Button */}
          {isQZSupported() && (
            <Button 
              onClick={handleQZPrint} 
              variant="contained" 
              color="primary" 
              fullWidth 
              startIcon={<CartIcon />} // Or import a PrinterIcon
            >
              Print to Thermal Printer (QZ)
            </Button>
          )}

          <Button onClick={() => setShowReceiptDialog(false)} variant="text" fullWidth>
            Close
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default POS;
