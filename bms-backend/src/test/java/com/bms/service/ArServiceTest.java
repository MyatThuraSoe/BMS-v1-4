package com.bms.service;

import com.bms.dto.request.ArPaymentRequest;
import com.bms.dto.response.PaymentValidationResponse;
import com.bms.dto.response.ArPaymentResponse;
import com.bms.entity.ArPayment;
import com.bms.entity.CashShift;
import com.bms.entity.Customer;
import com.bms.entity.PaymentMethod;
import com.bms.entity.Role;
import com.bms.entity.Sale;
import com.bms.entity.User;
import com.bms.exception.BusinessException;
import com.bms.repository.ArPaymentRepository;
import com.bms.repository.CustomerRepository;
import com.bms.repository.SaleRepository;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * BUG-2: AR payments must not accept CREDIT or STORE_CREDIT as settlement methods.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArServiceTest {

    @Mock private SaleRepository saleRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private ArPaymentRepository arPaymentRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private PaymentValidationService paymentValidationService;
    @Mock private CashDrawerSessionService cashDrawerSessionService;
    @Mock private PeriodGuard periodGuard;

    private ArService service;

    private Sale sale;
    private Customer customer;
    private User recordedBy;

    @BeforeEach
    void setUp() {
        service = new ArService();
        ReflectionTestUtils.setField(service, "saleRepository", saleRepository);
        ReflectionTestUtils.setField(service, "customerRepository", customerRepository);
        ReflectionTestUtils.setField(service, "arPaymentRepository", arPaymentRepository);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        ReflectionTestUtils.setField(service, "auditLogService", auditLogService);
        ReflectionTestUtils.setField(service, "paymentValidationService", paymentValidationService);
        ReflectionTestUtils.setField(service, "cashDrawerSessionService", cashDrawerSessionService);
        ReflectionTestUtils.setField(service, "periodGuard", periodGuard);

        sale = new Sale();
        sale.setId(100L);
        sale.setInvoiceNumber("INV-AR-1");
        sale.setSaleType(Sale.SaleType.CREDIT);
        sale.setPaymentStatus(Sale.PaymentStatus.UNPAID);
        sale.setTotalAmount(new BigDecimal("100.00"));
        sale.setAmountPaid(BigDecimal.ZERO);
        sale.setAmountReturned(BigDecimal.ZERO);
        sale.setIsActive(true);
        sale.setDeletedAt(null);
        sale.setIsVoided(false);

        customer = new Customer();
        customer.setId(7L);
        customer.setCustomerCode("CUST-7");
        customer.setCurrentBalance(new BigDecimal("100.00"));
        customer.setCreditBalance(BigDecimal.ZERO);
        sale.setCustomer(customer);

        recordedBy = new User();
        recordedBy.setId(5L);
        recordedBy.setUsername("clerk");

        lenient().when(saleRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(sale));
        lenient().when(userRepository.findById(5L)).thenReturn(Optional.of(recordedBy));
        lenient().when(customerRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(customer));
        lenient().when(arPaymentRepository.save(any(ArPayment.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(saleRepository.save(any(Sale.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(customerRepository.save(any(Customer.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(paymentValidationService.findOpenShift(anyLong())).thenReturn(new CashShift());
        lenient().when(paymentValidationService.validate(any(), any()))
                .thenAnswer(inv -> {
                    PaymentValidationResponse response = new PaymentValidationResponse();
                    response.setValid(true);
                    return response;
                });
        lenient().when(paymentValidationService.parseSettlementMethod("STORE_CREDIT"))
                .thenThrow(new BusinessException("PAYMENT_INVALID: STORE_CREDIT is not a valid settlement method."));
        lenient().when(paymentValidationService.parseSettlementMethod("CREDIT"))
                .thenThrow(new BusinessException("PAYMENT_INVALID: CREDIT is not a valid settlement method."));
        lenient().when(paymentValidationService.parseSettlementMethod("CASH"))
                .thenReturn(PaymentMethod.CASH);
        ReflectionTestUtils.setField(service, "shopTimeService", new ShopTimeService(null, "UTC"));
    }

    @Test
    void recordPaymentRejectsStoreCredit() {
        ArPaymentRequest request = new ArPaymentRequest();
        request.setAmount(new BigDecimal("50.00"));
        request.setPaymentMethod("STORE_CREDIT");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.recordPayment(100L, request, 5L));
        assertTrue(ex.getMessage().startsWith("PAYMENT_INVALID"));

        // No balance change should have happened
        assertEquals(0, customer.getCurrentBalance().compareTo(new BigDecimal("100.00")));
        assertEquals(0, customer.getCreditBalance().compareTo(BigDecimal.ZERO));
        verify(arPaymentRepository, never()).save(any());
    }

    @Test
    void recordPaymentRejectsCredit() {
        ArPaymentRequest request = new ArPaymentRequest();
        request.setAmount(new BigDecimal("50.00"));
        request.setPaymentMethod("CREDIT");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.recordPayment(100L, request, 5L));
        assertTrue(ex.getMessage().startsWith("PAYMENT_INVALID"));

        verify(arPaymentRepository, never()).save(any());
    }

    @Test
    void recordPaymentAcceptsCash() {
        ArPaymentRequest request = new ArPaymentRequest();
        request.setAmount(new BigDecimal("50.00"));
        request.setPaymentMethod("CASH");

        // Should not throw
        service.recordPayment(100L, request, 5L);
    }
}
