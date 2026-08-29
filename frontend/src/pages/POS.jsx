import { useState, useEffect, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
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
  ToggleButton,
  ToggleButtonGroup,
} from '@mui/material';
import {
  Add as AddIcon,
  Remove as RemoveIcon,
  Delete as DeleteIcon,
  Search as SearchIcon,
  ShoppingCart as CartIcon,
  PersonAdd as CustomerIcon,
  FlashOn as DirectPrintIcon,
  ListAlt as OrderIcon,
} from '@mui/icons-material';

import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';

import { useAuth } from '../context/AuthContext';
import { notifySuccess, notifyError, notifyWarning } from '../utils/notify';
import { formatCurrency, formatReceiptDateTime } from '../utils/helpers';

import ProductImage from '../components/ProductImage';
import ShopLogo from '../components/ShopLogo';
import ReceiptDocument, { generatePrintHtml, generateQRDataUrl } from '../components/ReceiptDocument';

import { productService, customerService, saleService, categoryService, receiptService, shopInfoService, receiptCustomizationService, orderService, counterPrintService } from '../api/services';
import directPrint from '../services/directPrintService';
import useShopConfig from '../hooks/useShopConfig';
import {
  readDrafts,
  writeDrafts,
  createDraftRecord,
  DRAFT_TTL_MS,
} from '../utils/draftStorage';

