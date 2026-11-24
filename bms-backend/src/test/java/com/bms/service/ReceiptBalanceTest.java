package com.bms.service;

import com.bms.dto.receipt.ArPaymentReceiptDto;
import com.bms.dto.receipt.ReceiptDto;
import com.bms.entity.ArPayment;
import com.bms.entity.Sale;
import com.bms.entity.SaleReturn;
import com.bms.exception.StateConflictException;
import com.bms.repository.ArPaymentRepository;
import com.bms.repository.SaleRepository;
import com.bms.repository.SaleReturnRepository;
import com.bms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The balance a customer is shown has one meaning: what is still owed on this
 * invoice after what was paid AND after the goods that came back.
 *
 * <p>The AR screens already subtract {@code amountReturned}; the receipts did
 * not, so the same invoice printed two different numbers. A reprinted
 * collection receipt has a second rule: it must show the balance as it stood
 * right after that collection, not today's.
 */
class ReceiptBalanceTest {

    private static final String INVOICE = "INV-260101-001";
    private static final long INVOICE_ID = 1L;

    private SaleRepository saleRepository;
    private ArPaymentRepository arPaymentRepository;
    private SaleReturnRepository saleReturnRepository;
    private ReceiptService receiptService;

    @BeforeEach
    void wire() {
        saleRepository = mock(SaleRepository.class);
        arPaymentRepository = mock(ArPaymentRepository.class);
        saleReturnRepository = mock(SaleReturnRepository.class);
        receiptService = new ReceiptService(saleRepository, mock(UserRepository.class));
        ReflectionTestUtils.setField(receiptService, "arPaymentRepository", arPaymentRepository);
        ReflectionTestUtils.setField(receiptService, "saleReturnRepository", saleReturnRepository);
    }

    /** A credit invoice of {@code total} with {@code paid} collected and {@code returned} handed back. */
    private static Sale invoice(String total, String paid, String returned) {
        Sale sale = new Sale();
        sale.setId(INVOICE_ID);
        sale.setInvoiceNumber(INVOICE);
        sale.setSaleDate(LocalDateTime.of(2026, 1, 1, 10, 0));
        sale.setSubtotal(new BigDecimal(total));
        sale.setTotalAmount(new BigDecimal(total));
        sale.setAmountPaid(new BigDecimal(paid));
        sale.setAmountReturned(new BigDecimal(returned));
        sale.setPaymentMethod(Sale.PaymentMethod.CREDIT);
        sale.setSaleType(Sale.SaleType.CREDIT);
        sale.setPaymentStatus(Sale.PaymentStatus.PARTIAL);
        sale.setItems(new ArrayList<>());
        return sale;
    }

    private static ArPayment collection(long paymentId, String amount, Sale invoice) {
        ArPayment payment = new ArPayment();
        payment.setId(paymentId);
        payment.setAmount(new BigDecimal(amount));
        payment.setInvoice(invoice);
        payment.setPaymentDate(LocalDateTime.of(2026, 1, 2, 9, 0));
        return payment;
    }

    private void receiptOf(Sale sale) {
        when(saleRepository.findByInvoiceNumber(INVOICE)).thenReturn(Optional.of(sale));
    }

