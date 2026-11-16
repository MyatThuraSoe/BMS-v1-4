package com.bms.service;

import com.bms.dto.request.CustomerCreateRequest;
import com.bms.entity.Customer;
import com.bms.exception.BusinessException;
import com.bms.repository.CustomerRepository;
import com.bms.repository.OrderRepository;
import com.bms.repository.SaleRepository;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T3: a customer is only really deletable when nothing still points at them.
 * The stored balance alone is not enough — an unpaid credit invoice or a pending
 * order that reserves stock survives the soft delete and becomes unreachable
 * from every screen that would have settled it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CustomerDeleteGuardTest {

    @Mock private CustomerRepository customerRepository;
    @Mock private SaleRepository saleRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private SequenceService sequenceService;
    @Mock private ShopTimeService shopTimeService;

    private CustomerService customerService;

    @BeforeEach
    void setUp() {
        customerService = new CustomerService();
        ReflectionTestUtils.setField(customerService, "customerRepository", customerRepository);
        ReflectionTestUtils.setField(customerService, "saleRepository", saleRepository);
        ReflectionTestUtils.setField(customerService, "orderRepository", orderRepository);
        ReflectionTestUtils.setField(customerService, "auditLogService", auditLogService);
        ReflectionTestUtils.setField(customerService, "sequenceService", sequenceService);
        ReflectionTestUtils.setField(customerService, "shopTimeService", shopTimeService);
        when(shopTimeService.now()).thenReturn(java.time.LocalDateTime.of(2026, 3, 4, 10, 0));
    }

    @Test
    void anUnpaidCreditInvoiceBlocksDeleteEvenWithAZeroBalance() {
        Customer customer = customer("0.00", "0.00");
        when(customerRepository.findById(7L)).thenReturn(Optional.of(customer));
        when(saleRepository.sumOutstandingArByCustomerId(7L)).thenReturn(new BigDecimal("150.00"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> customerService.deleteCustomer(7L, 3L));

        assertTrue(error.getMessage().contains("150.00"), error.getMessage());
        verify(customerRepository, never()).save(any(Customer.class));
    }

    @Test
    void aPendingOrderBlocksDeleteBecauseItStillHoldsStock() {
        Customer customer = customer("0.00", "0.00");
        when(customerRepository.findById(7L)).thenReturn(Optional.of(customer));
        when(saleRepository.sumOutstandingArByCustomerId(7L)).thenReturn(BigDecimal.ZERO);
        when(orderRepository.countPendingOrdersByCustomerId(7L)).thenReturn(2L);

        BusinessException error = assertThrows(BusinessException.class,
                () -> customerService.deleteCustomer(7L, 3L));

        assertTrue(error.getMessage().contains("2 pending order"), error.getMessage());
        verify(customerRepository, never()).save(any(Customer.class));
    }

    @Test
    void aSettledCustomerWithNoOpenOrderDeletesAndRecordsWhoDidIt() {
        Customer customer = customer("0.00", "0.00");
        when(customerRepository.findById(7L)).thenReturn(Optional.of(customer));
        when(saleRepository.sumOutstandingArByCustomerId(7L)).thenReturn(BigDecimal.ZERO);
        when(orderRepository.countPendingOrdersByCustomerId(7L)).thenReturn(0L);

        customerService.deleteCustomer(7L, 3L);

        assertEquals(Boolean.FALSE, customer.getIsActive());
        verify(customerRepository).save(customer);
        verify(auditLogService).logAction(eq(3L), eq("CUSTOMER_DELETE"), any(String.class),
                eq("Customer"), eq(7L), any(String.class), any());
    }

    @Test
    void creatingAndUpdatingACustomerNameTheActorInTheAudit() {
        when(sequenceService.nextCustomerNumber()).thenReturn("CUST-0009");
        when(customerRepository.save(any(Customer.class)))
                .thenAnswer(call -> call.getArgument(0));
        when(customerRepository.findById(7L)).thenReturn(Optional.of(customer("0.00", "0.00")));
        CustomerCreateRequest request = new CustomerCreateRequest();
        request.setFirstName("Nadia");
        request.setLastName("Rahman");

        customerService.createCustomer(request, 3L);
        customerService.updateCustomer(7L, request, 4L);

        verify(auditLogService).logAction(eq(3L), eq("CUSTOMER_CREATE"), any(),
                eq("Customer"), any(), isNull(), any());
        verify(auditLogService).logAction(eq(4L), eq("CUSTOMER_UPDATE"), any(),
                eq("Customer"), any(), any(), any());
    }

    private Customer customer(String balance, String storeCredit) {
        Customer customer = new Customer();
        customer.setId(7L);
        customer.setCustomerCode("CUST-0007");
        customer.setFirstName("Imran");
        customer.setLastName("Khan");
        customer.setIsActive(true);
        customer.setCurrentBalance(new BigDecimal(balance));
        customer.setCreditBalance(new BigDecimal(storeCredit));
        return customer;
    }
}
