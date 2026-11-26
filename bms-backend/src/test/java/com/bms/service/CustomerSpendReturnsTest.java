package com.bms.service;

import com.bms.entity.Customer;
import com.bms.entity.PaymentMethod;
import com.bms.entity.Role;
import com.bms.entity.Sale;
import com.bms.entity.SaleReturn;
import com.bms.entity.User;
import com.bms.repository.SaleRepository;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Defect 9: customer spend / LTV must net returns on CASH sales too.
 *
 * <p>{@link SaleReturnService} only copies the returned total onto
 * {@code Sale.amountReturned} for CREDIT invoices, while the spend queries in
 * {@link SaleRepository} subtract only that column. A returned cash sale is
 * therefore counted at its full gross value in every customer-spend figure.
 * The CREDIT case is included to pin the opposite requirement: its return is
 * already reflected in {@code amountReturned}, so the fix must not subtract
 * the SaleReturn row a second time.
 *
 * <p>Runs on an isolated in-memory H2 slice (no production data, no web context),
 * mirroring {@link ReportProfitAggregationIntegrationTest}.
 */
@DataJpaTest
@ContextConfiguration(classes = CustomerSpendReturnsTest.JpaConfig.class)
class CustomerSpendReturnsTest {

    @SpringBootConfiguration
    @EnableJpaRepositories(basePackages = "com.bms.repository")
    @EntityScan(basePackages = "com.bms.entity")
    static class JpaConfig {
    }

    @Autowired private TestEntityManager em;
    @Autowired private SaleRepository saleRepository;

    private static final BigDecimal SALE_TOTAL = new BigDecimal("100.00");
    private static final BigDecimal RETURN_TOTAL = new BigDecimal("40.00");
    private static final BigDecimal EXPECTED_NET = new BigDecimal("60.00");
    private static final LocalDateTime SALE_DATE = LocalDateTime.of(2026, 6, 15, 10, 30);

    @Test
    void cashSaleReturnIsNettedFromAllCustomerSpendFigures() {
        Customer customer = customer("D9-CASH");
        createSaleWithReturn(customer, "D9-INV-CASH", Sale.SaleType.CASH,
                BigDecimal.ZERO, PaymentMethod.CASH);

        BigDecimal sum = saleRepository.sumTotalAmountByCustomerIdAndDateAfter(
                customer.getId(), SALE_DATE.minusDays(1));
        BigDecimal daily = dailySpending(customer.getId(), SALE_DATE.getYear());
        BigDecimal ltv = lifetimeSpend(customer.getId());

        assertAll("cash-sale return must reduce customer spend",
                () -> assertEquals(EXPECTED_NET, sum,
                        "sumTotalAmountByCustomerIdAndDateAfter"),
                () -> assertEquals(EXPECTED_NET, daily,
                        "findDailySpendingByCustomerIdAndYear"),
                () -> assertEquals(EXPECTED_NET, ltv,
                        "findCustomerLtvData lifetime spend"));
    }

    @Test
    void creditSaleReturnIsNotDoubleSubtracted() {
        Customer customer = customer("D9-CREDIT");
        // SaleReturnService stores 40.00 in Sale.amountReturned for CREDIT sales.
        createSaleWithReturn(customer, "D9-INV-CREDIT", Sale.SaleType.CREDIT,
                RETURN_TOTAL, PaymentMethod.CREDIT);

        BigDecimal sum = saleRepository.sumTotalAmountByCustomerIdAndDateAfter(
                customer.getId(), SALE_DATE.minusDays(1));
        BigDecimal daily = dailySpending(customer.getId(), SALE_DATE.getYear());
        BigDecimal ltv = lifetimeSpend(customer.getId());

        assertAll("credit-sale return must be subtracted exactly once",
                () -> assertEquals(EXPECTED_NET, sum,
                        "sumTotalAmountByCustomerIdAndDateAfter"),
                () -> assertEquals(EXPECTED_NET, daily,
                        "findDailySpendingByCustomerIdAndYear"),
                () -> assertEquals(EXPECTED_NET, ltv,
                        "findCustomerLtvData lifetime spend"));
    }

