package com.bms.service;

import com.bms.entity.ArPayment;
import com.bms.entity.PaymentMethod;
import com.bms.entity.Role;
import com.bms.entity.Sale;
import com.bms.entity.User;
import com.bms.repository.ArPaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sum behind a reprinted collection receipt has to skip the collections that
 * were reversed: money handed back to the customer never reduced what they owed,
 * so counting it would show a balance smaller than the one they actually had.
 */
@DataJpaTest
@ContextConfiguration(classes = ArPaymentLaterCollectionsTest.JpaConfig.class)
class ArPaymentLaterCollectionsTest {

    @SpringBootConfiguration
    @EnableJpaRepositories(basePackages = "com.bms.repository")
    @EntityScan(basePackages = "com.bms.entity")
    static class JpaConfig {
    }

    @Autowired private TestEntityManager em;
    @Autowired private ArPaymentRepository arPaymentRepository;

    private Sale invoice;
    private User collector;

    @BeforeEach
    void seedInvoice() {
        Role role = new Role();
        role.setName(Role.RoleName.ROLE_MANAGER);
        em.persist(role);

        collector = new User();
        collector.setUsername("ar-collector");
        collector.setEmail("ar-collector@example.test");
        collector.setPassword("unused-fixture-password");
        collector.setFirstName("Collected");
        collector.setLastName("By");
        collector.setRole(role);
        em.persist(collector);

        invoice = new Sale();
        invoice.setInvoiceNumber("INV-260101-001");
        invoice.setCustomerDisplayName("U Thuzar");
        invoice.setCashierId(collector.getId());
        invoice.setSaleDate(LocalDateTime.of(2026, 1, 1, 10, 0));
        invoice.setSubtotal(new BigDecimal("80.00"));
        invoice.setTotalAmount(new BigDecimal("80.00"));
        invoice.setAmountPaid(new BigDecimal("75.00"));
        invoice.setPaymentMethod(Sale.PaymentMethod.CREDIT);
        invoice.setSaleType(Sale.SaleType.CREDIT);
        invoice.setPaymentStatus(Sale.PaymentStatus.PARTIAL);
        em.persist(invoice);

        // 30 collected, then 20 that was reversed, then 25 that stands.
        collect("30.00", false);
        collect("20.00", true);
        collect("25.00", false);
        em.flush();
    }

    private void collect(String amount, boolean reversed) {
        ArPayment payment = new ArPayment();
        payment.setInvoice(invoice);
        payment.setAmount(new BigDecimal(amount));
        payment.setPaymentMethod(PaymentMethod.CASH);
        payment.setPaymentDate(LocalDateTime.now());
        payment.setRecordedBy(collector);
        if (reversed) {
            payment.setReversedAt(LocalDateTime.now());
            payment.setReversalNote("Given back at the counter");
        }
        em.persist(payment);
    }

    @Test
    void theCollectionsAfterAGivenOneAreSummedWithoutTheReversedOnes() {
        long first = arPaymentRepository
                .findByInvoiceIdOrderByPaymentDateAsc(invoice.getId()).get(0).getId();

        BigDecimal afterFirst = arPaymentRepository
                .sumAmountByInvoiceIdAndIdGreaterThanExcludingReversed(invoice.getId(), first);

        assertEquals(0, afterFirst.compareTo(new BigDecimal("25.00")),
                "only the 25 that stands counts; the 20 that was reversed went back over the counter");
    }

    @Test
    void nothingCollectedAfterTheLastCollectionSumsToZero() {
        long last = arPaymentRepository
                .findByInvoiceIdOrderByPaymentDateAsc(invoice.getId()).get(2).getId();

        BigDecimal afterLast = arPaymentRepository
                .sumAmountByInvoiceIdAndIdGreaterThanExcludingReversed(invoice.getId(), last);

        assertTrue(afterLast == null || afterLast.compareTo(BigDecimal.ZERO) == 0,
                "an empty tail must not read as some money was collected: " + afterLast);
    }
}
