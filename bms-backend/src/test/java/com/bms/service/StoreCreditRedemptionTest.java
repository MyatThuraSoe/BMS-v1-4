package com.bms.service;

import com.bms.dto.request.ArPaymentRequest;
import com.bms.dto.request.SaleCreateRequest;
import com.bms.dto.request.SalePaymentRequest;
import com.bms.entity.ArPayment;
import com.bms.entity.CashDrawerSession;
import com.bms.entity.CashShift;
import com.bms.entity.Customer;
import com.bms.entity.DrawerSessionStatus;
import com.bms.entity.PaymentMethod;
import com.bms.entity.PaymentStatus;
import com.bms.entity.Product;
import com.bms.entity.Sale;
import com.bms.entity.SalePayment;
import com.bms.entity.ShiftType;
import com.bms.entity.ShopInfo;
import com.bms.entity.User;
import com.bms.exception.BusinessException;
import com.bms.repository.ArPaymentRepository;
import com.bms.repository.CashShiftRepository;
import com.bms.repository.CustomerRepository;
import com.bms.repository.ProductRepository;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Audit item 4 (option a): STORE_CREDIT is a liability the shop already owes,
 * so it must be redeemable at the till and through AR settlement, capped by the
 * customer's stored credit under a write lock.
 *
 * <p>The real PaymentValidationService is wired into both services on purpose:
 * today {@code verifyForSale} rejects a STORE_CREDIT tender and
 * {@code parseSettlementMethod} rejects it for AR, so these tests go red on
 * those production gates rather than on a stub.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StoreCreditRedemptionTest {

    private static final long CUSTOMER_ID = 7L;
    private static final long CASHIER_ID = 9L;
    private static final long COLLECTOR_ID = 5L;
    private static final long INVOICE_ID = 100L;

    @Mock private SaleRepository saleRepository;
    @Mock private ProductRepository productRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private StockMovementRepository stockMovementRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private SaleReturnService saleReturnService;
    @Mock private SalePaymentRepository salePaymentRepository;
    @Mock private SaleReturnRepository saleReturnRepository;
    @Mock private CashShiftRepository cashShiftRepository;
    @Mock private CashDrawerSessionService cashDrawerSessionService;
    @Mock private ShopInfoRepository shopInfoRepository;
    @Mock private SystemSettingRepository systemSettingRepository;
    @Mock private PromotionService promotionService;
    @Mock private SequenceService sequenceService;
    @Mock private PeriodGuard periodGuard;
    @Mock private LedgerPostingService ledgerPostingService;
    @Mock private ManagerApprovalService managerApprovalService;
    @Mock private ArPaymentRepository arPaymentRepository;
    @Mock private UserRepository userRepository;

    private PaymentValidationService validationService;
    private SaleService saleService;
    private ArService arService;

    private Customer customer;
    private Product product;

    @BeforeEach
    void setUp() {
        // The gate under test is real; only its collaborators are mocked.
        validationService = new PaymentValidationService();
        ReflectionTestUtils.setField(validationService, "shopTimeService", new ShopTimeService(null, "UTC"));
        ReflectionTestUtils.setField(validationService, "cashShiftRepository", cashShiftRepository);
        ReflectionTestUtils.setField(validationService, "userRepository", userRepository);
        ReflectionTestUtils.setField(validationService, "salePaymentRepository", salePaymentRepository);
        ReflectionTestUtils.setField(validationService, "cashDrawerSessionService", cashDrawerSessionService);

        saleService = new SaleService();
        ReflectionTestUtils.setField(saleService, "saleRepository", saleRepository);
        ReflectionTestUtils.setField(saleService, "productRepository", productRepository);
        ReflectionTestUtils.setField(saleService, "customerRepository", customerRepository);
        ReflectionTestUtils.setField(saleService, "stockMovementRepository", stockMovementRepository);
        ReflectionTestUtils.setField(saleService, "auditLogService", auditLogService);
        ReflectionTestUtils.setField(saleService, "userRepository", userRepository);
        ReflectionTestUtils.setField(saleService, "saleReturnService", saleReturnService);
        ReflectionTestUtils.setField(saleService, "salePaymentRepository", salePaymentRepository);
        ReflectionTestUtils.setField(saleService, "saleReturnRepository", saleReturnRepository);
        ReflectionTestUtils.setField(saleService, "cashShiftRepository", cashShiftRepository);
        ReflectionTestUtils.setField(saleService, "cashDrawerSessionService", cashDrawerSessionService);
        ReflectionTestUtils.setField(saleService, "shopInfoRepository", shopInfoRepository);
        ReflectionTestUtils.setField(saleService, "systemSettingRepository", systemSettingRepository);
        ReflectionTestUtils.setField(saleService, "promotionService", promotionService);
        ReflectionTestUtils.setField(saleService, "sequenceService", sequenceService);
        ReflectionTestUtils.setField(saleService, "periodGuard", periodGuard);
        ReflectionTestUtils.setField(saleService, "ledgerPostingService", ledgerPostingService);
        ReflectionTestUtils.setField(saleService, "managerApprovalService", managerApprovalService);
        ReflectionTestUtils.setField(saleService, "paymentValidationService", validationService);
        ShopTimeTestSupport.withClock(saleService);

        arService = new ArService();
        ReflectionTestUtils.setField(arService, "saleRepository", saleRepository);
        ReflectionTestUtils.setField(arService, "customerRepository", customerRepository);
        ReflectionTestUtils.setField(arService, "arPaymentRepository", arPaymentRepository);
        ReflectionTestUtils.setField(arService, "salePaymentRepository", salePaymentRepository);
        ReflectionTestUtils.setField(arService, "userRepository", userRepository);
        ReflectionTestUtils.setField(arService, "auditLogService", auditLogService);
        ReflectionTestUtils.setField(arService, "paymentValidationService", validationService);
        ReflectionTestUtils.setField(arService, "cashDrawerSessionService", cashDrawerSessionService);
        ReflectionTestUtils.setField(arService, "periodGuard", periodGuard);
        ShopTimeTestSupport.withClock(arService);

        customer = new Customer();
        customer.setId(CUSTOMER_ID);
        customer.setCustomerCode("CUST-7");
        customer.setFirstName("A");
        customer.setLastName("Buyer");
        customer.setIsActive(true);
        customer.setCurrentBalance(BigDecimal.ZERO);
        customer.setCreditBalance(new BigDecimal("50.00"));
        customer.setCreditLimit(new BigDecimal("100.00"));

        product = new Product();
        product.setId(1L);
        product.setName("Widget");
        product.setUnitPrice(new BigDecimal("30.00"));
        product.setCostPrice(new BigDecimal("10.00"));
        product.setStockQuantity(10);

        CashShift shift = new CashShift();
        shift.setId(1L);
        shift.setCashierId(CASHIER_ID);
        shift.setShiftType(ShiftType.CASH_DRAWER);
        CashDrawerSession session = new CashDrawerSession();
        session.setId(100L);
        session.setStatus(DrawerSessionStatus.OPEN);
        shift.setDrawerSession(session);
        lenient().when(cashShiftRepository.findByCashierIdAndStatus(CASHIER_ID, "OPEN"))
                .thenReturn(Optional.of(shift));
        lenient().when(cashShiftRepository.findByCashierIdAndStatus(COLLECTOR_ID, "OPEN"))
                .thenReturn(Optional.of(shift));

        lenient().when(userRepository.findById(anyLong())).thenAnswer(inv -> {
            User user = new User();
            user.setId(inv.getArgument(0, Long.class));
            user.setFirstName("Test");
            user.setLastName("Clerk");
            return Optional.of(user);
        });
        lenient().when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));
        lenient().when(customerRepository.findByIdForUpdate(CUSTOMER_ID)).thenReturn(Optional.of(customer));
        lenient().when(customerRepository.save(any(Customer.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(productRepository.findByIdForUpdate(anyLong())).thenReturn(Optional.of(product));
        lenient().when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(sequenceService.nextInvoiceNumber()).thenReturn("INV-SC-1");
        lenient().when(sequenceService.nextCreditInvoiceNumber()).thenReturn("CR-SC-1");
        lenient().when(shopInfoRepository.findTopByOrderByIdAsc()).thenReturn(Optional.of(shopInfo()));
        lenient().when(promotionService.getActivePromotions(any())).thenReturn(new ArrayList<>());
        lenient().when(promotionService.calculateBestPrice(any(Product.class), anyInt(), any(), any(), any()))
                .thenAnswer(inv -> {
                    Product p = inv.getArgument(0);
                    return new PricingQuote(p.getUnitPrice(), p.getUnitPrice(), BigDecimal.ZERO,
                            null, null, null, null, "BASE");
                });
        lenient().when(stockMovementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(saleRepository.save(any(Sale.class))).thenAnswer(inv -> {
            Sale saved = inv.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(500L);
            }
            return saved;
        });
        lenient().when(salePaymentRepository.save(any(SalePayment.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(salePaymentRepository.findBySaleIdOrderByPaidAtAsc(anyLong()))
                .thenReturn(new ArrayList<>());
        lenient().when(saleReturnService.findBySaleId(anyLong())).thenReturn(new ArrayList<>());
        lenient().when(arPaymentRepository.save(any(ArPayment.class))).thenAnswer(inv -> {
            ArPayment saved = inv.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(11L);
            }
            return saved;
        });
    }

    private static ShopInfo shopInfo() {
        ShopInfo info = new ShopInfo();
        info.setTaxPercentage(BigDecimal.ZERO);
        info.setDiscountEnabled(false);
        info.setDiscountType(ShopInfo.DiscountType.AMOUNT);
        info.setDiscountValue(BigDecimal.ZERO);
        return info;
    }

    private SaleCreateRequest cashSaleWithStoreCredit(String amount) {
        SaleCreateRequest request = new SaleCreateRequest();
        SaleCreateRequest.SaleItemRequest item = new SaleCreateRequest.SaleItemRequest();
        item.setProductId(product.getId());
        item.setQuantity(1);
        request.setItems(List.of(item));
        request.setSaleType(Sale.SaleType.CASH.name());
        request.setCustomerId(CUSTOMER_ID);

        SalePaymentRequest line = new SalePaymentRequest();
        line.setMethod(PaymentMethod.STORE_CREDIT.name());
        line.setAmount(new BigDecimal(amount));
        request.setPayments(List.of(line));
        return request;
    }

    private Sale creditInvoice() {
        Sale invoice = new Sale();
        invoice.setId(INVOICE_ID);
        invoice.setInvoiceNumber("INV-AR-1");
        invoice.setSaleType(Sale.SaleType.CREDIT);
        invoice.setPaymentStatus(Sale.PaymentStatus.UNPAID);
        invoice.setTotalAmount(new BigDecimal("100.00"));
        // The customer owes this invoice; the store-credit settlement reduces it.
        customer.setCurrentBalance(new BigDecimal("100.00"));
        invoice.setAmountPaid(BigDecimal.ZERO);
        invoice.setAmountReturned(BigDecimal.ZERO);
        invoice.setIsActive(true);
        invoice.setIsVoided(false);
        invoice.setCustomer(customer);
        return invoice;
    }

    @Test
    void storeCreditTenderPaysACashSaleAndDebitsTheLockedCustomer() {
        saleService.createSale(cashSaleWithStoreCredit("30.00"), CASHIER_ID);

        assertEquals(0, new BigDecimal("20.00").compareTo(customer.getCreditBalance()),
                "30.00 of the customer's 50.00 store credit was spent");
        verify(customerRepository, atLeastOnce()).findByIdForUpdate(CUSTOMER_ID);
        verify(customerRepository, atLeastOnce()).save(customer);
        verify(saleRepository, atLeastOnce()).save(any(Sale.class));
    }

    @Test
    void storeCreditLineAboveTheBalanceIsRejectedWithoutSavingASale() {
        product.setUnitPrice(new BigDecimal("60.00"));

        BusinessException failure = assertThrows(BusinessException.class,
                () -> saleService.createSale(cashSaleWithStoreCredit("60.00"), CASHIER_ID));

        String message = failure.getMessage() == null ? "" : failure.getMessage().toLowerCase();
        assertFalse(message.contains("cannot be used as a payment method"),
                "store credit must be an accepted tender, so 60.00 should fail only for "
                        + "exceeding the 50.00 balance, but got: " + failure.getMessage());
        assertTrue(message.contains("credit"), failure.getMessage());
        assertEquals(0, new BigDecimal("50.00").compareTo(customer.getCreditBalance()),
                "a rejected redemption must not spend the customer's credit");
        verify(saleRepository, never()).save(any(Sale.class));
    }

    @Test
    void zeroTotalCashSaleWithStoreCreditLineIsRejectedWithoutDebitingCustomer() {
        product.setUnitPrice(BigDecimal.ZERO);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> saleService.createSale(cashSaleWithStoreCredit("30.00"), CASHIER_ID));

        String message = failure.getMessage() == null ? "" : failure.getMessage().toLowerCase();
        assertTrue(message.contains("zero"), failure.getMessage());
        assertEquals(0, new BigDecimal("50.00").compareTo(customer.getCreditBalance()),
                "a zero-total sale must not spend store credit");
        verify(saleRepository, never()).save(any(Sale.class));
    }

    @Test
    void zeroTotalCreditSaleWithStoreCreditLineIsRejectedWithoutNpe() {
        product.setUnitPrice(BigDecimal.ZERO);
        SaleCreateRequest request = cashSaleWithStoreCredit("30.00");
        request.setSaleType(Sale.SaleType.CREDIT.name());
        request.setDueDate(LocalDate.now().plusDays(1));

        BusinessException failure = assertThrows(BusinessException.class,
                () -> saleService.createSale(request, CASHIER_ID));

        String message = failure.getMessage() == null ? "" : failure.getMessage().toLowerCase();
        assertTrue(message.contains("zero"), failure.getMessage());
        assertEquals(0, new BigDecimal("50.00").compareTo(customer.getCreditBalance()));
        verify(saleRepository, never()).save(any(Sale.class));
    }

    @Test
    void partialArPaymentLeavesTheCreditTenderLinePending() {
        Sale sale = creditInvoice();
        SalePayment creditLine = creditTenderLine(sale);
        when(saleRepository.findByIdForUpdate(INVOICE_ID)).thenReturn(Optional.of(sale));
        when(salePaymentRepository.findBySaleIdOrderByPaidAtAsc(INVOICE_ID))
                .thenReturn(new ArrayList<>(List.of(creditLine)));

        ArPaymentRequest request = new ArPaymentRequest();
        request.setAmount(new BigDecimal("30.00"));
        request.setPaymentMethod(PaymentMethod.CASH.name());

        arService.recordPayment(INVOICE_ID, request, COLLECTOR_ID);

        assertEquals(PaymentStatus.PENDING, creditLine.getPaymentStatus(),
                "a 30.00 payment against a 100.00 invoice does not settle its debt line");
    }

    @Test
    void fullArPaymentCompletesTheCreditTenderLine() {
        Sale sale = creditInvoice();
        SalePayment creditLine = creditTenderLine(sale);
        when(saleRepository.findByIdForUpdate(INVOICE_ID)).thenReturn(Optional.of(sale));
        when(salePaymentRepository.findBySaleIdOrderByPaidAtAsc(INVOICE_ID))
                .thenReturn(new ArrayList<>(List.of(creditLine)));

        ArPaymentRequest request = new ArPaymentRequest();
        request.setAmount(new BigDecimal("100.00"));
        request.setPaymentMethod(PaymentMethod.CASH.name());

        arService.recordPayment(INVOICE_ID, request, COLLECTOR_ID);

        assertEquals(PaymentStatus.COMPLETED, creditLine.getPaymentStatus());
    }

    private static SalePayment creditTenderLine(Sale sale) {
        SalePayment payment = new SalePayment();
        payment.setId(900L);
        payment.setSale(sale);
        payment.setPaymentMethod(PaymentMethod.CREDIT);
        payment.setPaymentStatus(PaymentStatus.PENDING);
        payment.setAmount(new BigDecimal("100.00"));
        return payment;
    }

    @Test
    void arPaymentByStoreCreditReducesBothCustomerBalances() {
        when(saleRepository.findByIdForUpdate(INVOICE_ID)).thenReturn(Optional.of(creditInvoice()));

        ArPaymentRequest request = new ArPaymentRequest();
        request.setAmount(new BigDecimal("30.00"));
        request.setPaymentMethod(PaymentMethod.STORE_CREDIT.name());

        arService.recordPayment(INVOICE_ID, request, COLLECTOR_ID);

        assertEquals(0, new BigDecimal("70.00").compareTo(customer.getCurrentBalance()),
                "the invoice debt falls by the 30.00 settled with credit");
        assertEquals(0, new BigDecimal("20.00").compareTo(customer.getCreditBalance()),
                "the store credit handed back to the shop is spent");
        verify(customerRepository, atLeastOnce()).findByIdForUpdate(CUSTOMER_ID);

        ArgumentCaptor<ArPayment> captured = ArgumentCaptor.forClass(ArPayment.class);
        verify(arPaymentRepository, atLeastOnce()).save(captured.capture());
        assertEquals(PaymentMethod.STORE_CREDIT, captured.getAllValues().get(0).getPaymentMethod());
    }
}
