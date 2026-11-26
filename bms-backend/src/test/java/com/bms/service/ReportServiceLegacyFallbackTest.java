package com.bms.service;

import com.bms.entity.SystemSetting;
import com.bms.repository.SaleRepository;
import com.bms.repository.SystemSettingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A shop that has not cut over yet still needs its reports to open. */
class ReportServiceLegacyFallbackTest {

    @Test
    void profitSummaryUsesLegacyDataWhenLedgerReadsAreInactive() {
        LocalDate start = LocalDate.of(2026, 11, 1);
        LocalDate end = LocalDate.of(2026, 11, 26);

        ReportService service = new ReportService();
        LedgerReadService ledger = mock(LedgerReadService.class);
        when(ledger.isActive()).thenReturn(false);

        SystemSettingRepository settings = mock(SystemSettingRepository.class);
        SystemSetting legacyFlag = new SystemSetting();
        legacyFlag.setSettingKey(LedgerReadService.LEGACY_ENABLED_KEY);
        legacyFlag.setSettingValue("false");
        when(settings.findBySettingKey(LedgerReadService.LEGACY_ENABLED_KEY))
                .thenReturn(Optional.of(legacyFlag));

        ShopTimeService shopTime = mock(ShopTimeService.class);
        when(shopTime.startOfDay(start)).thenReturn(start.atStartOfDay());
        when(shopTime.endOfDayExclusive(end)).thenReturn(end.plusDays(1).atStartOfDay());

        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        doReturn(new BigDecimal[]{new BigDecimal("120.00"), new BigDecimal("70.000000")})
                .when(jdbcTemplate).queryForObject(anyString(),
                        org.mockito.ArgumentMatchers.<RowMapper<BigDecimal[]>>any(),
                        any(LocalDateTime.class), any(LocalDateTime.class));

        SaleRepository sales = mock(SaleRepository.class);
        when(sales.sumOutstandingAr()).thenReturn(BigDecimal.ZERO);

        ReflectionTestUtils.setField(service, "ledgerReadService", ledger);
        ReflectionTestUtils.setField(service, "systemSettingRepository", settings);
        ReflectionTestUtils.setField(service, "shopTimeService", shopTime);
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbcTemplate);
        ReflectionTestUtils.setField(service, "saleRepository", sales);

        Map<String, Object> summary = service.getProfitSummary(start, end);

        assertEquals(0, new BigDecimal("120.00").compareTo((BigDecimal) summary.get("revenue")));
        assertEquals(0, new BigDecimal("70.000000").compareTo((BigDecimal) summary.get("cogs")));
        assertEquals(0, new BigDecimal("50.00").compareTo((BigDecimal) summary.get("grossProfit")));
    }
}
