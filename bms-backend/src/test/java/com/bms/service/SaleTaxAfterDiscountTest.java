package com.bms.service;

import com.bms.dto.request.CartVerifyRequest;
import com.bms.dto.request.SaleCreateRequest;
import com.bms.dto.request.SalePaymentRequest;
import com.bms.dto.request.SaleReturnRequest;
import com.bms.dto.response.CartVerifyResponse;
import com.bms.dto.response.ReturnableItemsResponse;
import com.bms.dto.response.SaleReturnResponse;
import com.bms.entity.CashShift;
import com.bms.entity.Order;
import com.bms.entity.OrderItem;
import com.bms.entity.PaymentMethod;
import com.bms.entity.Product;
import com.bms.entity.Sale;
import com.bms.entity.SaleItem;
import com.bms.entity.SaleReturn;
import com.bms.entity.ShopInfo;
import com.bms.entity.User;
import com.bms.repository.CashShiftRepository;
import com.bms.repository.CustomerRepository;
import com.bms.repository.ProductRepository;
import com.bms.repository.SaleItemRepository;
import com.bms.repository.SalePaymentRepository;
import com.bms.repository.SaleRepository;
import com.bms.repository.SaleReturnRepository;
import com.bms.repository.ShopInfoRepository;
import com.bms.repository.StockMovementRepository;
import com.bms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * P5: tax must be computed on the discounted line value, not the gross.
 * Acceptance: line 100, discount 20, tax 10% -> total 88.00, tax 8.00.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SaleTaxAfterDiscountTest {

    @Mock private SaleRepository saleRepository;
    @Mock private ProductRepository productRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private StockMovementRepository stockMovementRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private UserRepository userRepository;
    @Mock private SaleReturnService saleReturnServiceMock;
    @Mock private SaleItemRepository saleItemRepository;
    @Mock private CashShiftRepository cashShiftRepository;
    @Mock private ShopInfoRepository shopInfoRepository;
    @Mock private SequenceService sequenceService;
    @Mock private PromotionService promotionService;
    @Mock private PaymentValidationService paymentValidationService;
    @Mock private CashDrawerSessionService cashDrawerSessionService;
    @Mock private SalePaymentRepository salePaymentRepository;
    @Mock private SaleReturnRepository saleReturnRepository;

    private SaleService saleService;
    private SaleReturnService saleReturnService;

    private final Map<Long, Product> products = new HashMap<>();
    private final Map<Long, Sale> sales = new HashMap<>();
    private final Map<Long, SaleItem> saleItems = new HashMap<>();
    private final AtomicLong saleIds = new AtomicLong(100);
    private final AtomicLong itemIds = new AtomicLong(1000);
    private final AtomicLong returnIds = new AtomicLong(500);
    private ShopInfo shopInfo;

    @BeforeEach
    void setUp() {
        saleService = new SaleService();
        ReflectionTestUtils.setField(saleService, "saleRepository", saleRepository);
        ReflectionTestUtils.setField(saleService, "productRepository", productRepository);
        ReflectionTestUtils.setField(saleService, "customerRepository", customerRepository);
        ReflectionTestUtils.setField(saleService, "stockMovementRepository", stockMovementRepository);
        ReflectionTestUtils.setField(saleService, "auditLogService", auditLogService);
        ReflectionTestUtils.setField(saleService, "userRepository", userRepository);
        ReflectionTestUtils.setField(saleService, "saleReturnService", saleReturnServiceMock);
        ReflectionTestUtils.setField(saleService, "saleItemRepository", saleItemRepository);
        ReflectionTestUtils.setField(saleService, "cashShiftRepository", cashShiftRepository);
        ReflectionTestUtils.setField(saleService, "shopInfoRepository", shopInfoRepository);
        ReflectionTestUtils.setField(saleService, "sequenceService", sequenceService);
        ReflectionTestUtils.setField(saleService, "promotionService", promotionService);
        ReflectionTestUtils.setField(saleService, "paymentValidationService", paymentValidationService);
        ReflectionTestUtils.setField(saleService, "cashDrawerSessionService", cashDrawerSessionService);
        ReflectionTestUtils.setField(saleService, "salePaymentRepository", salePaymentRepository);
        ReflectionTestUtils.setField(saleService, "saleReturnRepository", saleReturnRepository);

        saleReturnService = new SaleReturnService();
        ReflectionTestUtils.setField(saleReturnService, "saleRepository", saleRepository);
        ReflectionTestUtils.setField(saleReturnService, "saleItemRepository", saleItemRepository);
        ReflectionTestUtils.setField(saleReturnService, "saleReturnRepository", saleReturnRepository);
        ReflectionTestUtils.setField(saleReturnService, "productRepository", productRepository);
        ReflectionTestUtils.setField(saleReturnService, "stockMovementRepository", stockMovementRepository);
        ReflectionTestUtils.setField(saleReturnService, "userRepository", userRepository);
        ReflectionTestUtils.setField(saleReturnService, "customerRepository", customerRepository);
        ReflectionTestUtils.setField(saleReturnService, "auditLogService", auditLogService);
        ReflectionTestUtils.setField(saleReturnService, "paymentValidationService", paymentValidationService);
        ReflectionTestUtils.setField(saleReturnService, "cashDrawerSessionService", cashDrawerSessionService);
        ReflectionTestUtils.setField(saleReturnService, "salePaymentRepository", salePaymentRepository);

        shopInfo = new ShopInfo();
        shopInfo.setTaxPercentage(new BigDecimal("10"));
        shopInfo.setDiscountEnabled(true);
        shopInfo.setDiscountType(ShopInfo.DiscountType.AMOUNT);
        shopInfo.setDiscountValue(BigDecimal.ZERO);
        lenient().when(shopInfoRepository.findTopByOrderByIdAsc()).thenReturn(Optional.of(shopInfo));

        lenient().when(sequenceService.nextInvoiceNumber()).thenReturn("INV-T-0001");

        CashShift shift = new CashShift();
        shift.setId(1L);
        lenient().when(paymentValidationService.requireOpenShift(anyLong())).thenReturn(shift);
        lenient().when(paymentValidationService.findOpenShift(anyLong())).thenReturn(shift);
        lenient().when(paymentValidationService.parseMethod(anyString())).thenReturn(PaymentMethod.CASH);
        lenient().when(paymentValidationService.parseMethod("CARD")).thenReturn(PaymentMethod.CARD);
        lenient().when(paymentValidationService.resolvePayments(any(), any(), any()))
                .thenAnswer(inv -> {
                    BigDecimal total = inv.getArgument(1);
                    SalePaymentRequest line = new SalePaymentRequest();
                    line.setMethod("CASH");
                    line.setAmount(total);
                    List<SalePaymentRequest> lines = new ArrayList<>();
                    lines.add(line);
                    return lines;
                });
        lenient().when(paymentValidationService.saveSalePayments(any(), any(), any()))
                .thenReturn(new ArrayList<>());

        lenient().when(promotionService.calculateBestPrice(any(Product.class), anyInt(), any(), any()))
                .thenAnswer(inv -> {
                    Product p = inv.getArgument(0);
                    return new PricingQuote(p.getUnitPrice(), p.getUnitPrice(), BigDecimal.ZERO,
                            null, null, null, null, "BASE");
                });

        lenient().when(productRepository.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(products.get(inv.getArgument(0))));
        lenient().when(productRepository.findByIdForUpdate(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(products.get(inv.getArgument(0))));
        lenient().when(productRepository.save(any(Product.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        lenient().when(saleRepository.save(any(Sale.class))).thenAnswer(inv -> {
            Sale sale = inv.getArgument(0);
            if (sale.getId() == null) {
                sale.setId(saleIds.incrementAndGet());
                for (SaleItem item : sale.getItems()) {
                    if (item.getId() == null) {
                        item.setId(itemIds.incrementAndGet());
                        saleItems.put(item.getId(), item);
                    }
                }
            }
            sales.put(sale.getId(), sale);
            return sale;
        });
        lenient().when(saleRepository.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(sales.get(inv.getArgument(0))));

        lenient().when(saleItemRepository.findByIdForUpdate(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(saleItems.get(inv.getArgument(0))));

        lenient().when(saleReturnRepository.save(any(SaleReturn.class))).thenAnswer(inv -> {
            SaleReturn ret = inv.getArgument(0);
            if (ret.getId() == null) {
                ret.setId(returnIds.incrementAndGet());
            }
            return ret;
        });

        User cashier = new User();
        cashier.setId(7L);
        cashier.setUsername("cashier");
        lenient().when(userRepository.findById(anyLong())).thenReturn(Optional.of(cashier));

        lenient().when(salePaymentRepository.findBySaleIdOrderByPaidAtAsc(anyLong()))
                .thenReturn(new ArrayList<>());
        lenient().when(stockMovementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private Product product(long id, String unitPrice) {
        Product p = new Product();
        p.setId(id);
        p.setName("Product " + id);
        p.setUnitPrice(new BigDecimal(unitPrice));
        p.setCostPrice(new BigDecimal("10"));
        p.setStockQuantity(100);
        products.put(id, p);
        return p;
    }

    private SaleCreateRequest saleRequest(BigDecimal discount, long[] productIds, int[] quantities) {
        SaleCreateRequest request = new SaleCreateRequest();
        List<SaleCreateRequest.SaleItemRequest> items = new ArrayList<>();
        for (int i = 0; i < productIds.length; i++) {
            SaleCreateRequest.SaleItemRequest item = new SaleCreateRequest.SaleItemRequest();
            item.setProductId(productIds[i]);
            item.setQuantity(quantities[i]);
            items.add(item);
        }
        request.setItems(items);
        request.setDiscountAmount(discount);
        return request;
    }

    private Sale createAndCapture(SaleCreateRequest request) {
        saleService.createSale(request, 7L);
        ArgumentCaptor<Sale> captor = ArgumentCaptor.forClass(Sale.class);
        verify(saleRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " but was " + actual);
    }

    @Test
    void p5_taxIsComputedOnDiscountedLineValue() {
        product(1L, "100.00");
        Sale sale = createAndCapture(saleRequest(new BigDecimal("20.00"),
                new long[]{1L}, new int[]{1}));

        assertMoney("100.00", sale.getSubtotal());
        assertMoney("20.00", sale.getDiscountAmount());
        assertMoney("8.00", sale.getTaxAmount());
        assertMoney("88.00", sale.getTotalAmount());

        SaleItem item = sale.getItems().get(0);
        assertMoney("100.00", item.getTotalPrice());
        assertMoney("8.00", item.getTaxAmount());
    }

    @Test
    void p5_multiLineHeaderTaxEqualsSumOfLineTaxes() {
        product(1L, "10.01");
        product(2L, "10.01");
        product(3L, "10.01");
        Sale sale = createAndCapture(saleRequest(new BigDecimal("10.00"),
                new long[]{1L, 2L, 3L}, new int[]{1, 1, 1}));

        Map<SaleItem, BigDecimal> allocations = SaleDiscountUtils.allocateDiscounts(sale);
        BigDecimal allocatedTotal = BigDecimal.ZERO;
        BigDecimal lineTaxSum = BigDecimal.ZERO;
        BigDecimal lineNetPlusTaxSum = BigDecimal.ZERO;
        for (SaleItem item : sale.getItems()) {
            BigDecimal share = allocations.get(item);
            allocatedTotal = allocatedTotal.add(share);
            lineTaxSum = lineTaxSum.add(item.getTaxAmount());
            lineNetPlusTaxSum = lineNetPlusTaxSum
                    .add(item.getTotalPrice().subtract(share).add(item.getTaxAmount()));
        }

        assertMoney("10.00", allocatedTotal);
        assertMoney("30.03", sale.getSubtotal());
        assertEquals(0, lineTaxSum.compareTo(sale.getTaxAmount()),
                "sum of line taxes must equal header tax");
        assertEquals(0, lineNetPlusTaxSum.compareTo(sale.getTotalAmount()),
                "total must equal sum of discounted lines plus their tax");
        assertMoney("2.01", sale.getTaxAmount());
        assertMoney("22.04", sale.getTotalAmount());
    }

    @Test
    void p5_percentageShopDiscountAlsoShrinksTaxBase() {
        shopInfo.setDiscountType(ShopInfo.DiscountType.PERCENTAGE);
        shopInfo.setDiscountValue(new BigDecimal("10"));
        product(1L, "100.00");

        Sale sale = createAndCapture(saleRequest(null, new long[]{1L}, new int[]{1}));

        assertMoney("10.00", sale.getDiscountAmount());
        assertMoney("9.00", sale.getTaxAmount());
        assertMoney("99.00", sale.getTotalAmount());
    }

    @Test
    void p5_noDiscountKeepsTaxOnGross() {
        product(1L, "100.00");
        Sale sale = createAndCapture(saleRequest(null, new long[]{1L}, new int[]{1}));

        assertMoney("0.00", sale.getDiscountAmount());
        assertMoney("10.00", sale.getTaxAmount());
        assertMoney("110.00", sale.getTotalAmount());
    }

    @Test
    void p5_verifyCartMatchesCreateSale() {
        product(1L, "100.00");
        Sale sale = createAndCapture(saleRequest(new BigDecimal("20.00"),
                new long[]{1L}, new int[]{1}));

        CartVerifyRequest request = new CartVerifyRequest();
        CartVerifyRequest.CartVerifyItem item = new CartVerifyRequest.CartVerifyItem();
        item.setProductId(1L);
        item.setQuantity(1);
        item.setExpectedUnitPrice(new BigDecimal("100.00"));
        request.setItems(List.of(item));
        request.setDiscountAmount(new BigDecimal("20.00"));

        CartVerifyResponse response = saleService.verifyCart(request);

        assertMoney("100.00", response.getSubtotal());
        assertMoney("20.00", response.getDiscountAmount());
        assertMoney("8.00", response.getTaxAmount());
        assertMoney("88.00", response.getTotalAmount());
        assertEquals(0, response.getTotalAmount().compareTo(sale.getTotalAmount()));
        assertEquals(0, response.getTaxAmount().compareTo(sale.getTaxAmount()));
    }

    @Test
    void p5_verifyCartMultiLineMatchesCreateSale() {
        product(1L, "10.01");
        product(2L, "10.01");
        product(3L, "10.01");
        Sale sale = createAndCapture(saleRequest(new BigDecimal("10.00"),
                new long[]{1L, 2L, 3L}, new int[]{1, 1, 1}));

        CartVerifyRequest request = new CartVerifyRequest();
        List<CartVerifyRequest.CartVerifyItem> items = new ArrayList<>();
        for (long id : new long[]{1L, 2L, 3L}) {
            CartVerifyRequest.CartVerifyItem item = new CartVerifyRequest.CartVerifyItem();
            item.setProductId(id);
            item.setQuantity(1);
            item.setExpectedUnitPrice(new BigDecimal("10.01"));
            items.add(item);
        }
        request.setItems(items);
        request.setDiscountAmount(new BigDecimal("10.00"));

        CartVerifyResponse response = saleService.verifyCart(request);
        assertEquals(0, response.getTaxAmount().compareTo(sale.getTaxAmount()));
        assertEquals(0, response.getTotalAmount().compareTo(sale.getTotalAmount()));
    }

    @Test
    void p5_refundReturnsExactlyWhatWasPaidForThoseUnits() {
        product(1L, "100.00");
        // 2 units @ 100, header discount 40, tax 10% -> paid 176.00 (88.00 per unit).
        Sale sale = createAndCapture(saleRequest(new BigDecimal("40.00"),
                new long[]{1L}, new int[]{2}));
        assertMoney("176.00", sale.getTotalAmount());

        SaleItem saleItem = sale.getItems().get(0);

        ReturnableItemsResponse returnable = saleReturnService.getReturnableItems(sale.getId());
        assertMoney("88.00", returnable.getItems().get(0).getUnitRefundAmount());

        SaleReturnRequest request = new SaleReturnRequest();
        request.setReason("Customer changed mind");
        request.setRefundMethod("CARD");
        SaleReturnRequest.SaleReturnItemRequest item = new SaleReturnRequest.SaleReturnItemRequest();
        item.setSaleItemId(saleItem.getId());
        item.setQuantity(1);
        request.setItems(List.of(item));

        SaleReturnResponse response = saleReturnService.createSaleReturn(sale.getId(), request, 7L);

        assertMoney("88.00", response.getTotalReturnAmount());
        assertMoney("88.00", response.getItems().get(0).getReturnAmount());
        assertEquals(1, saleItem.getQuantityRefunded());
    }

    @Test
    void p2_orderConversionBooksCogsAtFulfilmentCostNotOrderCost() {
        // Ordered Monday at cost 60; WAC rose to 90 by fulfilment on Friday.
        Product p = product(1L, "100.00");
        p.setCostPrice(new BigDecimal("90.00"));
        p.setReservedQuantity(10);

        Order order = new Order();
        order.setOrderNumber("ORD-1");
        order.setSubtotal(new BigDecimal("1000.00"));
        order.setTaxAmount(BigDecimal.ZERO);
        order.setTotalAmount(new BigDecimal("1000.00"));
        OrderItem oi = new OrderItem();
        oi.setProduct(p);
        oi.setQuantity(10);
        oi.setUnitPrice(new BigDecimal("100.00"));
        oi.setTotalPrice(new BigDecimal("1000.00"));
        oi.setTaxAmount(BigDecimal.ZERO);
        oi.setCostPriceAtOrder(new BigDecimal("60.00"));
        order.setItems(new ArrayList<>(List.of(oi)));

        saleService.createSaleFromOrder(order, "CASH", new BigDecimal("1000.00"), null, 7L);

        ArgumentCaptor<Sale> captor = ArgumentCaptor.forClass(Sale.class);
        verify(saleRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        SaleItem item = captor.getValue().getItems().get(0);

        // COGS uses the fulfilment cost (90), not the order quote (60).
        assertMoney("90.00", item.getCostPriceAtSale());
        assertMoney("900.00", CostCalculationUtils.calculateCogs(item.getCostPriceAtSale(), item.getQuantity()));
        assertMoney("100.00", CostCalculationUtils.calculateProfit(
                item.getUnitPrice(), item.getCostPriceAtSale(), item.getQuantity()));
        // The order-time quote is preserved for margin quoting, untouched.
        assertMoney("60.00", oi.getCostPriceAtOrder());
    }
}