package com.bms.service;

import com.bms.entity.Supplier;
import com.bms.exception.BusinessException;
import com.bms.repository.PurchaseRepository;
import com.bms.repository.SupplierRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A supplier that is still owed money, or still has goods coming in, must not
 * vanish from the payables screens (same gap as customer delete in T3).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SupplierDeleteGuardTest {

    @Mock
    private SupplierRepository supplierRepository;

    @Mock
    private PurchaseRepository purchaseRepository;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private SupplierService service;

    private Supplier supplier;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "shopTimeService", new ShopTimeService(null, "UTC"));
        supplier = new Supplier();
        supplier.setId(3L);
        supplier.setName("Delta Dairy");
        supplier.setEmail("delta@example.com");
        supplier.setIsActive(true);
        when(supplierRepository.findById(3L)).thenReturn(Optional.of(supplier));
        when(supplierRepository.save(any(Supplier.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void anUnpaidPurchaseBlocksTheDelete() {
        when(purchaseRepository.countUnpaidInvoicesBySupplierId(3L)).thenReturn(2L);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.deleteSupplier(3L, 9L));

        assertTrue(ex.getMessage().contains("2"), "the refusal must say how many invoices are open");
        verify(supplierRepository, never()).save(any(Supplier.class));
        verify(auditLogService, never()).logAction(any(), anyString(), anyString(), anyString(),
                any(), any(), any());
    }

    @Test
    void goodsStillToArriveBlockTheDelete() {
        when(purchaseRepository.countAwaitingReceivingBySupplierId(3L)).thenReturn(1L);

        assertThrows(BusinessException.class, () -> service.deleteSupplier(3L, 9L));

        verify(supplierRepository, never()).save(any(Supplier.class));
    }

    @Test
    void aSettledSupplierIsReleasedAndTheActingAdminIsRecorded() {
        service.deleteSupplier(3L, 9L);

        assertNotNull(supplier.getDeletedAt());
        assertFalse(supplier.getIsActive());
        assertTrue(supplier.getName().startsWith("Delta Dairy#del_"),
                "the unique name is released so a new supplier may reuse it");
        verify(auditLogService).logAction(eq(9L), eq("SUPPLIER_DELETE"), anyString(),
                eq("Supplier"), eq(3L), anyString(), isNull());
    }
}