    private void paymentIs(ArPayment payment) {
        when(arPaymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
    }

    /** Everything collected on this invoice after the given row, which the service asks the ledger for. */
    private void laterCollectionsOf(long paymentId, String amount) {
        when(arPaymentRepository.sumAmountByInvoiceIdAndIdGreaterThanExcludingReversed(INVOICE_ID, paymentId))
                .thenReturn(new BigDecimal(amount));
        when(arPaymentRepository.sumAmountByInvoiceIdAndPaymentDateAfterExcludingReversed(
                anyLong(), any(LocalDateTime.class)))
                .thenReturn(new BigDecimal(amount));
    }

    @Test
    void aReturnReducesTheBalanceDueOnTheReceipt() {
        Sale sale = invoice("100.00", "30.00", "20.00");
        receiptOf(sale);

        ReceiptDto receipt = receiptService.getReceiptByInvoiceNumber(INVOICE);

        assertEquals(new BigDecimal("50.00"), receipt.getBalanceDue(),
                "100 owed, 30 paid and 20 handed back is still 50 due");
        assertEquals(new BigDecimal("20.00"), receipt.getAmountReturned());
    }

    @Test
    void anInvoiceNobodyReturnedReportsNoReturns() {
        Sale sale = invoice("100.00", "30.00", "0.00");
        receiptOf(sale);

        ReceiptDto receipt = receiptService.getReceiptByInvoiceNumber(INVOICE);

        assertEquals(new BigDecimal("0.00"), receipt.getAmountReturned());
        assertEquals(new BigDecimal("70.00"), receipt.getBalanceDue());
    }

    @Test
    void aSettledInvoiceNeverPrintsANegativeBalance() {
        Sale sale = invoice("100.00", "100.00", "10.00");
        receiptOf(sale);

        assertEquals(new BigDecimal("0.00"), receiptService.getReceiptByInvoiceNumber(INVOICE).getBalanceDue(),
                "the shop owes the customer that 10, it is not a negative debt");
    }

    @Test
    void theReceiptLookedUpByIdAppliesTheSameArithmetic() {
        Sale sale = invoice("100.00", "30.00", "20.00");
        when(saleRepository.findById(INVOICE_ID)).thenReturn(Optional.of(sale));

        ReceiptDto receipt = receiptService.getReceiptById(INVOICE_ID);

        assertEquals(new BigDecimal("50.00"), receipt.getBalanceDue());
        assertEquals(new BigDecimal("20.00"), receipt.getAmountReturned());
    }

    @Test
    void reprintingACollectionShowsTheBalanceOfThatDay() {
        Sale sale = invoice("80.00", "50.00", "0.00");
        paymentIs(collection(11L, "30.00", sale));
        laterCollectionsOf(11L, "20.00");

        ArPaymentReceiptDto first = receiptService.getArPaymentReceipt(11L);

        assertEquals(new BigDecimal("50.00"), first.getBalanceAfter(),
                "30 of the 80 was paid when this receipt was printed; the next 20 came later");
    }

    @Test
    void theLastCollectionShowsTheBalanceThatStandsToday() {
        Sale sale = invoice("80.00", "50.00", "0.00");
        paymentIs(collection(12L, "20.00", sale));
        laterCollectionsOf(12L, "0.00");

        assertEquals(new BigDecimal("30.00"), receiptService.getArPaymentReceipt(12L).getBalanceAfter());
    }

    @Test
    void anOldCollectionReceiptAlsoSubtractsWhatWasReturned() {
        Sale sale = invoice("80.00", "50.00", "10.00");
        paymentIs(collection(11L, "30.00", sale));
        laterCollectionsOf(11L, "20.00");
        SaleReturn sr = new SaleReturn();
        sr.setId(1L);
        sr.setSale(sale);
        sr.setReturnDate(LocalDateTime.of(2026, 1, 1, 11, 0));
        sr.setTotalReturnAmount(new BigDecimal("10.00"));
        when(saleReturnRepository.findBySaleIdOrderByReturnDateDesc(INVOICE_ID))
                .thenReturn(java.util.List.of(sr));

        ArPaymentReceiptDto first = receiptService.getArPaymentReceipt(11L);

        assertEquals(new BigDecimal("40.00"), first.getBalanceAfter());
        assertEquals(new BigDecimal("10.00"), first.getAmountReturned());
    }

    @Test
    void aVoidedInvoiceHasNoCollectionReceipt() {
        Sale sale = invoice("80.00", "30.00", "0.00");
        sale.setIsVoided(true);
        paymentIs(collection(11L, "30.00", sale));

        StateConflictException thrown = assertThrows(StateConflictException.class,
                () -> receiptService.getArPaymentReceipt(11L));

        assertEquals("Invoice was voided: " + INVOICE, thrown.getMessage());
    }

    @Test
    void aVoidedInvoiceHasNoSaleReceiptEither() {
        Sale sale = invoice("80.00", "30.00", "0.00");
        sale.setIsVoided(true);
        receiptOf(sale);

        StateConflictException thrown = assertThrows(StateConflictException.class,
                () -> receiptService.getReceiptByInvoiceNumber(INVOICE));

        assertEquals("Invoice was voided: " + INVOICE, thrown.getMessage());
    }
}
