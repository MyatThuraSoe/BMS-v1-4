package com.bms.service;

import com.bms.dto.request.PurchaseCreateRequest;
import com.bms.entity.Product;
import com.bms.entity.Supplier;
import com.bms.exception.BusinessException;
import com.bms.repository.ProductRepository;
import com.bms.repository.PurchaseRepository;
import com.bms.repository.SupplierRepository;
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
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Audit item 19: inactive or deleted suppliers must not be used on purchases. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PurchaseServiceInactiveSupplierTest {

    @Mock private PeriodGuard periodGuard;
    @Mock private SequenceService sequenceService;
    @Mock private ProductRepository productRepository;
    @Mock private PurchaseRepository purchaseRepository;
    @Mock private SupplierRepository supplierRepository;

    private PurchaseService purchaseService;

    @BeforeEach
    void setUp() {
        purchaseService = new PurchaseService();
        ReflectionTestUtils.setField(purchaseService, "periodGuard", periodGuard);
        ReflectionTestUtils.setField(purchaseService, "sequenceService", sequenceService);
        ReflectionTestUtils.setField(purchaseService, "productRepository", productRepository);
        ReflectionTestUtils.setField(purchaseService, "purchaseRepository", purchaseRepository);
        ReflectionTestUtils.setField(purchaseService, "supplierRepository", supplierRepository);

        doNothing().when(periodGuard).assertOpen(any(LocalDate.class));
        lenient().when(sequenceService.nextPurchaseNumber()).thenReturn("PO-TEST-SUPPLIER");
        Product active = new Product();
        active.setId(1L);
        active.setName("Active product");
        active.setIsActive(true);
        lenient().when(productRepository.findById(1L)).thenReturn(Optional.of(active));
        lenient().when(purchaseRepository.saveAndFlush(any(com.bms.entity.Purchase.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createPurchaseRejectsInactiveSupplier() {
        when(supplierRepository.findById(5L)).thenReturn(Optional.of(supplier(false, null)));

        assertThrows(BusinessException.class, () -> purchaseService.createPurchase(request(), 9L));
        verify(supplierRepository).findById(5L);
    }

    @Test
    void createPurchaseRejectsDeletedSupplier() {
        when(supplierRepository.findById(5L))
                .thenReturn(Optional.of(supplier(true, LocalDateTime.now())));

        assertThrows(BusinessException.class, () -> purchaseService.createPurchase(request(), 9L));
        verify(supplierRepository).findById(5L);
    }

    private Supplier supplier(boolean active, LocalDateTime deletedAt) {
        Supplier supplier = new Supplier();
        supplier.setId(5L);
        supplier.setName("Old supplier");
        supplier.setIsActive(active);
        supplier.setDeletedAt(deletedAt);
        return supplier;
    }

    private PurchaseCreateRequest request() {
        PurchaseCreateRequest request = new PurchaseCreateRequest();
        request.setPurchaseDate(LocalDate.of(2026, 1, 10).toString());
        request.setSupplierId(5L);
        PurchaseCreateRequest.PurchaseItemRequest item = new PurchaseCreateRequest.PurchaseItemRequest();
        item.setProductId(1L);
        item.setQuantity(2);
        item.setUnitCost(BigDecimal.TEN);
        request.setItems(List.of(item));
        return request;
    }
}
