package com.bms.service;

import com.bms.entity.Order;
import com.bms.entity.OrderItem;
import com.bms.entity.Product;
import com.bms.entity.SystemSetting;
import com.bms.repository.CustomerRepository;
import com.bms.repository.OrderRepository;
import com.bms.repository.ProductRepository;
import com.bms.repository.ShopInfoRepository;
import com.bms.repository.SystemSettingRepository;
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
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * O8: a pending order may not hold stock forever. The hourly sweep cancels the
 * orders the shop has outlived its reservation window for, through the normal
 * cancel path so the stock release and the audit trail are the ones a cashier
 * would get, and does nothing at all while the window is zero.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderReservationExpiryTest {

    @Mock private OrderRepository orderRepository;
    @Mock private ProductRepository productRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private UserRepository userRepository;
    @Mock private ShopInfoRepository shopInfoRepository;
    @Mock private SequenceService sequenceService;
    @Mock private SaleService saleService;
    @Mock private AuditLogService auditLogService;
    @Mock private SystemSettingRepository systemSettingRepository;
    @Mock private PeriodGuard periodGuard;
    @Mock private PromotionService promotionService;

    private OrderService orderService;
    private final List<Order> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, productRepository, customerRepository,
                userRepository, shopInfoRepository, sequenceService, saleService, auditLogService);
        ReflectionTestUtils.setField(orderService, "periodGuard", periodGuard);
        ReflectionTestUtils.setField(orderService, "promotionService", promotionService);
        ReflectionTestUtils.setField(orderService, "systemSettingRepository", systemSettingRepository);
        ShopTimeTestSupport.withClock(orderService);

        lenient().when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order order = inv.getArgument(0);
            saved.add(order);
            return order;
        });
        lenient().when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(userRepository.findById(anyLong())).thenReturn(Optional.empty());
    }

    private void reservationWindow(String hours) {
        SystemSetting setting = new SystemSetting();
        setting.setSettingKey("shop.order.reservationHours");
        setting.setSettingValue(hours);
        lenient().when(systemSettingRepository.findBySettingKey("shop.order.reservationHours"))
                .thenReturn(Optional.of(setting));
    }

    /** A pending order for {@code quantity} units, holding them in reservedQuantity. */
    private Order pendingOrder(long orderId, int quantity) {
        Product product = new Product();
        product.setId(1L);
        product.setName("Reserved widget");
        product.setUnitPrice(new BigDecimal("50.00"));
        product.setCostPrice(new BigDecimal("20.00"));
        product.setStockQuantity(50);
        product.setReservedQuantity(quantity);
        lenient().when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(product));

        Order order = new Order();
        order.setId(orderId);
        order.setOrderNumber("ORD-" + orderId);
        order.setCashierId(9L);
        order.setStatus(Order.OrderStatus.PENDING);
        order.setSubtotal(new BigDecimal("50.00").multiply(BigDecimal.valueOf(quantity)));
        order.setTotalAmount(order.getSubtotal());
        order.setTaxAmount(BigDecimal.ZERO);
        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProduct(product);
        item.setQuantity(quantity);
        item.setUnitPrice(new BigDecimal("50.00"));
        item.setTotalPrice(order.getSubtotal());
        List<OrderItem> items = new ArrayList<>();
        items.add(item);
        order.setItems(items);
        lenient().when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        lenient().when(orderRepository.findPendingOrdersOlderThan(any(LocalDateTime.class)))
                .thenReturn(List.of(order));
        return order;
    }

    @Test
    void aZeroWindowLeavesReservationsAlone() {
        reservationWindow("0");

        assertEquals(0, orderService.expireStaleReservations());

        verify(orderRepository, never()).findPendingOrdersOlderThan(any());
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void anUnreadableWindowIsTreatedAsOff() {
        reservationWindow("abc");

        assertEquals(0, orderService.expireStaleReservations());

        verify(orderRepository, never()).findPendingOrdersOlderThan(any());
    }

    @Test
    void anExpiredReservationIsCancelledAndTheStockReleased() {
        reservationWindow("48");
        Order order = pendingOrder(11L, 6);

        assertEquals(1, orderService.expireStaleReservations());

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(orderRepository).findPendingOrdersOlderThan(cutoff.capture());
        assertEquals(48, Duration.between(cutoff.getValue(), LocalDateTime.now()).toHours(),
                "the sweep must ask for orders older than the configured window");

        assertEquals(Order.OrderStatus.CANCELLED, order.getStatus());
        assertEquals("Reservation expired", order.getCancelReason());
        assertEquals(0L, order.getCancelledBy(),
                "the sweep records itself as the cancelling actor");
        assertEquals(0, order.getItems().get(0).getProduct().getReservedQuantity(),
                "cancelling must give the reserved units back");

        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).logAction(eq(0L), eq("ORDER_CANCEL"), description.capture(),
                eq("Order"), eq(11L), any(), any());
        assertEquals("Order cancelled: ORD-11. Reason: Reservation expired", description.getValue());
    }
}