    private Customer customer(String code) {
        Customer customer = new Customer();
        customer.setCustomerCode(code);
        customer.setFirstName("Spend");
        customer.setLastName(code);
        customer.setPhone("09-" + code);
        customer.setIsActive(true);
        return em.persistAndFlush(customer);
    }

    /** A 100.00 sale with a 40.00 SaleReturn; amountReturned is passed explicitly
     *  so the CASH fixture mirrors production (where SaleReturnService leaves it 0)
     *  and the CREDIT fixture mirrors the current credit-only update. */
    private void createSaleWithReturn(Customer customer, String invoice, Sale.SaleType saleType,
                                      BigDecimal amountReturned, PaymentMethod refundMethod) {
        Role role = new Role();
        role.setName(Role.RoleName.ROLE_ADMIN);
        role = em.persistAndFlush(role);

        User cashier = new User();
        cashier.setUsername(invoice + "-user");
        cashier.setEmail(invoice + "@example.test");
        cashier.setPassword("test-password");
        cashier.setFirstName("Test");
        cashier.setLastName("Cashier");
        cashier.setRole(role);
        cashier.setIsActive(true);
        cashier = em.persistAndFlush(cashier);

        Sale sale = new Sale();
        sale.setInvoiceNumber(invoice);
        sale.setCustomer(customer);
        sale.setCustomerDisplayName(customer.getFirstName() + " " + customer.getLastName());
        sale.setCashierId(cashier.getId());
        sale.setSaleDate(SALE_DATE);
        sale.setSubtotal(SALE_TOTAL);
        sale.setTaxAmount(BigDecimal.ZERO);
        sale.setTotalAmount(SALE_TOTAL);
        sale.setAmountPaid(saleType == Sale.SaleType.CREDIT ? BigDecimal.ZERO : SALE_TOTAL);
        sale.setAmountReturned(amountReturned);
        sale.setChangeGiven(BigDecimal.ZERO);
        sale.setPaymentMethod(saleType == Sale.SaleType.CREDIT
                ? Sale.PaymentMethod.CREDIT : Sale.PaymentMethod.CASH);
        sale.setSaleType(saleType);
        sale.setPaymentStatus(saleType == Sale.SaleType.CREDIT ? Sale.PaymentStatus.UNPAID : Sale.PaymentStatus.PAID);
        sale.setReturnStatus(Sale.ReturnStatus.PARTIALLY_RETURNED);
        sale.setIsVoided(false);
        sale.setIsActive(true);
        em.persistAndFlush(sale);

        SaleReturn saleReturn = new SaleReturn();
        saleReturn.setSale(sale);
        saleReturn.setReturnedBy(cashier);
        saleReturn.setReturnDate(SALE_DATE.plusMinutes(5));
        saleReturn.setReason("Defect 9 fixture");
        saleReturn.setTotalReturnAmount(RETURN_TOTAL);
        saleReturn.setDebtReductionAmount(saleType == Sale.SaleType.CREDIT ? RETURN_TOTAL : BigDecimal.ZERO);
        saleReturn.setRefundAmount(saleType == Sale.SaleType.CREDIT ? BigDecimal.ZERO : RETURN_TOTAL);
        saleReturn.setRefundMethod(refundMethod);
        em.persistAndFlush(saleReturn);
    }

    private BigDecimal dailySpending(Long customerId, int year) {
        List<Object[]> rows = saleRepository.findDailySpendingByCustomerIdAndYear(customerId, year);
        return rows.stream()
                .filter(row -> row[0] != null && row[0].toString().startsWith(SALE_DATE.toLocalDate().toString()))
                .findFirst()
                .map(row -> (BigDecimal) row[1])
                .orElse(null);
    }

    private BigDecimal lifetimeSpend(Long customerId) {
        return saleRepository.findCustomerLtvData().stream()
                .filter(row -> customerId.equals(row[0]))
                .findFirst()
                .map(row -> (BigDecimal) row[5])
                .orElse(null);
    }
}
