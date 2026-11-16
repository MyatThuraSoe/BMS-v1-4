package com.bms.service;

import com.bms.dto.order.OrderCreateRequest;
import com.bms.dto.request.SaleCreateRequest;
import com.bms.dto.request.SalePaymentRequest;
import com.bms.entity.CashShift;
import com.bms.entity.InventoryLedger;
import com.bms.entity.Order;
import com.bms.entity.OrderItem;
import com.bms.entity.PaymentMethod;
import com.bms.entity.Product;
import com.bms.entity.ProfitLedger;
import com.bms.entity.Sale;
import com.bms.entity.SaleItem;
import com.bms.entity.ShopInfo;
import com.bms.entity.User;
import com.bms.repository.CashShiftRepository;
import com.bms.repository.CustomerRepository;
import com.bms.repository.InventoryLedgerRepository;
import com.bms.repository.LocationStockRepository;
import com.bms.repository.OrderRepository;
import com.bms.repository.ProductRepository;
import com.bms.repository.ProfitLedgerRepository;
import com.bms.repository.SaleItemRepository;
import com.bms.repository.SalePaymentRepository;
import com.bms.repository.SaleRepository;
import com.bms.repository.SaleReturnRepository;
import com.bms.repository.ShopInfoRepository;
import com.bms.repository.StockMovementRepository;
import com.bms.repository.SystemSettingRepository;
import com.bms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

