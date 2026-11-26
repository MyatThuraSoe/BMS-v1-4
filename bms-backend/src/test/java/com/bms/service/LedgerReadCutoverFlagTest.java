package com.bms.service;

import com.bms.entity.SystemSetting;
import com.bms.repository.PeriodCloseRepository;
import com.bms.repository.SystemSettingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Audit T6-11: only a real cutover timestamp/flag activates ledger reads. */
class LedgerReadCutoverFlagTest {

    private final SystemSettingRepository settings = mock(SystemSettingRepository.class);
    private final LedgerReadService service =
            new LedgerReadService(mock(JdbcTemplate.class), settings, mock(PeriodCloseRepository.class));

    @Test
    void explicitDisabledValuesDoNotActivateLedgerReads() {
        for (String value : new String[]{"false", "FALSE", "0", "no", "off", "garbage"}) {
            assertFalse(isActiveWith(value), "cutover value '" + value + "' must stay inactive");
        }
    }

    @Test
    void blankOrMissingValueStaysInactive() {
        assertFalse(isActiveWith(null));
        assertFalse(isActiveWith("   "));
        assertFalse(service.isActive());
    }

    @Test
    void timestampOrExplicitTrueActivatesLedgerReads() {
        assertTrue(isActiveWith("2026-11-01T00:00:00"));
        assertTrue(isActiveWith("2026-11-01T00:00"));
        assertTrue(isActiveWith("true"));
        assertTrue(isActiveWith("1"));
    }

    private boolean isActiveWith(String value) {
        SystemSetting setting = new SystemSetting();
        setting.setSettingKey(LedgerReadService.CUTOVER_KEY);
        setting.setSettingValue(value);
        when(settings.findBySettingKey(LedgerReadService.CUTOVER_KEY)).thenReturn(Optional.of(setting));
        return service.isActive();
    }
}
