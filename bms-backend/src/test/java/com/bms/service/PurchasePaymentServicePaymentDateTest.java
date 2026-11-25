package com.bms.service;

import com.bms.dto.request.PurchasePaymentRequest;
import com.bms.entity.Purchase;
import com.bms.entity.User;
import com.bms.exception.BusinessException;
import com.bms.repository.PurchasePaymentRepository;
import com.bms.repository.PurchaseRepository;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Audit item 19: a supplier payment cannot be dated before the purchase. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PurchasePaymentServicePaymentDateTest {

    @Mock private PurchaseRepository purchaseRepository;
    @Mock private PurchasePaymentRepository purchasePaymentRepository;
    @Mock private UserRepository userRepository;
    @Mock private ShopTimeService shopTimeService;
    @Mock private PeriodGuard periodGuard;

    private PurchasePaymentService service;

    @BeforeEach
    void setUp() {
        service = new PurchasePaymentService();
        ReflectionTestUtils.setField(service, "purchaseRepository", purchaseRepository);
        ReflectionTestUtils.setField(service, "purchasePaymentRepository", purchasePaymentRepository);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        ReflectionTestUtils.setField(service, "shopTimeService", shopTimeService);
        ReflectionTestUtils.setField(service, "periodGuard", periodGuard);

        Purchase purchase = new Purchase();
        purchase.setId(7L);
        purchase.setPurchaseNumber("PO-7");
        purchase.setIsActive(true);
        purchase.setDeletedAt(null);
        purchase.setPurchaseDate(LocalDate.of(2026, 1, 10));
        purchase.setReceivingStatus(Purchase.ReceivingStatus.RECEIVED);
        purchase.setTotalAmount(new BigDecimal("100.00"));
        purchase.setReturnedAmount(BigDecimal.ZERO);
        when(purchaseRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(purchase));
        lenient().when(purchasePaymentRepository.sumAmountByPurchaseId(7L)).thenReturn(BigDecimal.ZERO);
        lenient().when(userRepository.findById(9L)).thenReturn(Optional.of(new User()));
        lenient().when(shopTimeService.today()).thenReturn(LocalDate.of(2026, 1, 15));
        lenient().when(shopTimeService.now()).thenReturn(LocalDateTime.of(2026, 1, 15, 10, 0));
        doNothing().when(periodGuard).assertOpen(any(LocalDateTime.class));
    }

    @Test
    void paymentDatedBeforeThePurchaseIsRejected() {
        PurchasePaymentRequest request = new PurchasePaymentRequest();
        request.setAmount(new BigDecimal("20.00"));
        request.setPaymentDate(LocalDate.of(2026, 1, 9));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.recordPayment(7L, request, 9L));

        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("before the purchase"),
                ex.getMessage());
        verify(purchasePaymentRepository, never()).save(any());
    }
}
