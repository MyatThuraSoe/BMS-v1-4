package com.bms.service;

import com.bms.dto.order.OrderCreateRequest;
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
import com.bms.exception.BusinessException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import org.mockito.InOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.inOrder;

/**
 * F22: converting one order must not take the units another pending order is
 * still holding. The converting order gets exactly its own reservation back,
 * the rest of the pool stays spoken for, and a product on several lines is
 * measured by its total demand rather than its largest line.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderConversionReservationTest {

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
    private final List<Order> savedOrders = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        LedgerPostingService ledgerPostingService = new LedgerPostingService();
        ReflectionTestUtils.setField(ledgerPostingService, "inventoryLedgerRepository", inventoryLedgerRepository);
        ReflectionTestUtils.setField(ledgerPostingService, "profitLedgerRepository", profitLedgerRepository);
        ReflectionTestUtils.setField(ledgerPostingService, "productRepository", productRepository);
        ReflectionTestUtils.setField(ledgerPostingService, "locationStockRepository", locationStockRepository);
        ReflectionTestUtils.setField(ledgerPostingService, "periodGuard", periodGuard);
        ShopTimeTestSupport.withClock(ledgerPostingService);

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
        shopInfo.setTaxPercentage(BigDecimal.ZERO);
        shopInfo.setDiscountEnabled(false);
        shopInfo.setDiscountType(ShopInfo.DiscountType.AMOUNT);
        shopInfo.setDiscountValue(BigDecimal.ZERO);
        lenient().when(shopInfoRepository.findTopByOrderByIdAsc()).thenReturn(Optional.of(shopInfo));

        lenient().when(sequenceService.nextInvoiceNumber()).thenReturn("INV-F22-0001");
        lenient().when(sequenceService.nextOrderNumber()).thenAnswer(inv -> "ORD-F22-" + ids.incrementAndGet());
        lenient().when(promotionService.getActivePromotions(any())).thenReturn(new ArrayList<>());
        lenient().when(promotionService.calculateBestPrice(any(Product.class), anyInt(), any(), any(), any()))
                .thenAnswer(inv -> {
                    Product p = inv.getArgument(0);
                    return new PricingQuote(p.getUnitPrice(), p.getUnitPrice(), BigDecimal.ZERO,
                            null, null, null, null, "BASE");
                });

        CashShift shift = new CashShift();
        shift.setId(1L);
        lenient().when(paymentValidationService.requireOpenShift(anyLong())).thenReturn(shift);
        lenient().when(paymentValidationService.parseMethod(anyString())).thenReturn(PaymentMethod.CASH);
        lenient().when(paymentValidationService.saveSalePayments(any(), any(), any()))
                .thenReturn(new ArrayList<>());

        lenient().when(productRepository.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(products.get(inv.getArgument(0))));
        lenient().when(productRepository.findByIdForUpdate(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(products.get(inv.getArgument(0))));
        lenient().when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(locationStockRepository.sumNonShopQuantityByProduct(anyLong())).thenReturn(0L);
        lenient().when(inventoryLedgerRepository.findBySourceTypeAndSourceIdAndEventTypeAndProductIdAndLineKey(
                any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(inventoryLedgerRepository.existsByProductId(any())).thenReturn(true);
        lenient().when(inventoryLedgerRepository.save(any(InventoryLedger.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(profitLedgerRepository.save(any(ProfitLedger.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(stockMovementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(salePaymentRepository.findBySaleIdOrderByPaidAtAsc(anyLong()))
                .thenReturn(new ArrayList<>());

        lenient().when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order order = inv.getArgument(0);
            if (order.getId() == null) {
                order.setId(ids.incrementAndGet());
                for (OrderItem item : order.getItems()) {
                    if (item.getId() == null) {
                        item.setId(ids.incrementAndGet());
                    }
                }
            }
            savedOrders.add(order);
            return order;
        });
        lenient().when(saleRepository.save(any(Sale.class))).thenAnswer(inv -> {
            Sale sale = inv.getArgument(0);
            if (sale.getId() == null) {
                sale.setId(ids.incrementAndGet());
                for (SaleItem item : sale.getItems()) {
                    if (item.getId() == null) {
                        item.setId(ids.incrementAndGet());
                    }
                }
            }
            return sale;
        });

        User cashier = new User();
        cashier.setId(7L);
        cashier.setUsername("cashier");
        lenient().when(userRepository.findById(anyLong())).thenReturn(Optional.of(cashier));
    }

    @Test
    void convertingTheFirstOrderLeavesTheSecondOneConvertible() {
        Product product = product(1L, 10);
        Order first = order(1L, 4);
        Order second = order(1L, 6);
        assertEquals(10, product.getReservedQuantity());

        convert(first);

        assertEquals(6, product.getStockQuantity(), "only the four converted units leave the shelf");
        assertEquals(6, product.getReservedQuantity(), "the other order keeps its six units");

        convert(second);

        assertEquals(0, product.getStockQuantity());
        assertEquals(0, product.getReservedQuantity());
    }

    @Test
    void aProductOnTwoLinesIsCheckedAgainstItsWholeDemand() {
        // A pool that already speaks for more than the shelf holds — a count
        // shortage or a manager override gets stock below reserved. Each line of
        // six units fits on its own; together they do not.
        Product product = product(1L, 10);
        Order order = order(1L, 3, 3);
        product.setReservedQuantity(11);

        BusinessException error = assertThrows(BusinessException.class, () -> convert(order));

        assertTrue(error.getMessage().contains("Requested: 6"), error.getMessage());
        assertEquals(10, product.getStockQuantity(), "the refused conversion left the shelf alone");
        assertEquals(11, product.getReservedQuantity());
    }

    @Test
    void conversionRefusesToTakeTheShelfBelowWhatOtherOrdersHold() {
        Product product = product(1L, 8);
        Order mine = order(1L, 4);
        product.setReservedQuantity(10);

        BusinessException error = assertThrows(BusinessException.class, () -> convert(mine));

        assertTrue(error.getMessage().contains("Available: 2"), error.getMessage());
        assertEquals(8, product.getStockQuantity());
        assertEquals(10, product.getReservedQuantity());
    }

    private void convert(Order order) {
        saleService.createSaleFromOrder(order, "CASH", order.getTotalAmount(), null, 7L);
    }

    private Order order(long productId, int... quantities) {
        OrderCreateRequest request = new OrderCreateRequest();
        List<OrderCreateRequest.OrderItemRequest> items = new ArrayList<>();
        for (int quantity : quantities) {
            OrderCreateRequest.OrderItemRequest item = new OrderCreateRequest.OrderItemRequest();
            item.setProductId(productId);
            item.setQuantity(quantity);
            items.add(item);
        }
        request.setItems(items);
        orderService.createOrder(request, 7L);
        return savedOrders.get(savedOrders.size() - 1);
    }

    private Product product(long id, int stock) {
        Product product = new Product();
        product.setId(id);
        product.setName("Rice 5kg");
        product.setSku("SKU-" + id);
        product.setUnitPrice(new BigDecimal("100.00"));
        product.setCostPrice(new BigDecimal("60.00"));
        product.setStockQuantity(stock);
        product.setReservedQuantity(0);
        product.setIsActive(true);
        products.put(id, product);
        return product;
    }

    // BUG-7: products are locked in ascending id order even when order items
    // are given in descending product-id order
    @Test
    void conversionLocksProductsInAscendingIdOrder() {
        Product productA = product(3L, 10);  // higher id first in request
        Product productB = product(1L, 10);  // lower id second in request

        OrderCreateRequest request = new OrderCreateRequest();
        List<OrderCreateRequest.OrderItemRequest> items = new ArrayList<>();
        OrderCreateRequest.OrderItemRequest itemA = new OrderCreateRequest.OrderItemRequest();
        itemA.setProductId(3L);
        itemA.setQuantity(1);
        items.add(itemA);
        OrderCreateRequest.OrderItemRequest itemB = new OrderCreateRequest.OrderItemRequest();
        itemB.setProductId(1L);
        itemB.setQuantity(1);
        items.add(itemB);
        request.setItems(items);
        orderService.createOrder(request, 7L);
        Order order = savedOrders.get(savedOrders.size() - 1);

        // Clear invocations from order creation, then verify the lock order
        // during conversion only.
        org.mockito.Mockito.clearInvocations(productRepository);

        saleService.createSaleFromOrder(order, "CASH", order.getTotalAmount(), null, 7L);

        InOrder inOrder = inOrder(productRepository);
        inOrder.verify(productRepository).findByIdForUpdate(1L);
        inOrder.verify(productRepository).findByIdForUpdate(3L);
    }
}
