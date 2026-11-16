package com.bms.service;

import com.bms.dto.request.PurchaseCreateRequest;
import com.bms.dto.request.ReceivePurchaseRequest;
import com.bms.dto.request.ReturnToVendorRequest;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.PurchaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O3: every write path on a purchase has to take the row lock before it reads
 * the purchase, otherwise two receipts (or a receipt and a return) can each
 * see the old received quantities and the second one overwrites the first.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PurchaseServiceRowLockTest {

    @Mock private PurchaseRepository purchaseRepository;
    @Mock private PeriodGuard periodGuard;

    private PurchaseService service;

    @BeforeEach
    void setUp() {
        service = new PurchaseService();
        ReflectionTestUtils.setField(service, "purchaseRepository", purchaseRepository);
        ReflectionTestUtils.setField(service, "periodGuard", periodGuard);
        ShopTimeTestSupport.withClock(service);
        when(purchaseRepository.findByIdForUpdate(any())).thenReturn(Optional.empty());
    }

    private void assertLockedInsteadOfPlainFind() {
        verify(purchaseRepository).findByIdForUpdate(1L);
        verify(purchaseRepository, never()).findById(any());
    }

    @Test
    void receivingLocksThePurchaseRow() {
        assertThrows(ResourceNotFoundException.class,
                () -> service.receivePurchase(1L, new ReceivePurchaseRequest(), 9L));
        assertLockedInsteadOfPlainFind();
    }

    @Test
    void returningToVendorLocksThePurchaseRow() {
        assertThrows(ResourceNotFoundException.class,
                () -> service.returnToVendor(1L, new ReturnToVendorRequest(), 9L));
        assertLockedInsteadOfPlainFind();
    }

    @Test
    void deletingLocksThePurchaseRow() {
        assertThrows(ResourceNotFoundException.class, () -> service.deletePurchase(1L, 9L));
        assertLockedInsteadOfPlainFind();
    }

    @Test
    void updatingLocksThePurchaseRow() {
        PurchaseCreateRequest request = new PurchaseCreateRequest();
        request.setPurchaseDate("2026-11-16");

        assertThrows(ResourceNotFoundException.class, () -> service.updatePurchase(1L, request, 9L));
        assertLockedInsteadOfPlainFind();
    }
}
