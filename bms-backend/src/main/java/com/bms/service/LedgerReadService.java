package com.bms.service;

import com.bms.entity.PeriodClose;
import com.bms.repository.PeriodCloseRepository;
import com.bms.repository.SystemSettingRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class LedgerReadService {

    @Autowired
    private ShopTimeService shopTimeService;

    public static final String CUTOVER_KEY = "accounting.ledger.cutover";
    public static final String LEGACY_ENABLED_KEY = "accounting.ledger.legacy.enabled";

    private final JdbcTemplate jdbcTemplate;
    private final SystemSettingRepository systemSettingRepository;
    private final PeriodCloseRepository periodCloseRepository;

    public LedgerReadService(JdbcTemplate jdbcTemplate, SystemSettingRepository systemSettingRepository,
                             PeriodCloseRepository periodCloseRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.systemSettingRepository = systemSettingRepository;
        this.periodCloseRepository = periodCloseRepository;
    }

    public boolean isActive() {
        return systemSettingRepository.findBySettingKey(CUTOVER_KEY)
                .map(setting -> setting.getSettingValue() != null && !setting.getSettingValue().isBlank())
                .orElse(false);
    }

    public BigDecimal[] totals(LocalDateTime from, LocalDateTime to) {
        Map<String, BigDecimal[]> periods = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT period, SUM(net_revenue), SUM(cogs), SUM(expense) FROM profit_ledger "
                        + "WHERE occurred_at >= ? AND occurred_at < ? GROUP BY period",
                (RowCallbackHandler) rs -> periods.put(rs.getString(1), new BigDecimal[]{
                        value(rs.getBigDecimal(2)), value(rs.getBigDecimal(3)), value(rs.getBigDecimal(4))}), from, to);
        for (PeriodClose close : periodCloseRepository.findByPeriodGreaterThanEqualAndPeriodLessThanEqual(
                YearMonth.from(from).toString(), YearMonth.from(to.minusNanos(1)).toString())) {
            YearMonth month = YearMonth.parse(close.getPeriod());
            LocalDateTime start = month.atDay(1).atStartOfDay();
            LocalDateTime end = month.plusMonths(1).atDay(1).atStartOfDay();
            if (!from.isAfter(start) && !to.isBefore(end)) {
                periods.put(close.getPeriod(), new BigDecimal[]{close.getFrozenRevenue(),
                        close.getFrozenCogs(), close.getFrozenExpense()});
            }
        }
        BigDecimal[] result = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        for (BigDecimal[] period : periods.values()) {
            for (int i = 0; i < result.length; i++) result[i] = result[i].add(period[i]);
        }
        return result;
    }

    public Map<String, BigDecimal> expensesByCategory(LocalDateTime from, LocalDateTime to, boolean inventoryLoss) {
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        String predicate = inventoryLoss ? "event_type = 'WRITE_OFF'" : "event_type IN ('EXPENSE', 'PURCHASE_VARIANCE')";
        jdbcTemplate.query("SELECT COALESCE(category, 'OTHER'), SUM(expense) FROM profit_ledger "
                        + "WHERE occurred_at >= ? AND occurred_at < ? AND " + predicate
                        + " GROUP BY category ORDER BY category",
                (RowCallbackHandler) rs -> amounts.put(rs.getString(1), value(rs.getBigDecimal(2))), from, to);
        return amounts;
    }

    private static BigDecimal value(BigDecimal amount) {
        return amount != null ? amount : BigDecimal.ZERO;
    }
}
