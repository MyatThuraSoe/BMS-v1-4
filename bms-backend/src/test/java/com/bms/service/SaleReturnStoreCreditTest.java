package com.bms.service;

import com.bms.dto.request.SaleReturnRequest;
import com.bms.dto.response.ReturnableItemsResponse;
import com.bms.entity.Customer;
import com.bms.entity.InventoryLedger;
import com.bms.entity.PaymentMethod;
import com.bms.entity.Product;
import com.bms.entity.Role;
import com.bms.entity.Sale;
import com.bms.entity.SaleItem;
import com.bms.entity.SaleReturn;
import com.bms.entity.User;
import com.bms.exception.BusinessException;
import com.bms.repository.CustomerRepository;
import com.bms.repository.ProductPriceHistoryRepository;
import com.bms.repository.ProductRepository;
import com.bms.repository.SaleItemRepository;
import com.bms.repository.SalePaymentRepository;
import com.bms.repository.SaleRepository;
import com.bms.repository.SaleReturnRepository;
import com.bms.repository.StockMovementRepository;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * N4: a STORE_CREDIT refund must work on a cash or card sale, not only on a
 * credit invoice, and it must refuse when the sale has no customer to owe.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SaleReturnStoreCreditTest {

    @Mock private SaleRepository saleRepository;
    @Mock private SaleItemRepository saleItemRepository;
    @Mock private SaleReturnRepository saleReturnRepository;
    @Mock private ProductRepository productRepository;
    @Mock private StockMovementRepository stockMovementRepository;
    @Mock private UserRepository userRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private PaymentValidationService paymentValidationService;
    @Mock private CashDrawerSessionService cashDrawerSessionService;
    @Mock private SalePaymentRepository salePaymentRepository;
    @Mock private LocationService locationService;
    @Mock private ProductPriceHistoryRepository priceHistoryRepository;
    @Mock private LedgerPostingService ledgerPostingService;
    @Mock private PeriodGuard periodGuard;
    @Mock private ShopTimeService shopTimeService;

    private SaleReturnService service;
    private Sale sale;
    private Customer customer;

    @BeforeEach
    void setUp() {
        service = new SaleReturnService();
        ReflectionTestUtils.setField(service, "saleRepository", saleRepository);
        ReflectionTestUtils.setField(service, "saleItemRepository", saleItemRepository);
        ReflectionTestUtils.setField(service, "saleReturnRepository", saleReturnRepository);
        ReflectionTestUtils.setField(service, "productRepository", productRepository);
        ReflectionTestUtils.setField(service, "stockMovementRepository", stockMovementRepository);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        ReflectionTestUtils.setField(service, "customerRepository", customerRepository);
        ReflectionTestUtils.setField(service, "auditLogService", auditLogService);
        ReflectionTestUtils.setField(service, "paymentValidationService", paymentValidationService);
        ReflectionTestUtils.setField(service, "cashDrawerSessionService", cashDrawerSessionService);
        ReflectionTestUtils.setField(service, "salePaymentRepository", salePaymentRepository);
        ReflectionTestUtils.setField(service, "locationService", locationService);
        ReflectionTestUtils.setField(service, "priceHistoryRepository", priceHistoryRepository);
        ReflectionTestUtils.setField(service, "ledgerPostingService", ledgerPostingService);
        ReflectionTestUtils.setField(service, "periodGuard", periodGuard);
        ReflectionTestUtils.setField(service, "shopTimeService", shopTimeService);

        LocalDateTime now = LocalDateTime.of(2026, 11, 10, 12, 0);
        lenient().when(shopTimeService.now()).thenReturn(now);

        Role cashierRole = new Role();
        cashierRole.setName(Role.RoleName.ROLE_CASHIER);
        User cashier = new User();
        cashier.setId(5L);
        cashier.setUsername("cashier");
        cashier.setRole(cashierRole);

        Product product = new Product();
        product.setId(10L);
        product.setSku("SKU-10");
        product.setName("Widget");
        product.setUnitPrice(new BigDecimal("30.00"));
        product.setCostPrice(new BigDecimal("18.00"));
        product.setStockQuantity(4);

        sale = new Sale();
        sale.setId(100L);
        sale.setInvoiceNumber("INV-SC-1");
        sale.setCashierId(5L);
        sale.setSaleDate(now);
        sale.setSubtotal(new BigDecimal("30.00"));
        sale.setTaxAmount(BigDecimal.ZERO);
        sale.setTotalAmount(new BigDecimal("30.00"));
        sale.setAmountPaid(new BigDecimal("30.00"));
        sale.setSaleType(Sale.SaleType.CASH);
        sale.setPaymentMethod(Sale.PaymentMethod.CASH);
        sale.setPaymentStatus(Sale.PaymentStatus.PAID);
        sale.setIsActive(true);
        sale.setIsVoided(false);

        SaleItem saleItem = new SaleItem();
        saleItem.setId(1000L);
        saleItem.setSale(sale);
        saleItem.setProduct(product);
        saleItem.setQuantity(1);
        saleItem.setQuantityRefunded(0);
        saleItem.setUnitPrice(new BigDecimal("30.00"));
        saleItem.setTotalPrice(new BigDecimal("30.00"));
        saleItem.setTaxAmount(BigDecimal.ZERO);
        saleItem.setCostPriceAtSale(new BigDecimal("18.00"));
        sale.getItems().add(saleItem);

        customer = new Customer();
        customer.setId(7L);
        customer.setCustomerCode("CUST-7");
        customer.setFirstName("Alice");
        customer.setCreditBalance(BigDecimal.ZERO);
        customer.setCurrentBalance(BigDecimal.ZERO);
        sale.setCustomer(customer);

        InventoryLedger posting = new InventoryLedger();
        posting.setUnitCostAfter(new BigDecimal("18.00"));

        lenient().when(saleRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(sale));
        lenient().when(saleRepository.findById(100L)).thenReturn(Optional.of(sale));
        lenient().when(saleRepository.save(any(Sale.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(userRepository.findById(5L)).thenReturn(Optional.of(cashier));
        lenient().when(saleItemRepository.findByIdForUpdate(1000L)).thenReturn(Optional.of(saleItem));
        lenient().when(productRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(product));
        lenient().when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(stockMovementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(saleReturnRepository.save(any(SaleReturn.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(customerRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(customer));
        lenient().when(customerRepository.save(any(Customer.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(salePaymentRepository.findBySaleIdOrderByPaidAtAsc(100L)).thenReturn(List.of());
        lenient().when(paymentValidationService.findOpenShift(anyLong())).thenReturn(null);
        lenient().when(paymentValidationService.parseMethod(anyString()))
                .thenAnswer(inv -> PaymentMethod.valueOf((String) inv.getArgument(0)));
        lenient().when(ledgerPostingService.postInventory(any(Product.class), any(InventoryLedger.EventType.class),
                anyString(), any(), any(), anyInt(), any(), any(), any(), anyString(), any(), any()))
                .thenReturn(posting);
    }

    private static SaleReturnRequest storeCreditRequest() {
        SaleReturnRequest request = new SaleReturnRequest();
        request.setReason("Wrong size");
        request.setRefundMethod("STORE_CREDIT");
        SaleReturnRequest.SaleReturnItemRequest item = new SaleReturnRequest.SaleReturnItemRequest();
        item.setSaleItemId(1000L);
        item.setQuantity(1);
        request.setItems(List.of(item));
        return request;
    }

    @Test
    void storeCreditOnACashSaleOwesTheCustomerTheRefund() {
        service.createSaleReturn(100L, storeCreditRequest(), 5L);

        assertEquals(0, customer.getCreditBalance().compareTo(new BigDecimal("30.00")),
                "the refund becomes money the shop owes the customer");
        verify(customerRepository).save(customer);
        verify(cashDrawerSessionService, never()).recordFinancialMovement(
                any(), any(), any(), anyString(), anyLong(), any(), any(), anyString());
    }

    @Test
    void storeCreditWithoutACustomerIsRefusedBeforeAnythingIsSaved() {
        sale.setCustomer(null);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.createSaleReturn(100L, storeCreditRequest(), 5L));

        assertEquals("Store credit needs a customer on the sale", failure.getMessage());
        verify(saleReturnRepository, never()).save(any(SaleReturn.class));
        verify(saleRepository, never()).save(any(Sale.class));
        verify(customerRepository, never()).save(any(Customer.class));
        assertEquals(0, customer.getCreditBalance().compareTo(BigDecimal.ZERO));
    }

    @Test
    void returnableItemsSayWhetherTheSaleHasACustomer() {
        assertEquals(Boolean.TRUE, service.getReturnableItems(100L).getHasCustomer(),
                "the dialog may only offer STORE_CREDIT for a sale with a customer");

        sale.setCustomer(null);
        ReturnableItemsResponse anonymous = service.getReturnableItems(100L);
        assertEquals(Boolean.FALSE, anonymous.getHasCustomer());
        assertEquals(0, new BigDecimal("30.00").compareTo(anonymous.getEstimatedRefundDue()),
                "the rest of the snapshot is unchanged");

        // Opening the dialog is a read; a write lock here would stall the till.
        verify(saleRepository, never()).findByIdForUpdate(anyLong());
    }
}
