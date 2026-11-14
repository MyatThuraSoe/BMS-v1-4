package com.bms.service;

import com.bms.exception.BusinessException;
import com.bms.repository.PeriodCloseRepository;
import com.bms.repository.SystemSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class PeriodGuard {

    public static final String LOCK_KEY = "accounting.period.lock";

    private final PeriodCloseRepository periodCloseRepository;
    private final SystemSettingRepository systemSettingRepository;
    private final ShopTimeService shopTimeService;

    public PeriodGuard(PeriodCloseRepository periodCloseRepository,
                       SystemSettingRepository systemSettingRepository,
                       ShopTimeService shopTimeService) {
        this.periodCloseRepository = periodCloseRepository;
        this.systemSettingRepository = systemSettingRepository;
        this.shopTimeService = shopTimeService;
    }

    public void lock() {
        // The same row locks postings and close/reopen until their transaction commits.
        systemSettingRepository.findBySettingKeyForUpdate(LOCK_KEY)
                .orElseThrow(() -> new BusinessException("Accounting transaction lock is not initialized"));
    }

    public void assertOpen(LocalDate date) {
        lock();
        // O7: period assignment always uses the shop timezone, never the JVM default.
        String period = shopTimeService.periodOf(date);
        if (periodCloseRepository.existsById(period)) {
            throw new BusinessException("Accounting period " + period
                    + " is closed. Record an adjusting entry in an open period instead.");
        }
    }

    public void assertOpen(LocalDateTime at) {
        assertOpen(shopTimeService.toShopDate(at));
    }
}
