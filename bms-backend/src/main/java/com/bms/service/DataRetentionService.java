package com.bms.service;

import com.bms.entity.PeriodClose;
import com.bms.entity.Sale;
import com.bms.entity.StockMovement;
import com.bms.exception.BusinessException;
import com.bms.repository.PeriodCloseRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * O4: bulk removal of historical sales. Deleting a sale cascades through the
 * invoice children, so the order of the statements below is what keeps the run
 * legal under foreign-key checks; ledger rows are append-only audit history and
 * are never touched here.
 */
@Service
public class DataRetentionService {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionService.class);

    @Autowired
    private PeriodCloseRepository periodCloseRepository;

    @Autowired
    private ShopTimeService shopTimeService;

    @Autowired
    private AuditLogService auditLogService;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public Map<String, Object> deleteSalesOlderThanYears(int years, boolean backupConfirmed, Long userId) {
        if (!backupConfirmed) {
            throw new BusinessException("Confirm a database backup before deleting old sales");
        }
        if (years < 1) {
            throw new BusinessException("Years must be at least 1");
        }

        LocalDateTime cutoff = shopTimeService.today().minusYears(years).atStartOfDay();
        List<Sale> candidates = entityManager.createQuery("""
                SELECT s FROM Sale s WHERE s.saleDate < :cutoff ORDER BY s.id
                """, Sale.class)
                .setParameter("cutoff", cutoff)
                .getResultList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cutoffDate", cutoff.toLocalDate().toString());
        if (candidates.isEmpty()) {
            result.put("deletedSales", 0);
            return result;
        }

        assertNotInClosedPeriod(candidates);
        assertFullySettled(candidates);

        List<Long> saleIds = candidates.stream().map(Sale::getId).toList();
        List<Long> returnIds = entityManager.createQuery("""
                SELECT r.id FROM SaleReturn r WHERE r.sale.id IN :saleIds
                """, Long.class)
                .setParameter("saleIds", saleIds)
                .getResultList();

        if (returnIds.isEmpty()) {
            execute("""
                    DELETE FROM SaleReturnItem ri WHERE ri.saleItem.sale.id IN :saleIds
                    """, saleIds);
        } else {
            entityManager.createQuery("""
                    DELETE FROM SaleReturnItem ri
                    WHERE ri.saleReturn.id IN :returnIds OR ri.saleItem.sale.id IN :saleIds
                    """)
                    .setParameter("returnIds", returnIds)
                    .setParameter("saleIds", saleIds)
                    .executeUpdate();
        }

        execute("DELETE FROM SaleReturn r WHERE r.sale.id IN :saleIds", saleIds);
        execute("DELETE FROM SalePayment sp WHERE sp.sale.id IN :saleIds", saleIds);
        execute("DELETE FROM ArPayment ap WHERE ap.invoice.id IN :saleIds", saleIds);
        deleteMovements(saleIds, returnIds);
        execute("DELETE FROM SaleItem si WHERE si.sale.id IN :saleIds", saleIds);

        int deleted = entityManager.createQuery("""
                DELETE FROM Sale s WHERE s.id IN :saleIds
                """)
                .setParameter("saleIds", saleIds)
                .executeUpdate();

        result.put("deletedSales", deleted);
        result.put("deletedReturns", returnIds.size());
        log.info("Deleted {} sales older than {} (by user {})", deleted, cutoff.toLocalDate(), userId);
        auditLogService.logAction(userId, "SALE_DELETE_OLD",
                "Deleted sales older than " + cutoff.toLocalDate() + ". Count: " + deleted,
                "Sale", null, null, null);
        return result;
    }

    /**
     * Movements are matched on their own source columns: a sale-out row points at
     * the sale, a restock row at the sale return it came from. The reference-id
     * fallback covers rows written before those columns existed, and matching a
     * movement of the same type by a different id leaves unrelated adjustments and
     * transfer rows alone.
     */
    private void deleteMovements(List<Long> saleIds, List<Long> returnIds) {
        String jpql = """
                DELETE FROM StockMovement sm
                WHERE sm.referenceType IN (:types)
                  AND (sm.sourceSaleId IN :saleIds
                       %sOR (sm.sourceSaleId IS NULL AND sm.sourceReturnId IS NULL
                           AND sm.referenceId IN :saleIds))
                """.formatted(returnIds.isEmpty() ? "" : "OR sm.sourceReturnId IN :returnIds\n");
        var query = entityManager.createQuery(jpql)
                .setParameter("types", List.of(StockMovement.ReferenceType.SALE,
                        StockMovement.ReferenceType.RETURN))
                .setParameter("saleIds", saleIds);
        if (!returnIds.isEmpty()) {
            query.setParameter("returnIds", returnIds);
        }
        int deleted = query.executeUpdate();
        log.debug("Removed {} stock movements for {} deleted sales", deleted, saleIds.size());
    }

    private void execute(String jpql, List<Long> saleIds) {
        entityManager.createQuery(jpql).setParameter("saleIds", saleIds).executeUpdate();
    }

    private void assertNotInClosedPeriod(List<Sale> candidates) {
        Set<String> closed = new HashSet<>();
        for (PeriodClose periodClose : periodCloseRepository.findAll()) {
            if (periodClose.getPeriod() != null) {
                closed.add(periodClose.getPeriod());
            }
        }
        if (closed.isEmpty()) {
            return;
        }
        List<String> blocked = new ArrayList<>();
        for (Sale sale : candidates) {
            if (sale.getSaleDate() != null
                    && closed.contains(YearMonth.from(sale.getSaleDate()).toString())) {
                blocked.add(sale.getInvoiceNumber() + " (" + sale.getSaleDate().toLocalDate() + ")");
            }
        }
        if (!blocked.isEmpty()) {
            throw new BusinessException("Sales in closed accounting periods cannot be deleted: "
                    + String.join(", ", blocked));
        }
    }

    private void assertFullySettled(List<Sale> candidates) {
        List<String> blocked = candidates.stream()
                .filter(sale -> sale.getPaymentStatus() != Sale.PaymentStatus.PAID)
                .map(sale -> sale.getInvoiceNumber() + " (" + sale.getPaymentStatus() + ")")
                .toList();
        if (!blocked.isEmpty()) {
            throw new BusinessException("Sales with an outstanding balance cannot be deleted: "
                    + String.join(", ", blocked));
        }
    }
}