/**
 * O6: a sale converted from an order must carry the same promotion figures as
 * the identical direct sale, otherwise the two are counted differently wherever
 * promotion discount is reported.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderPromotionParityTest {

    private static final BigDecimal TAX_RATE = new BigDecimal("10");
    private static final long PROMO_ID = 77L;
    private static final String PROMO_NAME = "Weekend markdown";

    @Mock private SaleRepository saleRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private ProductRepository productRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private StockMovementRepository stockMovementRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private UserRepository userRepository;
    @Mock private SaleItemRepository saleItemRepository;
    @Mock private SaleReturnService saleReturnService;
    @Mock private CashShiftRepository cashShiftRepository;
    @Mock private ShopInfoRepository shopInfoRepository;
    @Mock private SequenceService sequenceService;
    @Mock private PromotionService promotionService;
    @Mock private PaymentValidationService paymentValidationService;
    @Mock private CashDrawerSessionService cashDrawerSessionService;
    @Mock private SalePaymentRepository salePaymentRepository;
    @Mock private SaleReturnRepository saleReturnRepository;
    @Mock private PeriodGuard periodGuard;
    @Mock private SystemSettingRepository systemSettingRepository;
    @Mock private InventoryLedgerRepository inventoryLedgerRepository;
    @Mock private ProfitLedgerRepository profitLedgerRepository;
    @Mock private LocationStockRepository locationStockRepository;

    private SaleService saleService;
    private OrderService orderService;

    private final Map<Long, Product> products = new HashMap<>();
    private final List<Sale> savedSales = new ArrayList<>();
    private final List<Order> savedOrders = new ArrayList<>();
    private final AtomicLong saleIds = new AtomicLong(100);
    private final AtomicLong itemIds = new AtomicLong(1000);
    private final AtomicLong orderIds = new AtomicLong(900);

    @BeforeEach
    void setUp() {
        LedgerPostingService ledgerPostingService = new LedgerPostingService();
        ReflectionTestUtils.setField(ledgerPostingService, "inventoryLedgerRepository", inventoryLedgerRepository);
        ReflectionTestUtils.setField(ledgerPostingService, "profitLedgerRepository", profitLedgerRepository);
        ReflectionTestUtils.setField(ledgerPostingService, "productRepository", productRepository);
        ReflectionTestUtils.setField(ledgerPostingService, "locationStockRepository", locationStockRepository);
        ReflectionTestUtils.setField(ledgerPostingService, "periodGuard", periodGuard);
        ShopTimeTestSupport.withClock(ledgerPostingService);
        lenient().when(locationStockRepository.sumNonShopQuantityByProduct(anyLong())).thenReturn(0L);

        saleService = new SaleService();
        ReflectionTestUtils.setField(saleService, "saleRepository", saleRepository);
        ReflectionTestUtils.setField(saleService, "productRepository", productRepository);
        ReflectionTestUtils.setField(saleService, "customerRepository", customerRepository);
        ReflectionTestUtils.setField(saleService, "stockMovementRepository", stockMovementRepository);
        ReflectionTestUtils.setField(saleService, "auditLogService", auditLogService);
        ReflectionTestUtils.setField(saleService, "userRepository", userRepository);
        ReflectionTestUtils.setField(saleService, "saleItemRepository", saleItemRepository);
        ReflectionTestUtils.setField(saleService, "saleReturnService", saleReturnService);
        ReflectionTestUtils.setField(saleService, "cashShiftRepository", cashShiftRepository);
        ReflectionTestUtils.setField(saleService, "shopInfoRepository", shopInfoRepository);
        ReflectionTestUtils.setField(saleService, "sequenceService", sequenceService);
        ReflectionTestUtils.setField(saleService, "promotionService", promotionService);
        ReflectionTestUtils.setField(saleService, "paymentValidationService", paymentValidationService);
        ReflectionTestUtils.setField(saleService, "cashDrawerSessionService", cashDrawerSessionService);
        ReflectionTestUtils.setField(saleService, "salePaymentRepository", salePaymentRepository);
        ReflectionTestUtils.setField(saleService, "saleReturnRepository", saleReturnRepository);
        ReflectionTestUtils.setField(saleService, "periodGuard", periodGuard);
        ReflectionTestUtils.setField(saleService, "systemSettingRepository", systemSettingRepository);
        ReflectionTestUtils.setField(saleService, "ledgerPostingService", ledgerPostingService);
        ShopTimeTestSupport.withClock(saleService);

        orderService = new OrderService(orderRepository, productRepository, customerRepository,
                userRepository, shopInfoRepository, sequenceService, saleService, auditLogService);
        ReflectionTestUtils.setField(orderService, "periodGuard", periodGuard);
        ReflectionTestUtils.setField(orderService, "promotionService", promotionService);
        ReflectionTestUtils.setField(orderService, "systemSettingRepository", systemSettingRepository);
        ShopTimeTestSupport.withClock(orderService);

        ShopInfo shopInfo = new ShopInfo();
        shopInfo.setTaxPercentage(TAX_RATE);
        shopInfo.setDiscountEnabled(false);
        shopInfo.setDiscountType(ShopInfo.DiscountType.AMOUNT);
        shopInfo.setDiscountValue(BigDecimal.ZERO);
        lenient().when(shopInfoRepository.findTopByOrderByIdAsc()).thenReturn(Optional.of(shopInfo));

        lenient().when(sequenceService.nextInvoiceNumber()).thenReturn("INV-O6-0001");
        lenient().when(sequenceService.nextOrderNumber()).thenReturn("ORD-O6-0001");

        CashShift shift = new CashShift();
        shift.setId(1L);
        lenient().when(paymentValidationService.requireOpenShift(anyLong())).thenReturn(shift);
        lenient().when(paymentValidationService.findOpenShift(anyLong())).thenReturn(shift);
        lenient().when(paymentValidationService.parseMethod(anyString())).thenReturn(PaymentMethod.CASH);
        lenient().when(paymentValidationService.resolvePayments(any(), any(), any())).thenAnswer(inv -> {
            SalePaymentRequest line = new SalePaymentRequest();
            line.setMethod("CASH");
            line.setAmount(inv.getArgument(1));
            List<SalePaymentRequest> lines = new ArrayList<>();
            lines.add(line);
            return lines;
        });
        lenient().when(paymentValidationService.saveSalePayments(any(), any(), any()))
                .thenReturn(new ArrayList<>());

        // Every unit of the fixture sells at 80 against a 100 list price under one
        // named promotion, which is what both paths must remember on the sale.
        lenient().when(promotionService.getActivePromotions(any())).thenReturn(new ArrayList<>());
        lenient().when(promotionService.calculateBestPrice(any(Product.class), anyInt(), any(), any(), any()))
                .thenAnswer(inv -> {
                    Product p = inv.getArgument(0);
                    return new PricingQuote(p.getUnitPrice(), new BigDecimal("80.00"),
                            p.getUnitPrice().subtract(new BigDecimal("80.00"))
                                    .multiply(BigDecimal.valueOf((Integer) inv.getArgument(1))),
                            PROMO_ID, PROMO_NAME, null, null, "PROMOTION");
                });

        lenient().when(productRepository.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(products.get(inv.getArgument(0))));
        lenient().when(productRepository.findByIdForUpdate(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(products.get(inv.getArgument(0))));
        lenient().when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        lenient().when(saleRepository.save(any(Sale.class))).thenAnswer(inv -> {
            Sale sale = inv.getArgument(0);
            if (sale.getId() == null) {
                sale.setId(saleIds.incrementAndGet());
                for (SaleItem item : sale.getItems()) {
                    if (item.getId() == null) {
                        item.setId(itemIds.incrementAndGet());
                    }
                }
            }
            savedSales.add(sale);
            return sale;
        });
        lenient().when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order order = inv.getArgument(0);
            if (order.getId() == null) {
                order.setId(orderIds.incrementAndGet());
                for (OrderItem item : order.getItems()) {
                    if (item.getId() == null) {
                        item.setId(orderIds.incrementAndGet() * 10);
                    }
                }
            }
            savedOrders.add(order);
            return order;
        });
        lenient().when(stockMovementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(salePaymentRepository.findBySaleIdOrderByPaidAtAsc(anyLong()))
                .thenReturn(new ArrayList<>());
        lenient().when(inventoryLedgerRepository.findBySourceTypeAndSourceIdAndEventTypeAndProductIdAndLineKey(
                any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(inventoryLedgerRepository.existsByProductId(any())).thenReturn(true);
        lenient().when(inventoryLedgerRepository.save(any(InventoryLedger.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(profitLedgerRepository.save(any(ProfitLedger.class))).thenAnswer(inv -> inv.getArgument(0));

        User cashier = new User();
        cashier.setId(7L);
        cashier.setUsername("cashier");
        lenient().when(userRepository.findById(anyLong())).thenReturn(Optional.of(cashier));
    }

    private Product product(long id, String unitPrice) {
        Product product = new Product();
        product.setId(id);
        product.setName("Product " + id);
        product.setUnitPrice(new BigDecimal(unitPrice));
        product.setCostPrice(new BigDecimal("30.00"));
        product.setStockQuantity(100);
        product.setReservedQuantity(0);
        product.setIsActive(true);
        products.put(id, product);
        return product;
    }

    private SaleCreateRequest saleRequest(long productId, int quantity) {
        SaleCreateRequest request = new SaleCreateRequest();
        SaleCreateRequest.SaleItemRequest item = new SaleCreateRequest.SaleItemRequest();
        item.setProductId(productId);
        item.setQuantity(quantity);
        List<SaleCreateRequest.SaleItemRequest> items = new ArrayList<>();
        items.add(item);
        request.setItems(items);
        return request;
    }

    private OrderCreateRequest orderRequest(long productId, int quantity) {
        OrderCreateRequest request = new OrderCreateRequest();
        OrderCreateRequest.OrderItemRequest item = new OrderCreateRequest.OrderItemRequest();
        item.setProductId(productId);
        item.setQuantity(quantity);
        List<OrderCreateRequest.OrderItemRequest> items = new ArrayList<>();
        items.add(item);
        request.setItems(items);
        return request;
    }

    private Sale directSale(long productId, int quantity) {
        int before = savedSales.size();
        saleService.createSale(saleRequest(productId, quantity), 7L);
        return savedSales.get(before);
    }

    private Sale convertedOrderSale(long productId, int quantity) {
        orderService.createOrder(orderRequest(productId, quantity), 7L);
        Order order = savedOrders.get(savedOrders.size() - 1);
        saleService.createSaleFromOrder(order, "CASH", order.getTotalAmount(), null, 7L);
        return savedSales.get(savedSales.size() - 1);
    }

    @Test
    void convertedSaleCarriesTheSamePromotionFieldsAsTheDirectSale() {
        product(1L, "100.00");
        Sale direct = directSale(1L, 5);

        Sale converted = convertedOrderSale(1L, 5);

        assertMoney(direct.getSubtotal(), converted.getSubtotal());
        assertMoney(direct.getPromotionDiscount(), converted.getPromotionDiscount());
        assertMoney(direct.getPriceListDiscount(), converted.getPriceListDiscount());
        assertMoney(direct.getTaxAmount(), converted.getTaxAmount());
        assertMoney(direct.getTotalAmount(), converted.getTotalAmount());

        SaleItem directItem = direct.getItems().get(0);
        SaleItem convertedItem = converted.getItems().get(0);
        assertMoney(directItem.getOriginalUnitPrice(), convertedItem.getOriginalUnitPrice());
        assertMoney(directItem.getLineDiscount(), convertedItem.getLineDiscount());
        assertEquals(directItem.getPromotionId(), convertedItem.getPromotionId());
        assertEquals(directItem.getPromotionName(), convertedItem.getPromotionName());
    }

    @Test
    void thePromotionDiscountIsReportedRatherThanLeftAtZero() {
        product(1L, "100.00");
        Sale direct = directSale(1L, 5);

        assertMoney("100.00", direct.getPromotionDiscount());
        assertMoney("400.00", direct.getSubtotal());

        Sale converted = convertedOrderSale(1L, 5);

        assertMoney("100.00", converted.getPromotionDiscount());
        assertMoney("400.00", converted.getSubtotal());
        assertEquals(PROMO_ID, converted.getItems().get(0).getPromotionId().longValue());
        assertEquals(PROMO_NAME, converted.getItems().get(0).getPromotionName());
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertMoney(new BigDecimal(expected), actual);
    }

    private static void assertMoney(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual), "expected " + expected + " but was " + actual);
    }
}
