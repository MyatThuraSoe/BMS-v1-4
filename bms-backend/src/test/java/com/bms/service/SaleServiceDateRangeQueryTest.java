package com.bms.service;

import com.bms.entity.Sale;
import com.bms.repository.SaleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Audit T6-8: the date-range feed must ask the repository for the range instead
 * of paging the newest 1000 sales and filtering in memory.
 */
class SaleServiceDateRangeQueryTest {

    private static final LocalDate START = LocalDate.of(2026, 11, 1);
    private static final LocalDate END = LocalDate.of(2026, 11, 30);

    @Test
    void dateRangeIsQueriedInTheDatabase() throws Exception {
        SaleRepository saleRepository = mock(SaleRepository.class);
        Page<Sale> expected = new PageImpl<>(List.of());
        when(saleRepository.findByDateRange(any(), any(), any())).thenReturn(expected);

        SaleService service = new SaleService();
        ReflectionTestUtils.setField(service, "saleRepository", saleRepository);

        Method query = SaleService.class.getMethod("getSalesByDateRange", LocalDate.class, LocalDate.class);
        Object actual = query.invoke(service, START, END);

        assertSame(expected, actual);
        verify(saleRepository).findByDateRange(
                eq(START.atStartOfDay()),
                eq(END.plusDays(1).atStartOfDay().minusNanos(1)),
                eq(Pageable.unpaged()));
    }
}
