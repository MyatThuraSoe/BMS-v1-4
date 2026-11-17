package com.bms.service;

import com.bms.entity.Sale;
import com.bms.repository.ProductRepository;
import com.bms.repository.SaleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A shop that has been trading for years cannot afford a backup that loads its
 * whole sales table into memory before writing it — the export is exactly the
 * operation that has to work on the biggest database in the building. The format
 * has to stay the same, or every backup already in Drive stops being restorable.
 */
class DataExportStreamingTest {

    private static final int TOTAL_SALES = 5_001;

    private final Map<Class<?>, Object> fakes = new LinkedHashMap<>();
    private DataExportService service;
    private SaleRepository sales;
    private ProductRepository products;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        Constructor<?> ctor = DataExportService.class.getDeclaredConstructors()[0];
        for (Class<?> type : ctor.getParameterTypes()) {
            fakes.put(type, mock(type));
        }
        for (Object fake : fakes.values()) {
            JpaRepository repo = (JpaRepository) fake;
            when(repo.findAll(any(Pageable.class))).thenReturn(Page.empty());
        }
        service = (DataExportService) newInstance(ctor, fakes.values().toArray());
        ShopTimeService shopTime = mock(ShopTimeService.class);
        when(shopTime.now()).thenReturn(LocalDateTime.of(2026, 11, 17, 2, 0));
        ReflectionTestUtils.setField(service, "shopTimeService", shopTime);
        ReflectionTestUtils.setField(service, "entityManager", mock(EntityManager.class));

        sales = (SaleRepository) fakes.get(SaleRepository.class);
        products = (ProductRepository) fakes.get(ProductRepository.class);
        when(sales.findAll(any(Pageable.class))).thenAnswer(invocation -> page(invocation.getArgument(0)));
    }

    private static Object newInstance(Constructor<?> ctor, Object[] args) {
        try {
            return ctor.newInstance(args);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Page<Sale> page(Pageable request) {
        int from = (int) request.getOffset();
        List<Sale> chunk = new ArrayList<>();
        for (int i = from; i < Math.min(from + request.getPageSize(), TOTAL_SALES); i++) {
            Sale sale = new Sale();
            sale.setId((long) i);
            sale.setInvoiceNumber("INV-" + i);
            sale.setSaleDate(LocalDateTime.of(2026, 11, 1, 10, 0));
            sale.setSubtotal(new BigDecimal("10.00"));
            sale.setTaxAmount(BigDecimal.ZERO.setScale(2));
            sale.setDiscountAmount(BigDecimal.ZERO.setScale(2));
            sale.setTotalAmount(new BigDecimal("10.00"));
            sale.setAmountPaid(new BigDecimal("10.00"));
            sale.setChangeGiven(BigDecimal.ZERO.setScale(2));
            chunk.add(sale);
        }
        return new PageImpl<>(chunk, request, TOTAL_SALES);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theExportReadsTheSalesTableInPages() throws Exception {
        byte[] json = service.exportAllAsJson();

        Map<String, Object> parsed = new ObjectMapper().readValue(json, Map.class);
        Map<String, Object> data = (Map<String, Object>) parsed.get("data");
        List<Object> exportedSales = (List<Object>) data.get("sales");

        assertEquals(TOTAL_SALES, exportedSales.size(),
                "paging must not drop the last partial page");
        verify(sales, never()).findAll();
        verify(products, never()).findAll();
        // 500 at a time, so 11 reads for 5001 rows: ten full pages plus the tail.
        verify(sales, times(11)).findAll(any(Pageable.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theExportKeepsTheDocumentTheRestoreAlreadyReads() throws Exception {
        byte[] json = service.exportAllAsJson();

        Map<String, Object> parsed = new ObjectMapper().readValue(json, Map.class);
        assertEquals("LumiPOS", parsed.get("app"));
        assertEquals("1.0", parsed.get("backupVersion"));
        assertNotNull(parsed.get("exportedAt"));
        assertNotNull(parsed.get("data"));

        String text = new String(json, StandardCharsets.UTF_8);
        assertTrue(text.contains("\"INV-5000\""), "the newest row is written, not just the first page");
        assertTrue(text.contains("\"sales\" : ["), "the document is still the pretty one the shop can read");
    }

    @Test
    @SuppressWarnings("unchecked")
    void everySectionOfACompleteBackupIsWrittenEvenWhenTheTableIsEmpty() throws Exception {
        // This test is about a shop whose tables hold nothing, not about paging.
        when(sales.findAll(any(Pageable.class))).thenReturn(Page.empty());

        byte[] json = service.exportAllAsJson();

        Map<String, Object> parsed = new ObjectMapper().readValue(json, Map.class);
        Map<String, Object> data = (Map<String, Object>) parsed.get("data");

        List<String> required = List.of(
                "categories", "products", "customers", "suppliers", "sales", "purchases",
                "saleReturns", "expenses", "cashShifts", "users", "shopInfo", "systemSettings",
                "arPayments", "receiptCustomizations", "orders", "orderSequences",
                "invoiceSequences", "purchaseSequences", "customerSequences", "transferSequences",
                "locations", "locationStock", "stockMovements", "stockTransfers", "priceLists",
                "promotions", "productPriceHistory", "purchasePayments", "cashDrawers",
                "cashDrawerSessions", "cashDrawerMovements", "countSheets", "glAccounts",
                "glMappings", "periodCloses", "inventoryLedger", "profitLedger");

        assertTrue(data.keySet().containsAll(required),
                "missing sections: " + required.stream().filter(s -> !data.containsKey(s)).toList());
        for (String section : required) {
            assertEquals(0, ((List<Object>) data.get(section)).size(),
                    section + " must be an array, not absent, so the restore reads it as empty");
        }
    }
}
