package com.bms.service;

import com.bms.dto.request.PurchaseCreateRequest;
import com.bms.entity.Product;
import com.bms.exception.BusinessException;
import com.bms.repository.ProductRepository;
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
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * Defect 5: an inactive or soft-deleted product must not be receivable on a
 * purchase, in both createPurchase and updatePurchase.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PurchaseServiceInactiveProductTest {

    @Mock private PeriodGuard periodGuard;
    @Mock private SequenceService sequenceService;
    @Mock private ProductRepository productRepository;

    private PurchaseService purchaseService;

    @BeforeEach
    void setUp() {
        purchaseService = new PurchaseService();
        ReflectionTestUtils.setField(purchaseService, "periodGuard", periodGuard);
        ReflectionTestUtils.setField(purchaseService, "sequenceService", sequenceService);
        ReflectionTestUtils.setField(purchaseService, "productRepository", productRepository);

        doNothing().when(periodGuard).assertOpen(any(LocalDate.class));
        lenient().when(sequenceService.nextPurchaseNumber()).thenReturn("PO-TEST-1");
    }

    @Test
    void createPurchaseRejectsInactiveProduct() {
        Product inactive = new Product();
        inactive.setId(1L);
        inactive.setName("Discontinued");
        inactive.setIsActive(false);
        lenient().when(productRepository.findById(anyLong())).thenReturn(Optional.of(inactive));

        PurchaseCreateRequest request = new PurchaseCreateRequest();
        request.setPurchaseDate(LocalDate.of(2026, 1, 10).toString());
        PurchaseCreateRequest.PurchaseItemRequest item = new PurchaseCreateRequest.PurchaseItemRequest();
        item.setProductId(1L);
        item.setQuantity(5);
        item.setUnitCost(BigDecimal.valueOf(10));
        request.setItems(List.of(item));

        assertThrows(BusinessException.class, () -> purchaseService.createPurchase(request, 9L));
        verify(productRepository).findById(1L);
    }

    @Test
    void createPurchaseRejectsDeletedProduct() {
        Product deleted = new Product();
        deleted.setId(2L);
        deleted.setName("Deleted");
        deleted.setIsActive(true);
        deleted.setDeletedAt(java.time.LocalDateTime.now());
        lenient().when(productRepository.findById(anyLong())).thenReturn(Optional.of(deleted));

        PurchaseCreateRequest request = new PurchaseCreateRequest();
        request.setPurchaseDate(LocalDate.of(2026, 1, 10).toString());
        PurchaseCreateRequest.PurchaseItemRequest item = new PurchaseCreateRequest.PurchaseItemRequest();
        item.setProductId(2L);
        item.setQuantity(5);
        item.setUnitCost(BigDecimal.valueOf(10));
        request.setItems(List.of(item));

        assertThrows(BusinessException.class, () -> purchaseService.createPurchase(request, 9L));
        verify(productRepository).findById(2L);
    }
}
