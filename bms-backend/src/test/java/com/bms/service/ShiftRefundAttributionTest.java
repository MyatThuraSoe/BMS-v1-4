package com.bms.service;

import com.bms.entity.CashShift;
import com.bms.entity.PaymentMethod;
import com.bms.entity.Role;
import com.bms.entity.Sale;
import com.bms.entity.SaleReturn;
import com.bms.entity.User;
import com.bms.repository.SaleRepository;
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

/**
 * O7 acceptance: two cashiers sharing one drawer and the same clock window must
 * each subtract only the refunds their own shift handed back, while a refund of
 * a sale from another shift still follows the cash out of the shift that paid it.
 */
@DataJpaTest
@ContextConfiguration(classes = ShiftRefundAttributionTest.JpaConfig.class)
class ShiftRefundAttributionTest {

    @SpringBootConfiguration
    @EnableJpaRepositories(basePackages = "com.bms.repository")
    @EntityScan(basePackages = "com.bms.entity")
    static class JpaConfig {
    }

    private static final LocalDateTime SHIFT_START = LocalDateTime.of(2026, 11, 12, 9, 0);
    private static final LocalDateTime SHIFT_END = LocalDateTime.of(2026, 11, 12, 17, 0);

    @Autowired private TestEntityManager em;
    @Autowired private SaleRepository saleRepository;

    private User cashier;

    @BeforeEach
    void seedUser() {
        Role role = new Role();
        role.setName(Role.RoleName.ROLE_CASHIER);
        em.persist(role);

        cashier = new User();
        cashier.setUsername("shift-refund-cashier");
        cashier.setEmail("shift-refund-cashier@example.test");
        cashier.setPassword("unused-fixture-password");
        cashier.setFirstName("Shift");
        cashier.setLastName("Cashier");
        cashier.setRole(role);
        em.persist(cashier);
        em.flush();
    }

    @Test
    void simultaneousShiftsEachSubtractOnlyTheirOwnRefunds() {
        CashShift left = shift();
        CashShift right = shift();

        refund(sale("O7-SOLD-1"), left, "20.00", SHIFT_START.plusHours(1));
        refund(sale("O7-SOLD-2"), right, "35.00", SHIFT_START.plusHours(1));
        refund(sale("O7-SOLD-3"), right, "15.00", SHIFT_START.plusHours(2));

        assertMoney("20.00", saleRepository.sumRefundsForShift(left.getId(), SHIFT_START, SHIFT_END));
        assertMoney("50.00", saleRepository.sumRefundsForShift(right.getId(), SHIFT_START, SHIFT_END));
        // The figure the legacy close used: the whole drawer, both shifts.
        assertMoney("70.00", saleRepository.sumRefundsBetween(SHIFT_START, SHIFT_END));
    }

    @Test
    void aRefundLeavesTheShiftThatPaidItNotTheShiftThatSoldIt() {
        CashShift morning = shift();
        CashShift afternoon = shift();

        Sale soldInMorning = sale("O7-MORNING-SALE");
        soldInMorning.setCashShiftId(morning.getId());
        soldInMorning.setEmployeeShift(morning);
        em.flush();

        refund(soldInMorning, afternoon, "18.00", SHIFT_START.plusHours(6));

        assertMoney("0.00", saleRepository.sumRefundsForShift(morning.getId(), SHIFT_START, SHIFT_END));
        assertMoney("18.00", saleRepository.sumRefundsForShift(afternoon.getId(), SHIFT_START, SHIFT_END));
    }

    @Test
    void anUnstampedLegacyRefundFallsBackToTheWindow() {
        CashShift shift = shift();

        refund(sale("O7-LEGACY-1"), null, "12.00", SHIFT_START.plusHours(3));
        refund(sale("O7-LEGACY-2"), null, "99.00", SHIFT_END.plusHours(1));

        assertMoney("12.00", saleRepository.sumRefundsForShift(shift.getId(), SHIFT_START, SHIFT_END));
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private CashShift shift() {
        CashShift shift = new CashShift();
        shift.setCashierId(cashier.getId());
        shift.setOpeningAmount(new BigDecimal("200.00"));
        shift.setOpeningTime(SHIFT_START);
        shift.setStatus("OPEN");
        return em.persistAndFlush(shift);
    }

    private Sale sale(String invoice) {
        Sale sale = new Sale();
        sale.setInvoiceNumber(invoice);
        sale.setCustomerDisplayName("Walk-in");
        sale.setCashierId(cashier.getId());
        sale.setSaleDate(SHIFT_START);
        sale.setSubtotal(new BigDecimal("100.00"));
        sale.setTaxAmount(BigDecimal.ZERO);
        sale.setTotalAmount(new BigDecimal("100.00"));
        sale.setAmountPaid(new BigDecimal("100.00"));
        sale.setSaleType(Sale.SaleType.CASH);
        sale.setPaymentMethod(Sale.PaymentMethod.CASH);
        sale.setPaymentStatus(Sale.PaymentStatus.PAID);
        sale.setIsVoided(false);
        return em.persistAndFlush(sale);
    }

    private SaleReturn refund(Sale sale, CashShift paidByShift, String amount, LocalDateTime at) {
        SaleReturn saleReturn = new SaleReturn();
        saleReturn.setSale(sale);
        saleReturn.setReturnedBy(cashier);
        saleReturn.setReturnDate(at);
        saleReturn.setReason("Fixture return");
        saleReturn.setRefundMethod(PaymentMethod.CASH);
        saleReturn.setTotalReturnAmount(new BigDecimal(amount));
        saleReturn.setDebtReductionAmount(BigDecimal.ZERO);
        saleReturn.setRefundAmount(new BigDecimal(amount));
        saleReturn.setEmployeeShift(paidByShift);
        return em.persistAndFlush(saleReturn);
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " but was " + actual);
    }
}
