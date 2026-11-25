package com.bms.service;

import com.bms.dto.response.PayablesAgingResponse;
import com.bms.entity.Purchase;
import com.bms.entity.PurchaseItem;
import com.bms.entity.Supplier;
import com.bms.repository.PurchasePaymentRepository;
import com.bms.repository.PurchaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Defect 2: payables aging must not show 100% of a partially-received PO as
 * outstanding; only the received share of the goods value is owed yet.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PurchasePaymentServicePayablesAgingTest {

    @Mock private PurchaseRepository purchaseRepository;
    @Mock private PurchasePaymentRepository purchasePaymentRepository;

    private PurchasePaymentService service;

    @BeforeEach
    void setUp() {
        service = new PurchasePaymentService();
        ReflectionTestUtils.setField(service, "purchaseRepository", purchaseRepository);
        ReflectionTestUtils.setField(service, "purchasePaymentRepository", purchasePaymentRepository);

        LocalDate asOf = LocalDate.of(2026, 1, 15);
        lenient().when(purchasePaymentRepository.sumAmountsByPurchaseIds(anyList()))
                .thenReturn(new ArrayList<>());
    }

    @Test
    void partialReceiptShowsOnlyReceivedShareAsOutstanding() {
        // PO for 10 units @ 100.00 = 1000.00 subtotal, only 4 units received = 400.00 received.
        // Payable 1000.00 scaled by 400/1000 = 400.00 outstanding (nothing paid yet).
        Purchase po = purchase(1L, "SUP-1", LocalDate.of(2026, 1, 15),
                Purchase.ReceivingStatus.PARTIAL,
                new BigDecimal("1000.00"), new BigDecimal("1000.00"),
                List.of(item(10L, new BigDecimal("100.00"), 10, 4)));

        when(purchaseRepository.findActivePurchases(Pageable.unpaged()))
                .thenReturn(new PageImpl<>(List.of(po)));

        PayablesAgingResponse response = service.getPayablesAging(LocalDate.of(2026, 1, 15));

        PayablesAgingResponse.SupplierAging row = findSupplier(response, "SUP-1");
        assertEquals(new BigDecimal("400.00"), row.getTotalOutstanding(),
                "only the 40% received share of a PARTIAL PO is outstanding");
    }

    @Test
    void fullyReceivedPoShowsFullPayableAsOutstanding() {
        Purchase po = purchase(2L, "SUP-2", LocalDate.of(2026, 1, 15),
                Purchase.ReceivingStatus.RECEIVED,
                new BigDecimal("1000.00"), new BigDecimal("1000.00"),
                List.of(item(20L, new BigDecimal("100.00"), 10, 10)));

        when(purchaseRepository.findActivePurchases(Pageable.unpaged()))
                .thenReturn(new PageImpl<>(List.of(po)));

        PayablesAgingResponse response = service.getPayablesAging(LocalDate.of(2026, 1, 15));

        PayablesAgingResponse.SupplierAging row = findSupplier(response, "SUP-2");
        assertEquals(new BigDecimal("1000.00"), row.getTotalOutstanding(),
                "a fully received PO shows its full payable as outstanding");
    }

    @Test
    void orderedPoIsSkippedInAging() {
        Purchase po = purchase(3L, "SUP-3", LocalDate.of(2026, 1, 15),
                Purchase.ReceivingStatus.ORDERED,
                new BigDecimal("1000.00"), new BigDecimal("1000.00"),
                List.of(item(30L, new BigDecimal("100.00"), 10, 0)));

        when(purchaseRepository.findActivePurchases(Pageable.unpaged()))
                .thenReturn(new PageImpl<>(List.of(po)));

        PayablesAgingResponse response = service.getPayablesAging(LocalDate.of(2026, 1, 15));

        assertEquals(0, response.getSuppliers().size(),
                "an ORDERED PO must not appear in payables aging");
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private Purchase purchase(Long id, String supplierName, LocalDate purchaseDate,
                              Purchase.ReceivingStatus status, BigDecimal subtotal,
                              BigDecimal totalAmount, List<PurchaseItem> items) {
        Supplier supplier = new Supplier();
        supplier.setId(id);
        supplier.setName(supplierName);
        Purchase po = new Purchase();
        po.setId(id);
        po.setPurchaseNumber("PO-" + id);
        po.setSupplier(supplier);
        po.setPurchaseDate(purchaseDate);
        po.setReceivingStatus(status);
        po.setSubtotal(subtotal);
        po.setTotalAmount(totalAmount);
        po.setExchangeRate(BigDecimal.ONE);
        po.setItems(items);
        return po;
    }

    private PurchaseItem item(Long id, BigDecimal unitCost, int quantity, int received) {
        PurchaseItem it = new PurchaseItem();
        it.setId(id);
        it.setUnitCost(unitCost);
        it.setQuantity(quantity);
        it.setReceivedQuantity(received);
        return it;
    }

    private PayablesAgingResponse.SupplierAging findSupplier(PayablesAgingResponse response,
                                                              String name) {
        for (PayablesAgingResponse.SupplierAging row : response.getSuppliers()) {
            if (name.equals(row.getSupplierName())) {
                return row;
            }
        }
        throw new IllegalStateException("supplier not found in aging: " + name);
    }
}