const POS = () => {

  const { t } = useTranslation('pos');
  const [searchParams, setSearchParams] = useSearchParams();
  const draftId = searchParams.get('draftId');

  // Focused number inputs change value when scrolling the page — blur them
  // so scrolling never mutates a typed amount (cash received / discount).
  const preventWheelChange = (e) => {
    if (e.target === document.activeElement) e.target.blur();
  };

  const [customerSearch, setCustomerSearch] = useState('');
  const [debouncedCustomerSearch, setDebouncedCustomerSearch] = useState('');


  const [page, setPage] = useState(0);
  const pageSize = 12;

  const [cart, setCart] = useState([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedCustomer, setSelectedCustomer] = useState(null);
  const [cashAmount, setCashAmount] = useState('');
  const [cashManuallyEdited, setCashManuallyEdited] = useState(false);
  const [cashierDiscount, setCashierDiscount] = useState(''); // AMOUNT-mode discount entered by cashier
  const [counterPrinting, setCounterPrinting] = useState(false);

  const [showCheckoutDialog, setShowCheckoutDialog] = useState(false);
  const [showReceiptDialog, setShowReceiptDialog] = useState(false);
  const [printAfterCheckout, setPrintAfterCheckout] = useState(false);
  const [lastSale, setLastSale] = useState(null);
  const [error, setError] = useState('');
  const [registeredMode, setRegisteredMode] = useState(false); // toggle: off = plain name, on = search registered
  const [customerNameInput, setCustomerNameInput] = useState(''); // used only when registeredMode is off
  const [customerInputText, setCustomerInputText] = useState('');
  const queryClient = useQueryClient();
  const { user, isManager } = useAuth();
  const [verifiedTotals, setVerifiedTotals] = useState(null); // { subtotal, taxAmount, totalAmount } from last successful verify

    const [isDirectPrinting, setIsDirectPrinting] = useState(false); // ✅ NEW

  const [saleType, setSaleType] = useState('CASH'); // CASH | CREDIT
  const [dueDate, setDueDate] = useState('');
  const canUseCredit = isManager(); // credit sales are ADMIN/MANAGER only

  const [showOrderDialog, setShowOrderDialog] = useState(false);
  const [orderNotes, setOrderNotes] = useState('');
  const [loadedDraftId, setLoadedDraftId] = useState(null);

  // Shop info (for receipt branding) — shared cache via useShopConfig
  const { data: shopInfoData } = useShopConfig();

  const shopInfo = shopInfoData?.data;

  const { data: customizationData } = useQuery({
    queryKey: ['receipt-customization-pos'],
    queryFn: () => receiptCustomizationService.get(),
    enabled: true,
  });

  const customization     = customizationData?.data || {};

  // QR for the on-screen receipt dialog — same data the printed copy carries.
  // Placed AFTER showReceiptDialog/lastSale/customization are declared.
  const [receiptQrDataUrl, setReceiptQrDataUrl] = useState(null);
  useEffect(() => {
    let cancelled = false;
    if (showReceiptDialog && lastSale?.invoiceNumber && customization?.showQRCode) {
      generateQRDataUrl(lastSale.invoiceNumber).then((url) => {
        if (!cancelled) setReceiptQrDataUrl(url);
      });
    } else {
      setReceiptQrDataUrl(null);
    }
    return () => { cancelled = true; };
  }, [showReceiptDialog, lastSale, customization]);

  // Fetch products

  const { data: productsData, isLoading } = useQuery({
    queryKey: ['products-pos', page],
    queryFn: () => productService.getAll(page, pageSize),
    keepPreviousData: true,
    staleTime: 30_000,
    gcTime: 5 * 60 * 1000,
  });
  const products = productsData?.data?.content || [];
  const totalPages = productsData?.data?.page?.totalPages || 0;

  useEffect(() => {
    if (!draftId || loadedDraftId === draftId) return;
    let cancelled = false;
    const loadDraft = async () => {
      const allDrafts = readDrafts();
      const draft = allDrafts.find((d) => d.id === draftId);
      if (!draft) return;
      const loadedItems = await Promise.all((draft.items || []).map(async (item) => {
        try {
          const response = await productService.getById(item.productId);
          const product = response.data;
          const available = Number(product?.stockQuantity ?? product?.availableQuantity ?? 0);
          if (!product || available <= 0) return null;
          return {
            productId: product.id,
            name: product.name,
            price: product.unitPrice,
            quantity: Math.min(Number(item.quantity) || 1, available),
            stockQuantity: available,
          };
        } catch {
          return null;
        }
      }));
      if (cancelled) return;
      const missingCount = (draft.items || []).length - loadedItems.filter(Boolean).length;
      setCart(loadedItems.filter(Boolean));
      setLoadedDraftId(draftId);
      setSearchParams({}, { replace: true });
      if (missingCount > 0) notifyWarning(t('draft_items_unavailable', { count: missingCount }));
    };
    loadDraft();
    return () => { cancelled = true; };
  }, [draftId, loadedDraftId, setSearchParams, t]);

  // Fetch customers
  const { data: customersData } = useQuery({
    queryKey: ['customers-pos', debouncedCustomerSearch],
    queryFn: () => customerService.search(debouncedCustomerSearch),
    enabled: debouncedCustomerSearch.length >= 2,
    staleTime: 30_000,
    gcTime: 5 * 60 * 1000,
  });
  const customers = customersData?.data?.content || [];

  // Filter products by search
  const [selectedCategory, setSelectedCategory] = useState('');

  const { data: categoriesData } = useQuery({
    queryKey: ['categories-pos'],
    queryFn: () => categoryService.getAll(0, 100),
    enabled: true,
    staleTime: 60_000,
    gcTime: 10 * 60 * 1000,
  });
  const categories = useMemo(() => categoriesData?.data?.content || [], [categoriesData]);

  const normalizedSearch = searchQuery.trim().toLowerCase();

  const filteredProducts = useMemo(() => {
    return products.filter((p) => {
      const matchesSearch = !normalizedSearch ||
        p.name?.toLowerCase().includes(normalizedSearch) ||
        p.sku?.toLowerCase().includes(normalizedSearch);

      const matchesCategory = !selectedCategory || String(p.categoryId) === String(selectedCategory);
      return matchesSearch && matchesCategory;
    });
  }, [products, normalizedSearch, selectedCategory]);


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
    setCashManuallyEdited(false);
    setCashierDiscount('');
    setDueDate('');
    setSaleType('CASH');
    setError('');
    setVerifiedTotals(null);
  };

  const subtotal = cart.reduce(
      (sum, item) => sum + item.price * (parseInt(item.quantity) || 0),
      0
  );

  const shopTaxPercentage = Number(shopInfo?.taxPercentage) || 0;

  const tax = subtotal * (shopTaxPercentage / 100);

  // Shop-level discount config (admin-controlled, mirrors tax)
  const discountEnabled = Boolean(shopInfo?.discountEnabled);
  const rawDiscountType = shopInfo?.discountType;
  const discountMode = ['FIXED', 'AMOUNT'].includes(rawDiscountType) ? rawDiscountType : 'PERCENTAGE';

  // Live estimate for the cart panel; server recomputes authoritatively
  let discountEstimate = 0;
  if (discountEnabled && subtotal > 0) {
    if (discountMode === 'PERCENTAGE') {
      discountEstimate = subtotal * ((Number(shopInfo?.discountValue) || 0) / 100);
    } else if (discountMode === 'FIXED') {
      const v = Number(shopInfo?.discountValue) || 0;
      discountEstimate = Math.min(Math.max(v, 0), subtotal);
    } else {
      const d = parseFloat(cashierDiscount);
      if (Number.isFinite(d) && d > 0) discountEstimate = Math.min(d, subtotal);
    }
  }
  discountEstimate = Math.max(0, Math.min(discountEstimate, subtotal));

  const total = subtotal + tax - discountEstimate;
  const change = cashAmount ? parseFloat(cashAmount) - total : 0;

  const displaySubtotal = verifiedTotals?.subtotal ?? subtotal;

  const displayTax = verifiedTotals?.taxAmount ?? tax;

  const displayTotal = verifiedTotals?.totalAmount ?? total;// fallback only before first verify
  const displayChange = cashAmount ? parseFloat(cashAmount) - displayTotal : 0;

  const availableCredit = selectedCustomer
    ? (selectedCustomer.creditLimit ?? 0) - (selectedCustomer.currentBalance ?? 0)
    : 0;

  // Auto-fill the cash-received field with the exact total so cashiers don't
  // have to type it. Once they edit it (customer pays more), we stop syncing.
  useEffect(() => {
    if (saleType !== 'CASH') return;
    if (cart.length === 0 || cashManuallyEdited) return;
    const t = Number(displayTotal);
    if (!isFinite(t) || t <= 0) return;
    setCashAmount(t.toFixed(2));
  }, [displayTotal, saleType, cart.length, cashManuallyEdited]);

  const verifyCartMutation = useMutation({
    mutationFn: (cartItems) => saleService.verifyCart(
      cartItems,
      discountEnabled && discountMode === 'AMOUNT' ? (parseFloat(cashierDiscount) || 0) : null
    ),
    onSuccess: (response) => {
      const result = response.data;

      if (result.valid) {
        setVerifiedTotals({
          subtotal: result.subtotal,
          taxAmount: result.taxAmount,
          discountAmount: result.discountAmount,
          totalAmount: result.totalAmount,
        });
        // Checkout & Print: skip the confirmation dialog, create + print right away.
        // createSaleMutation is defined below — safe, because this callback only
        // runs long after render (deferred execution), but the linter can't know.
        if (printAfterCheckout) {
          // eslint-disable-next-line no-use-before-define
          createSaleMutation.mutate(buildSaleData());
          return;
        }
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
      setPrintAfterCheckout(false); // don't surprise-print on a later manual checkout
      notifyError(err.friendlyMessage || t('verify_cart_failed'));
    },
  });

  const createSaleMutation = useMutation({
    mutationFn: async (saleData) => {
      const response = await saleService.create(saleData);
      return response;
    },
    onSuccess: (response) => {
      const sale = response.data;
      setLastSale(sale);
      setShowCheckoutDialog(false);
      setShowReceiptDialog(true);
      clearCart();
      if (printAfterCheckout) {
        setPrintAfterCheckout(false);
        handlePrintAfterCheckout(sale);
      }
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
      setPrintAfterCheckout(false);
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

    const createOrderMutation = useMutation({
    mutationFn: (orderData) => orderService.create(orderData),
    onSuccess: () => {
      notifySuccess(t('order_created'));
      setShowOrderDialog(false);
      setOrderNotes('');
      queryClient.invalidateQueries({ queryKey: ['orders'] });
      queryClient.invalidateQueries({ queryKey: ['products-pos'] });
      queryClient.invalidateQueries({ queryKey: ['products'] });
      queryClient.invalidateQueries({ queryKey: ['low-stock'] });
      queryClient.invalidateQueries({ queryKey: ['inventoryReport'] });
      clearCart();
    },
    onError: (err) => {
      notifyError(err.friendlyMessage || t('order_failed'));
    },
  });

  const handleSaveDraft = () => {
    if (cart.length === 0) {
      setError(t('empty_cart'));
      return;
    }

    const allDrafts = readDrafts();
    const draftItems = cart.map((item) => ({
      productId: item.productId,
      quantity: parseInt(item.quantity, 10) || 1,
      productName: item.name || item.productName || '',
      unitPrice: Number(item.price ?? item.unitPrice ?? 0),
    }));

    if (loadedDraftId) {
      const idx = allDrafts.findIndex((d) => d.id === loadedDraftId);
      if (idx >= 0) {
        allDrafts[idx] = {
          ...allDrafts[idx],
          items: draftItems,
          customerId: registeredMode ? (selectedCustomer?.id ?? null) : null,
          expiresAt: Date.now() + DRAFT_TTL_MS,
        };
      }
    } else {
      const newDraftId = 'draft_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
      allDrafts.push(
        createDraftRecord({
          id: newDraftId,
          items: draftItems,
          customerId: registeredMode ? (selectedCustomer?.id ?? null) : null,
        }),
      );
      setLoadedDraftId(newDraftId);
    }

    writeDrafts(allDrafts);
    notifySuccess(t('draft_saved'));
    clearCart();
  };

  const handleOrderClick = () => {
    if (cart.length === 0) {
      setError(t('empty_cart'));
      return;
    }
    const sanitizedCart = cart.map(item => {
      const qty = parseInt(item.quantity, 10);
      if (isNaN(qty) || qty <= 0) {
        return { ...item, quantity: 1 };
      }
      return item;
    });
    setCart(sanitizedCart);
    setOrderNotes('');
    setShowOrderDialog(true);
  };

  const confirmOrder = () => {
    createOrderMutation.mutate({
      items: cart.map((item) => ({
        productId: item.productId,
        quantity: parseInt(item.quantity, 10) || 1,
      })),
      customerId: registeredMode ? (selectedCustomer?.id ?? null) : null,
      notes: orderNotes.trim() || undefined,
    });
  };

    useEffect(() => {
    const timer = setTimeout(() => setDebouncedCustomerSearch(customerSearch), 300);
    return () => clearTimeout(timer);
  }, [customerSearch]);


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

    if (saleType === 'CREDIT') {
      // Credit requires a registered customer + due date before checkout.
      if (!registeredMode || !selectedCustomer) {
        setError(t('select_credit_customer'));
        return;
      }
      if (!dueDate) {
        setError(t('enter_due_date'));
        return;
      }
    } else if (!cashAmount || parseFloat(cashAmount) <= 0) {
      setError(t('enter_cash_amount'));
      return;
    }
    verifyCartMutation.mutate(sanitizedCart); // opens the dialog itself on success, via onSuccess above
  };

  const getDiscountPayload = () =>
    discountEnabled && discountMode === 'AMOUNT'
      ? Math.max(0, Math.min(parseFloat(cashierDiscount) || 0, subtotal))
      : null;

  // Shared by the confirm dialog and the direct Checkout & Print path
  function buildSaleData() {
    if (saleType === 'CREDIT') {
      return {
        items: cart.map((item) => ({
          productId: item.productId,
          quantity: parseInt(item.quantity, 10) || 1,
          price: item.price,
        })),
        customerId: selectedCustomer?.id ?? null,
        customerName: null,
        paymentMethod: 'CREDIT',
        saleType: 'CREDIT',
        dueDate,
        amountPaid: 0,
        discountAmount: getDiscountPayload(),
      };
    }
    return {
      items: cart.map((item) => ({
        productId: item.productId,
        quantity: parseInt(item.quantity, 10) || 1, // Fallback to 1 just in case
        price: item.price,
      })),
      customerId: registeredMode ? (selectedCustomer?.id ?? null) : null,
      customerName: registeredMode ? null : (customerNameInput.trim() || null),
      paymentMethod: 'CASH',
      saleType: 'CASH',
      amountPaid: parseFloat(cashAmount),
      discountAmount: getDiscountPayload(),
    };
  }

  const confirmCheckout = () => {
    createSaleMutation.mutate(buildSaleData());
  }

  const handleSaleTypeChange = (event, newType) => {
    if (!newType) return;
    setSaleType(newType);
    if (newType === 'CREDIT') {
      setRegisteredMode(true);
      setCustomerNameInput('');
    } else {
      // Back to CASH: re-enable auto-fill so the field tracks the total again
      setCashManuallyEdited(false);
    }
    setError('');
  };

  // Browser printing uses the same self-contained renderer as silent printing.
  const handlePrintReceipt = async (saleOverride) => {
    const sale = saleOverride?.invoiceNumber ? saleOverride : lastSale;
    if (!sale?.invoiceNumber) return;
    try {
      let logoDataUrl = null;
      if (shopInfo?.hasLogo) {
        const blob = await shopInfoService.getLogo();
        logoDataUrl = await new Promise((resolve) => {
          const reader = new FileReader();
          reader.onload = () => resolve(reader.result);
          reader.onerror = () => resolve(null);
          reader.readAsDataURL(blob);
        });
      }
      const qrDataUrl = customization?.showQRCode
        ? await generateQRDataUrl(sale.invoiceNumber)
        : null;
      const htmlContent = generatePrintHtml(sale, shopInfo || {}, customization, logoDataUrl, qrDataUrl);
      const printWindow = window.open('', '_blank');
      if (!printWindow) throw new Error('Unable to open print window');
      printWindow.document.write(htmlContent);
      printWindow.document.close();
      printWindow.onload = () => printWindow.print();
    } catch (err) {
      notifyError(err.message || t('load_print_failed'));
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

  // Smart Direct Print: uses generatePrintHtml so the printed paper matches the on-screen receipt.
  // Accepts an optional sale object so "Checkout & Print" can print immediately after sale creation.
  async function handleDirectPrint(saleOverride) {
    const sale = (saleOverride && saleOverride.invoiceNumber) ? saleOverride : lastSale;
    if (!sale?.invoiceNumber) return;
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
          qrDataUrl = await generateQRDataUrl(sale.invoiceNumber);
        }
        const html = generatePrintHtml(sale, shopInfo || {}, customization, logoDataUrl, qrDataUrl);
        const result = await directPrint.print(html, null, paperWidthMm);
        if (result.success) {
          notifySuccess(t('receipt_sent_printer'));
        } else {
          notifyError(result.error || t('print_failed'));
        }
      } else {
        handlePrintReceipt(sale);
      }
    } catch (err) {
      notifyError(err.message || t('print_failed'));
    } finally {
      setIsDirectPrinting(false);
    }
  }

  const handleCheckoutAndPrint = () => {
    setPrintAfterCheckout(true);
    handleCheckout();
  };

  async function handlePrintAfterCheckout(sale) {
    if (!sale?.invoiceNumber) return;
    await handleDirectPrint(sale);
  }

  async function handleCounterPrint() {
    if (!lastSale?.invoiceNumber) return;
    setCounterPrinting(true);
    try {
      const res = await counterPrintService.printReceipt(lastSale.invoiceNumber);
      if (res?.success) notifySuccess(t('receipt_sent_printer'));
      else notifyError(res?.message || t('print_failed'));
    } catch (err) {
      const reason = err.friendlyMessage || err.response?.data?.message || err.message || t('print_failed');
      console.error('[Print queue] Unable to queue receipt', { invoiceNumber: lastSale.invoiceNumber, reason, error: err });
      notifyError(`Print request failed: ${reason}`);
    } finally {
      setCounterPrinting(false);
    }
  }


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
                      <Box sx={{ p: 1, pb: 0.5, display: 'flex', justifyContent: 'center', bgcolor: 'background.default' }}>
                        <ProductImage productId={product.id} hasImage={product.hasImage} size={130} />
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
              {discountEnabled && discountMode === 'PERCENTAGE' && (
                <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 1 }}>
                  <Typography color="secondary">
                    {t('discount_pct_label', { pct: Number(shopInfo?.discountValue) || 0 })}
                  </Typography>
                  <Typography color="secondary">-{formatCurrency(discountEstimate)}</Typography>
                </Box>
              )}
              {discountEnabled && discountMode === 'FIXED' && (
                <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 1 }}>
                  <Typography color="secondary">{t('discount_fixed_label')}</Typography>
                  <Typography color="secondary">-{formatCurrency(discountEstimate)}</Typography>
                </Box>
              )}
              {discountEnabled && discountMode === 'AMOUNT' && (
                <TextField
                  fullWidth
                  label={t('discount_amount')}
                  type="number"
                  value={cashierDiscount}
                  onChange={(e) => setCashierDiscount(e.target.value)}
                  onWheel={preventWheelChange}
                  size="small"
                  sx={{ mb: 1 }}
                  inputProps={{ min: 0, step: '0.01' }}
                  helperText={t('discount_amount_helper')}
                />
              )}
              <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 2 }}>
                <Typography variant="h6">{t('total')}</Typography>
                <Typography variant="h6" color="primary">
                  {formatCurrency(displayTotal)}
                </Typography>
              </Box>

              {canUseCredit && (
                <ToggleButtonGroup
                  size="small"
                  exclusive
                  fullWidth
                  value={saleType}
                  onChange={handleSaleTypeChange}
                  sx={{ mb: 1 }}
                >
                  <ToggleButton value="CASH" sx={{ flex: 1, textTransform: 'none' }}>
                    {t('sale_type_cash')}
                  </ToggleButton>
                  <ToggleButton value="CREDIT" sx={{ flex: 1, textTransform: 'none' }} color="primary">
                    {t('sale_type_credit')}
                  </ToggleButton>
                </ToggleButtonGroup>
              )}

              {saleType === 'CREDIT' ? (
                <>
                  {selectedCustomer && registeredMode && (
                    <Box
                        sx={{
                          mb: 1,
                          p: 1,
                          bgcolor: 'background.default',
                          borderRadius: 1,
                          display: 'flex',
                          alignItems: 'center',
                          justifyContent: 'space-between',
                          gap: 1,
                        }}
                      >
                        <Typography variant="body2" color="text.secondary">
                          {t('available_credit')}
                        </Typography>

                        <Typography
                          variant="body2"
                          fontWeight={600}
                          color={availableCredit < 0 ? 'error' : 'primary'}
                        >
                          {formatCurrency(availableCredit)}
                        </Typography>
                      </Box>
                  )}
                  <TextField
                    fullWidth
                    label={t('due_date')}
                    type="date"
                    value={dueDate}
                    onChange={(e) => setDueDate(e.target.value)}
                    size="small"
                    InputLabelProps={{ shrink: true }}
                    sx={{ mb: 1 }}
                  />
                </>
              ) : (
                <>
                  <TextField
                    fullWidth
                    label={t('cash_amount')}
                    type="number"
                    value={cashAmount}
                    onChange={(e) => { setCashAmount(e.target.value); setCashManuallyEdited(true); }}
                    onWheel={preventWheelChange}
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
                </>
              )}

              <Button
                fullWidth
                variant="contained"
                size="large"
                onClick={handleCheckoutAndPrint}
                disabled={cart.length === 0}
                startIcon={<DirectPrintIcon />}
                sx={{ py: 1.75, fontSize: '1.05rem', mt: 1, bgcolor: 'primary.main' }}
              >
                {saleType === 'CREDIT' ? t('complete_credit_sale_print') : t('checkout_and_print')}
              </Button>
              <Button
                fullWidth
                variant="contained"
                size="large"
                onClick={() => { setPrintAfterCheckout(false); handleCheckout(); }}
                disabled={cart.length === 0}
                sx={{
                  py: 1.75,
                  fontSize: '1.05rem',
                  mt: 1,
                  bgcolor: 'rgba(43,110,79,0.12)',
                  color: 'primary.dark',
                  '&:hover': { bgcolor: 'rgba(43,110,79,0.2)' },
                }}
              >
                {saleType === 'CREDIT' ? t('complete_credit_sale') : t('checkout')}
              </Button>
              <Button
                fullWidth
                variant="outlined"
                onClick={clearCart}
                sx={{ mt: 1 }}
              >
                {t('clear_cart')}
              </Button>
              <Button
                fullWidth
                variant="outlined"
                color="secondary"
                startIcon={<OrderIcon />}
                onClick={handleOrderClick}
                disabled={cart.length === 0}
                sx={{ mt: 1 }}
              >
                {t('order_now')}
              </Button>
              <Button
                fullWidth
                variant="outlined"
                startIcon={<CartIcon />}
                onClick={handleSaveDraft}
                disabled={cart.length === 0}
                sx={{ mt: 1 }}
              >
                {t('save_draft')}
              </Button>
            </Box>
            </Box>
          </Paper>
        </Grid>
      </Grid>

      {/* Checkout Confirmation Dialog */}
      <Dialog open={showCheckoutDialog} onClose={() => { setShowCheckoutDialog(false); setPrintAfterCheckout(false); }}>
        <DialogTitle>{t('confirm_checkout')}</DialogTitle>
        <DialogContent>
          <Typography>{t('items_count', { count: cart.length })}</Typography>
          <Typography>{t('total')}: {formatCurrency(displayTotal)}</Typography>
          {Number(verifiedTotals?.discountAmount) > 0 && (
            <Typography color="secondary">
              {t('discount_amount')}: -{formatCurrency(verifiedTotals.discountAmount)}
            </Typography>
          )}
          {saleType === 'CREDIT' ? (
            <>
              <Typography>{t('sale_type_credit')}</Typography>
              <Typography>{t('due_date')}: {dueDate}</Typography>
            </>
          ) : (
            <>
              <Typography>{t('cash_label')} {formatCurrency(parseFloat(cashAmount) || 0)}</Typography>
              <Typography>{t('change')} {formatCurrency(displayChange)}</Typography>
            </>
          )}
          {selectedCustomer && (
            <Typography>{t('customer_label', { name: `${selectedCustomer.firstName} ${selectedCustomer.lastName}` })}</Typography>
          )}
          {saleType !== 'CREDIT' && parseFloat(cashAmount) < displayTotal && (
            <Alert severity="error" sx={{ mt: 2 }}>
              {t('cash_less_than_total', { amount: formatCurrency(displayTotal - (parseFloat(cashAmount) || 0)) })}
            </Alert>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => { setShowCheckoutDialog(false); setPrintAfterCheckout(false); }}>{t('cancel')}</Button>
          <Button
            onClick={confirmCheckout}
            variant="contained"
            color="primary"
            disabled={saleType === 'CREDIT' ? false : parseFloat(cashAmount) < displayTotal}
          >
            {printAfterCheckout ? t('confirm_and_print') : t('confirm')}
          </Button>
        </DialogActions>
      </Dialog>



      {/* Order Dialog — reserves stock for a pending order */}
      <Dialog open={showOrderDialog} onClose={() => setShowOrderDialog(false)} maxWidth="xs" fullWidth>
        <DialogTitle>{t('confirm_order')}</DialogTitle>
        <DialogContent>
          <Typography>{t('items_count', { count: cart.length })}</Typography>
          <Typography>{t('total')}: {formatCurrency(displayTotal)}</Typography>
          {registeredMode && selectedCustomer && (
            <Typography>{t('customer_label', { name: `${selectedCustomer.firstName} ${selectedCustomer.lastName}` })}</Typography>
          )}
          <Alert severity="info" sx={{ mt: 2 }}>
            {t('order_reserves_stock')}
          </Alert>
          <TextField
            fullWidth
            label={t('notes_optional')}
            value={orderNotes}
            onChange={(e) => setOrderNotes(e.target.value)}
            size="small"
            multiline
            rows={2}
            sx={{ mt: 2 }}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setShowOrderDialog(false)}>{t('cancel')}</Button>
          <Button
            onClick={confirmOrder}
            variant="contained"
            color="secondary"
            disabled={createOrderMutation.isPending}
          >
            {t('confirm_order')}
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
                qrDataUrl={receiptQrDataUrl}
              />
            </Box>
          )}
        </DialogContent>
                <DialogActions sx={{ p: 2, flexDirection: 'column', gap: 1 }}>
          
          {/* ✅ NEW: Smart Direct Print Button (Always visible!) */}
          <Button 
            onClick={() => handleDirectPrint()} 
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
                ? '⚡ Print Button 1' 
                : '⚡ Print Button 1'}
          </Button>

          <Button
            onClick={handleCounterPrint}
            variant="contained"
            color="secondary"
            fullWidth
            startIcon={counterPrinting ? <CircularProgress size={20} sx={{ color: 'white' }} /> : <DirectPrintIcon />}
            disabled={counterPrinting || !lastSale?.invoiceNumber}
            sx={{ py: 1.2, fontSize: '1rem' }}
          >
            {counterPrinting ? t('printing') : 'Print Button 2'}
          </Button>

          <Box sx={{ display: 'flex', gap: 1, width: '100%' }}>
            <Button onClick={() => handleDownloadPdf()} variant="outlined" fullWidth>
              {t('download_pdf')}
            </Button>
            <Button onClick={handlePrintReceipt} variant="outlined" fullWidth>
              {t('print_browser')}
            </Button>
          </Box>

          <Button onClick={() => setShowReceiptDialog(false)} variant="text" fullWidth>
            {t('close')}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default POS;