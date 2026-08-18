import { useState,useEffect } from 'react';
import { useTranslation } from 'react-i18next';
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
  CircularProgress,
} from '@mui/material';
import {
  Add as AddIcon,
  Remove as RemoveIcon,
  Delete as DeleteIcon,
  Search as SearchIcon,
  ShoppingCart as CartIcon,
  PersonAdd as CustomerIcon,
  FlashOn as DirectPrintIcon,
  FlashOn,
} from '@mui/icons-material';

import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';

import { useAuth } from '../context/AuthContext';
import { notifySuccess, notifyError, notifyWarning } from '../utils/notify';
import { formatCurrency, formatReceiptDateTime } from '../utils/helpers';

import ProductImage from '../components/ProductImage';
import ShopLogo from '../components/ShopLogo';
import ReceiptDocument, { generatePrintHtml, generateQRDataUrl } from '../components/ReceiptDocument';

import { productService, customerService, saleService, categoryService, receiptService, shopInfoService, receiptCustomizationService } from '../api/services';
import { printReceiptViaQZ, isQZSupported } from '../utils/bluetoothPrinter';
import directPrint from '../services/directPrintService';

const POS = () => {

  const { t } = useTranslation('pos');

  const [customerSearch, setCustomerSearch] = useState('');
  const [debouncedCustomerSearch, setDebouncedCustomerSearch] = useState('');


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

    const [isDirectPrinting, setIsDirectPrinting] = useState(false); // ✅ NEW

  // Shop info (for receipt branding)
  const { data: shopInfoData } = useQuery({
    queryKey: ['shopInfo'],
    queryFn: () => shopInfoService.get(),
    enabled: true,
  });

  const shopInfo = shopInfoData?.data;

  const { data: customizationData } = useQuery({
    queryKey: ['receipt-customization-pos'],
    queryFn: () => receiptCustomizationService.get(),
    enabled: true,
  });

  const customization     = customizationData?.data || {};
  const receiptTimeFormat = customization.timeFormat || '12';

  // Fetch products

  const { data: productsData, isLoading } = useQuery({
    queryKey: ['products-pos', page],
    queryFn: () => productService.getAll(page, pageSize),
    keepPreviousData: true,
  });
  const products = productsData?.data?.content || [];
  const totalPages = productsData?.data?.page?.totalPages || 0;

  // Fetch customers
  const { data: customersData } = useQuery({
    queryKey: ['customers-pos', debouncedCustomerSearch],
    queryFn: () => customerService.search(debouncedCustomerSearch),
    enabled: debouncedCustomerSearch.length >= 2, // Only fetch after 2 characters
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
        p.sku?.toLowerCase().includes(searchQuery.toLowerCase());

      const matchesCategory = !selectedCategory || String(p.categoryId) === String(selectedCategory);
      return matchesSearch && matchesCategory;
    }
  );


  const addToCart = (product) => {
    const existingItem = cart.find((item) => item.productId === product.id);
    if (existingItem) {
      const currentQty = parseInt(existingItem.quantity, 10) || 0;
      if (currentQty >= product.stockQuantity) {
        setError(t('cannot_add_more', { count: product.stockQuantity }));
        return;
      }
      setCart(
        cart.map((item) =>
          item.productId === product.id
            ? { ...item, quantity: currentQty + 1 }
            : item
        )
      );
    } else {
      setCart([...cart, {
        productId: product.id,
        name: product.name,
        price: product.unitPrice,
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
            const currentQty = parseInt(item.quantity, 10) || 0;
            const newQty = currentQty + delta;
            if (newQty <= 0) return null;
            if (newQty > item.stockQuantity) {
              setError(t('exceed_stock', { count: item.stockQuantity }));
              return { ...item, quantity: item.stockQuantity };
            }
            setError('');
            return { ...item, quantity: newQty };
          }
          return item;
        })
        .filter(Boolean)
    );
    setError('');
  };

  const handleQuantityInputChange = (productId, value) => {
    setCart((prevCart) =>
      prevCart.map((item) => {
        if (item.productId === productId) {
          if (value === '') {
            return { ...item, quantity: '' }; // Allow clearing input temporarily
          }
          const newQty = parseInt(value, 10);
          if (isNaN(newQty)) return item;
          
          if (newQty > item.stockQuantity) {
            setError(`Cannot exceed available stock: ${item.stockQuantity}`);
            return { ...item, quantity: item.stockQuantity };
          }
          setError('');
          return { ...item, quantity: newQty };
        }
        return item;
      })
    );
  };

  const handleQuantityInputBlur = (productId) => {
    setCart((prevCart) =>
      prevCart.map((item) => {
        if (item.productId === productId) {
          const qty = parseInt(item.quantity, 10);
          if (isNaN(qty) || qty <= 0) {
            return { ...item, quantity: 1 }; // Default to 1 if left empty or invalid
          }
          return item;
        }
        return item;
      })
    );
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
      (sum, item) => sum + item.price * (parseInt(item.quantity) || 0),
      0
  );

  const shopTaxPercentage = Number(shopInfo?.taxPercentage) || 0;

  const tax = subtotal * (shopTaxPercentage / 100);

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
      queryClient.invalidateQueries({ queryKey: ['products-pos'] });
      queryClient.invalidateQueries({ queryKey: ['products'] });
      queryClient.invalidateQueries({ queryKey: ['low-stock'] });
      queryClient.invalidateQueries({ queryKey: ['inventoryReport'] });
      queryClient.invalidateQueries({ queryKey: ['sales'] });
      queryClient.invalidateQueries({ queryKey: ['financialSummary'] });
      queryClient.invalidateQueries({ queryKey: ['dailySales'] });
      queryClient.invalidateQueries({ queryKey: ['recentSales'] });
      queryClient.invalidateQueries({ queryKey: ['salesTrend'] });
    },
    onError: (err) => {
      const message = err.response?.data?.message || '';
      if (message.includes('Insufficient stock') || message.includes('less than total amount')) {
        notifyWarning(t('prices_changed_warning'));
        setShowCheckoutDialog(false);
        verifyCartMutation.mutate(cart); // re-verify and refresh in place, same pattern as above
      } else {
        notifyError(err.friendlyMessage || message || t('failed_create_sale'));
      }
    },
  });

    useEffect(() => {
    const timer = setTimeout(() => setDebouncedCustomerSearch(customerSearch), 300);
    return () => clearTimeout(timer);
  }, [customerSearch]);


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
          const currentQty = parseInt(cartItem.quantity, 10) || 0;
          return {
            ...cartItem,
            price: fresh.unitPrice,
            stockQuantity: fresh.availableStock,
            // clamp quantity down if stock dropped below what's in the cart
            quantity: fresh.insufficientStock
              ? Math.min(currentQty, fresh.availableStock)
              : currentQty || 1,
          };
        })
      );

      notifyWarning(t('items_changed_warning', { messages: result.messages.join(' | ') }));
      // Do NOT open the confirm dialog yet — let them see the corrected cart first.
    },
    onError: (err) => {
      notifyError(err.friendlyMessage || t('verify_cart_failed'));
    },
  });

  const handleCheckout = () => {
    if (cart.length === 0) {
      setError(t('empty_cart'));
      return;
    }

    // Sanitize quantities before proceeding to ensure no empty quantities are sent
    const sanitizedCart = cart.map(item => {
      const qty = parseInt(item.quantity, 10);
      if (isNaN(qty) || qty <= 0) {
        return { ...item, quantity: 1 };
      }
      return item;
    });
    setCart(sanitizedCart);

    if (!cashAmount || parseFloat(cashAmount) <= 0) {
      setError(t('enter_cash_amount'));
      return;
    }
    verifyCartMutation.mutate(sanitizedCart); // opens the dialog itself on success, via onSuccess above
  };

  const confirmCheckout = () => {
    const saleData = {
      items: cart.map((item) => ({
        productId: item.productId,
        quantity: parseInt(item.quantity, 10) || 1, // Fallback to 1 just in case
        price: item.price,
      })),
      customerId: registeredMode ? (selectedCustomer?.id ?? null) : null,
      customerName: registeredMode ? null : (customerNameInput.trim() || null),
      paymentMethod: 'CASH',
      amountPaid: parseFloat(cashAmount),
    };
    createSaleMutation.mutate(saleData);
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
        notifyError(t('load_print_failed'));
      }
    }
  };

  // 👇 FIXED: Uses anchor tag trick to bypass popup blockers
  const handleDownloadPdf = async () => {
    if (!lastSale?.invoiceNumber) return;
    
    try {
      const blob = await receiptService.downloadReceipt(lastSale.invoiceNumber, 'pdf');
      
      // Create a temporary URL for the blob
      const url = window.URL.createObjectURL(new Blob([blob], { type: 'application/pdf' }));
      
      // Create a temporary anchor element
      const link = document.createElement('a');
      link.href = url;
      link.setAttribute('download', `receipt-${lastSale.invoiceNumber}.pdf`);
      
      // Append to body, click it, and clean up
      document.body.appendChild(link);
      link.click();
      
      // Cleanup
      document.body.removeChild(link);
      window.URL.revokeObjectURL(url);
      
    } catch (err) {
      console.error("PDF Download Error:", err);
      notifyError(t('download_pdf_failed'));
    }
  };

  // 👇 NEW: Print directly to thermal printer via QZ Tray using the JSON data you already have!
  const handleQZPrint = async () => {
    if (lastSale) {
      try {
        // lastSale contains the exact same data structure as the receipt
        await printReceiptViaQZ(lastSale, shopInfo || {}, null, receiptTimeFormat, customization.paperSize);
        notifySuccess(t('receipt_sent_printer'));
      } catch (err) {
        // Error is already handled inside the utility
      }
    }
  };

  // Smart Direct Print: uses generatePrintHtml so the printed paper matches the on-screen receipt
  const handleDirectPrint = async () => {
    if (!lastSale?.invoiceNumber) return;
    setIsDirectPrinting(true);
    try {
      if (directPrint.isAvailable()) {
        // Fetch logo as base64 so it embeds in the offline HTML document
        let logoDataUrl = null;
        if (shopInfo?.hasLogo) {
          try {
            const blob = await shopInfoService.getLogo();
            logoDataUrl = await new Promise((resolve) => {
              const reader = new FileReader();
              reader.onload  = () => resolve(reader.result);
              reader.onerror = () => resolve(null);
              reader.readAsDataURL(blob);
            });
          } catch { /* no logo */ }
        }
        const paperWidthMm = Math.max(40, parseInt(String(customization.paperSize || '58').replace(/\D/g, ''), 10) || 58);
        let qrDataUrl = null;
        if (customization?.showQRCode) {
          qrDataUrl = await generateQRDataUrl(lastSale.invoiceNumber);
        }
        const html = generatePrintHtml(lastSale, shopInfo || {}, customization, logoDataUrl, qrDataUrl);
        const result = await directPrint.print(html, null, paperWidthMm);
        if (result.success) {
          notifySuccess(t('receipt_sent_printer'));
        } else {
          notifyError(result.error || t('print_failed'));
        }
      } else if (isQZSupported()) {
        await printReceiptViaQZ(lastSale, shopInfo || {}, null, receiptTimeFormat, customization.paperSize);
        notifySuccess(t('receipt_sent_printer'));
      } else {
        handlePrintReceipt();
      }
    } catch (err) {
      notifyError(err.message || t('print_failed'));
    } finally {
      setIsDirectPrinting(false);
    }
  };

  return (
    <Box>
      <Typography variant="h5"  sx={{ color:'primary.main'}} gutterBottom>
        {t('available_products')}
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
              placeholder={t('search_placeholder')}
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              InputProps={{
                startAdornment: <SearchIcon sx={{ mr: 1, color: 'text.secondary' }} />,
                sx: { borderRadius: 2, bgcolor: 'background.default' },
              }}
              sx={{ mb: 2, '& fieldset': { border: 'none' } }}
            />
            <Box sx={{ display: 'flex', gap: 1, mb: 2, flexWrap: 'wrap' }}>
              <Chip
                label={t('all_categories')}
                clickable
                sx={{ px: 0.5 }}
                color={!selectedCategory ? 'primary' : 'default'}
                variant={!selectedCategory ? 'filled' : 'outlined'}
                onClick={() => setSelectedCategory('')}
              />
              {categories.map((c) => (
                <Chip
                  key={c.id}
                  label={c.name}
                  sx={{ px: 0.5 }}
                  clickable
                  color={selectedCategory && String(selectedCategory) === String(c.id) ? 'primary' : 'default'}
                  variant={selectedCategory && String(selectedCategory) === String(c.id) ? 'filled' : 'outlined'}
                  onClick={() => setSelectedCategory(String(c.id))}
                />
              ))}
            </Box>
            <Grid container spacing={1.5}>
              {filteredProducts.map((product) => {
                const outOfStock = product.stockQuantity <= 0;
                const lowStock = !outOfStock && product.stockQuantity <= 10;
                return (
                  <Grid item xs={6} sm={4} md={3} key={product.id}>
                    <Paper
                      elevation={0}
                      onClick={() => !outOfStock && addToCart(product)}
                      sx={{
                        position: 'relative',
                        overflow: 'hidden',
                        cursor: outOfStock ? 'default' : 'pointer',
                        border: '1px solid',
                        borderColor: 'divider',
                        transition: 'transform 0.15s ease, box-shadow 0.15s ease, border-color 0.15s ease',
                        opacity: outOfStock ? 0.55 : 1,
                        filter: outOfStock ? 'grayscale(0.6)' : 'none',
                        '&:hover': outOfStock ? {} : {
                          transform: 'translateY(-3px)',
                          boxShadow: '0 8px 20px rgba(28, 38, 32, 0.12)',
                          borderColor: 'primary.main',
                        },
                      }}
                    >
                      {outOfStock && (
                        <Box sx={{
                          position: 'absolute', top: 10, right: -28, width: 110,
                          bgcolor: 'error.main', color: 'white', textAlign: 'center',
                          fontSize: '0.65rem', fontWeight: 700, letterSpacing: 0.5,
                          transform: 'rotate(35deg)', py: 0.3, zIndex: 1,
                        }}>
                          {t('out_of_stock')}
                        </Box>
                      )}
                      <Box sx={{ p: 1.5, pb: 1, display: 'flex', justifyContent: 'center', bgcolor: 'background.default' }}>
                        <ProductImage productId={product.id} hasImage={product.hasImage} size={72} />
                      </Box>
                      <Box sx={{ p: 1.5, pt: 1 }}>
                        <Typography variant="body2" fontWeight={500} noWrap title={product.name}>
                          {product.name}
                        </Typography>
                        <Box sx={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', mt: 0.5 }}>
                          <Typography
                            sx={{ fontFamily: '"IBM Plex Mono", monospace', fontWeight: 600, fontSize: '0.95rem' }}
                            color="secondary.dark"
                          >
                            {formatCurrency(product.unitPrice)}
                          </Typography>
                          {!outOfStock && (
                            <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                              <Box sx={{
                                width: 6, height: 6, borderRadius: '50%',
                                bgcolor: lowStock ? 'error.main' : 'primary.main',
                              }} />
                              <Typography variant="caption" color="text.secondary">
                                {product.stockQuantity}
                              </Typography>
                            </Box>
                          )}
                        </Box>
                      </Box>
                    </Paper>
                  </Grid>
                );
              })}
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
          <Paper
            elevation={0}
            sx={{
              height: '100%',
              display: 'flex',
              flexDirection: 'column',
              border: '1px solid',
              borderColor: 'divider',
              // Torn-receipt edge: a repeating radial scallop along the top
              backgroundImage:
                'radial-gradient(circle at 10px 0, transparent 9px, #FFFFFF 9.5px)',
              backgroundSize: '20px 20px',
              backgroundPosition: 'top left',
              backgroundRepeat: 'repeat-x',
              pt: '14px',
              pb: 2,
            }}
          >
            <Box sx={{ px: 2, pb: 1 }}>
              <Typography variant="h6" sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                <CartIcon fontSize="small" />
                {t('cart')}
              </Typography>
            </Box>
            <Box sx={{ px: 2, flexGrow: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
            <Box sx={{ flexGrow: 1, overflow: 'auto' }}>
              {cart.length === 0 ? (
                <Typography color="text.secondary" align="center" sx={{ py: 4 }}>
                  {t('empty_cart')}
                </Typography>
              ) : (
                <TableContainer sx={{ overflowX: 'auto' }}>
                  <Table size="small">
                    <TableHead>
                      <TableRow>
                          <TableCell>{t('item')}</TableCell>
                          <TableCell align="center">{t('quantity')}</TableCell>
                          <TableCell align="right">{t('price')}</TableCell>
                          <TableCell align="right">{t('total')}</TableCell>
                          <TableCell align="center">{t('action')}</TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {cart.map((item) => (
                        <TableRow key={item.productId}>
                          <TableCell>{item.name}</TableCell>

                          <TableCell align="center">
                              <IconButton
                                  size="small"
                                  onClick={() => updateQuantity(item.productId, -1)}
                              >
                                  <RemoveIcon fontSize="small" />
                              </IconButton>

                              <TextField
                                  type="number"
                                  size="small"
                                  value={item.quantity}
                                  onChange={(e) => handleQuantityInputChange(item.productId, e.target.value)}
                                  onBlur={() => handleQuantityInputBlur(item.productId)}
                                  inputProps={{ min: 1, max: item.stockQuantity }}
                                  sx={{ 
                                    mx: 0.5, 
                                    width: '70px',
                                    '& input::-webkit-outer-spin-button, & input::-webkit-inner-spin-button': { 
                                      WebkitAppearance: 'none', 
                                      margin: 0 
                                    },
                                    '& input[type=number]': { MozAppearance: 'textfield' },
                                    '& input': { textAlign: 'center', padding: '6px 4px' }
                                  }}
                              />

                              <IconButton
                                  size="small"
                                  onClick={() => updateQuantity(item.productId, 1)}
                              >
                                  <AddIcon fontSize="small" />
                              </IconButton>
                          </TableCell>

                          <TableCell align="right" sx={{ fontFamily: '"IBM Plex Mono", monospace', fontSize: '0.85rem' }}>
                              {formatCurrency(item.price)}
                          </TableCell>

                          <TableCell align="right" sx={{ fontFamily: '"IBM Plex Mono", monospace', fontSize: '0.85rem', fontWeight: 600 }}>
                              {formatCurrency(item.price * (parseInt(item.quantity) || 0))}
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
                    sx={{ flex: 1, minWidth: 0 }}
                    options={customers}
                    getOptionLabel={(c) => `${c.firstName} ${c.lastName}${c.phone ? ' (' + c.phone + ')' : ''}`}
                    value={selectedCustomer}
                    onChange={(e, val) => setSelectedCustomer(val)}
                    inputValue={customerSearch}
                    onInputChange={(e, newValue) => setCustomerSearch(newValue)}
                    renderInput={(params) => (
                      <TextField
                        {...params}
                        label={t('search_registered_customer')}
                        size="small"
                        InputProps={{
                          ...params.InputProps,
                          startAdornment: <CustomerIcon sx={{ mr: 1, color: 'text.secondary' }} />,
                        }}
                      />
                    )}
                    noOptionsText={
                      <Box sx={{ p: 1 }}>
                        <Typography variant="body2" color="text.secondary">{t('no_match_found')}</Typography>
                        <Button size="small" onClick={() => setRegisteredMode(false)}>
                          {t('add_as_unregistered')}
                        </Button>
                      </Box>
                    }
                  />
                ) : (
                  <TextField
                    sx={{ flex: 1, minWidth: 0 }}
                    size="small"
                    label={t('customer_name_optional')}
                    placeholder={t('walkin_hint')}
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
                    {registeredMode ? t('registered') : t('unregistered')}
                  </Typography>
                </Box>
              </Box>
              {selectedCustomer && registeredMode && (
                <Chip
                  label={`${selectedCustomer.firstName} ${selectedCustomer.lastName}${selectedCustomer.phone ? ' (' + selectedCustomer.phone + ')' : ''}`}
                  onDelete={() => setSelectedCustomer(null)}
                  color="primary"
                  size="small"
                  sx={{ px: 0.5 }}
                />
              )}

              <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 1 }}>
                <Typography>{t('subtotal')}</Typography>
                <Typography>{formatCurrency(subtotal)}</Typography>
              </Box>
              <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 1 }}>
                <Typography>{t('tax')}</Typography>
                <Typography>{formatCurrency(displayTax)}</Typography>
            </Box>
              <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 2 }}>
                <Typography variant="h6">{t('total')}</Typography>
                <Typography variant="h6" color="primary">
                  {formatCurrency(displayTotal)}
                </Typography>
              </Box>

              <TextField
                fullWidth
                label={t('cash_amount')}
                type="number"
                value={cashAmount}
                onChange={(e) => setCashAmount(e.target.value)}
                size="small"
                sx={{ mb: 1 }}
              />

              {cashAmount && (
                <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 2 }}>
                  <Typography>{t('change')}</Typography>
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
                sx={{ py: 1.75, fontSize: '1.05rem', mt: 1 }}
              >
                {t('checkout')}
              </Button>
              <Button
                fullWidth
                variant="outlined"
                onClick={clearCart}
                sx={{ mt: 1 }}
              >
                {t('clear_cart')}
              </Button>
            </Box>
            </Box>
          </Paper>
        </Grid>
      </Grid>

      {/* Checkout Confirmation Dialog */}
      <Dialog open={showCheckoutDialog} onClose={() => setShowCheckoutDialog(false)}>
        <DialogTitle>{t('confirm_checkout')}</DialogTitle>
        <DialogContent>
          <Typography>{t('items_count', { count: cart.length })}</Typography>
          <Typography>{t('total')}: {formatCurrency(displayTotal)}</Typography>
          <Typography>{t('cash_label')} {formatCurrency(parseFloat(cashAmount) || 0)}</Typography>
          <Typography>{t('change')} {formatCurrency(displayChange)}</Typography>
          {selectedCustomer && (
            <Typography>{t('customer_label', { name: `${selectedCustomer.firstName} ${selectedCustomer.lastName}` })}</Typography>
          )}
          {parseFloat(cashAmount) < displayTotal && (
            <Alert severity="error" sx={{ mt: 2 }}>
              {t('cash_less_than_total', { amount: formatCurrency(displayTotal - (parseFloat(cashAmount) || 0)) })}
            </Alert>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setShowCheckoutDialog(false)}>{t('cancel')}</Button>
          <Button
            onClick={confirmCheckout}
            variant="contained"
            color="primary"
            disabled={parseFloat(cashAmount) < displayTotal}
          >
            {t('confirm')}
          </Button>
        </DialogActions>
      </Dialog>



      {/* Receipt Dialog — uses the shared ReceiptDocument so it matches what prints */}
      <Dialog open={showReceiptDialog} onClose={() => setShowReceiptDialog(false)} maxWidth="sm" fullWidth>
        <DialogTitle>{t('receipt')}</DialogTitle>
        <DialogContent>
          {lastSale && (
            <Box
              sx={{
                background: '#fff',
                border: '1px solid #ddd',
                borderRadius: '2px',
                p: '12px',
                mx: 'auto',
                maxWidth: 340,
              }}
            >
              <ReceiptDocument
                receipt={lastSale}
                shopInfo={shopInfo || {}}
                customization={customization}
                isMockPreview={false}
              />
            </Box>
          )}
        </DialogContent>
                <DialogActions sx={{ p: 2, flexDirection: 'column', gap: 1 }}>
          
          {/* ✅ NEW: Smart Direct Print Button (Always visible!) */}
          <Button 
            onClick={handleDirectPrint} 
            variant="contained" 
            color="primary" 
            fullWidth 
            startIcon={isDirectPrinting ? <CircularProgress size={20} sx={{ color: 'white' }} /> : <DirectPrintIcon />}
            disabled={isDirectPrinting}
            sx={{ py: 1.2, fontSize: '1rem' }}
          >
            {isDirectPrinting 
              ? t('printing') 
              : directPrint.isAvailable() 
                ? '⚡ Direct Print (Silent)' 
                : '⚡ Direct Print'}
          </Button>

          <Box sx={{ display: 'flex', gap: 1, width: '100%' }}>
            <Button onClick={handleDownloadPdf} variant="outlined" fullWidth>
              {t('download_pdf')}
            </Button>
            <Button onClick={handlePrintReceipt} variant="outlined" fullWidth>
              {t('print_browser')}
            </Button>
          </Box>
          
          {/* QZ Tray Thermal Printer Button */}
          {isQZSupported() && (
            <Button 
              onClick={handleQZPrint} 
              variant="outlined" 
              color="primary" 
              fullWidth 
            
            >
              {t('print_thermal_qz')}
            </Button>
          )}

          <Button onClick={() => setShowReceiptDialog(false)} variant="text" fullWidth>
            {t('close')}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default POS;