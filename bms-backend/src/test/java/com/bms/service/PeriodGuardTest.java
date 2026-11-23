package com.bms.service;

import com.bms.exception.BusinessException;
import com.bms.repository.PeriodCloseRepository;
import com.bms.repository.SystemSettingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** TASK-5: a closed period still rejects back-dated postings. */
@ExtendWith(MockitoExtension.class)
class PeriodGuardTest {

    @Mock private PeriodCloseRepository periodCloseRepository;
    @Mock private SystemSettingRepository systemSettingRepository;
    @Mock private ShopTimeService shopTimeService;

    @Test
    void assertOpenThrowsWhenPeriodIsClosed() {
        when(shopTimeService.periodOf(LocalDate.of(2026, 1, 15))).thenReturn("2026-01");
        when(periodCloseRepository.existsById("2026-01")).thenReturn(true);
        lenient().when(systemSettingRepository.findBySettingKeyForUpdate("accounting.period.lock"))
                .thenReturn(java.util.Optional.of(new com.bms.entity.SystemSetting()));

        PeriodGuard guard = new PeriodGuard(periodCloseRepository, systemSettingRepository, shopTimeService);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> guard.assertOpen(LocalDate.of(2026, 1, 15)));
        assertEquals("Accounting period 2026-01 is closed. Record an adjusting entry in an open period instead.",
                ex.getMessage());
    }

    @Test
    void assertOpenSucceedsWhenPeriodIsOpen() {
        when(shopTimeService.periodOf(LocalDate.of(2026, 1, 15))).thenReturn("2026-01");
        when(periodCloseRepository.existsById("2026-01")).thenReturn(false);
        lenient().when(systemSettingRepository.findBySettingKeyForUpdate("accounting.period.lock"))
                .thenReturn(java.util.Optional.of(new com.bms.entity.SystemSetting()));

        PeriodGuard guard = new PeriodGuard(periodCloseRepository, systemSettingRepository, shopTimeService);

        guard.assertOpen(LocalDate.of(2026, 1, 15)); // should not throw
    }
}
