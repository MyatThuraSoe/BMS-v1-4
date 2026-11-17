package com.bms.service;

import com.bms.dto.receipt.ReceiptDto;
import com.bms.exception.ResourceNotFoundException;
import com.bms.exception.StateConflictException;
import com.bms.repository.SystemSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Q9: the queue belongs to the printer, not to whoever asks for it.
 *
 * <p>A job that was handed to a print agent which then died stayed PRINTING forever, so the
 * receipt never printed and nothing said so. These tests pin the claim time: a claim that
 * goes quiet is handed out again twice, and after that it is written down as failed instead
 * of hanging. The book of job statuses is cleaned too, because it used to grow for the life
 * of the process.
 */
class CounterPrintQueueTest {

    private static final String PRINTER = "";

    private ReceiptService receiptService;
    private CounterPrintService service;
    private Instant now = Instant.parse("2026-11-17T04:00:00Z");

    @BeforeEach
    void buildTheQueue() {
        receiptService = mock(ReceiptService.class);
        when(receiptService.getReceiptByInvoiceNumber(anyString())).thenReturn(new ReceiptDto());
        service = new CounterPrintService(receiptService, mock(ShopInfoService.class),
                mock(ReceiptCustomizationService.class), mock(SystemSettingRepository.class));
        service.setClock(() -> now);
    }

    /** Move the clock past the point where a claim counts as abandoned. */
    private void letTheClaimGoQuiet() {
        now = now.plus(Duration.ofSeconds(61));
    }

    @Test
    void aJobIsHandedOutWithItsInvoiceAndPrinter() {
        String jobId = service.enqueueReceipt("INV-1").get("jobId");

        Map<String, String> claimed = service.claimNextReceipt();

        assertEquals(jobId, claimed.get("jobId"));
        assertEquals("INV-1", claimed.get("invoiceNumber"));
        assertEquals(PRINTER, claimed.get("printerName"));
        assertEquals("PRINTING", claimed.get("status"));
        assertEquals("PRINTING", service.jobStatus(jobId).get("status"));
    }

    @Test
    void anAbandonedClaimIsHandedOutAgainRatherThanLeftUnprinted() {
        String jobId = service.enqueueReceipt("INV-1").get("jobId");
        service.claimNextReceipt();
        letTheClaimGoQuiet();

        assertEquals(jobId, service.claimNextReceipt().get("jobId"),
                "the second agent to ask gets the receipt the first one never printed");
    }

    @Test
    void aReceiptRefusesToBeRetriedForever() {
        String jobId = service.enqueueReceipt("INV-1").get("jobId");
        for (int claim = 1; claim <= 3; claim++) {
            service.claimNextReceipt();
            letTheClaimGoQuiet();
        }

        assertEquals("EMPTY", service.claimNextReceipt().get("status"),
                "two retries is the most a dead agent can cost");
        assertEquals("FAILED", service.jobStatus(jobId).get("status"));
    }

    @Test
    void aJobThatReportedBackIsNotHandedOutAgain() {
        String jobId = service.enqueueReceipt("INV-1").get("jobId");
        service.claimNextReceipt();
        service.completeReceipt(jobId, true);
        letTheClaimGoQuiet();

        assertEquals("EMPTY", service.claimNextReceipt().get("status"));
        assertEquals("COMPLETED", service.jobStatus(jobId).get("status"));
    }

    @Test
    void aPrintedReceiptIsRememberedOnlyAsLongAsItCouldStillBeAskedAbout() {
        String old = service.enqueueReceipt("INV-1").get("jobId");
        service.claimNextReceipt();
        service.completeReceipt(old, true);

        now = now.plus(Duration.ofHours(2));
        String fresh = service.enqueueReceipt("INV-2").get("jobId");

        assertThrows(ResourceNotFoundException.class, () -> service.jobStatus(old),
                "the status book may not grow for the life of the process");
        assertEquals("QUEUED", service.jobStatus(fresh).get("status"));
    }

    @Test
    void anUnknownInvoiceIsRefusedBeforeItEverEntersTheQueue() {
        when(receiptService.getReceiptByInvoiceNumber("INV-404"))
                .thenThrow(new ResourceNotFoundException("Receipt not found for invoice: INV-404"));

        assertThrows(ResourceNotFoundException.class, () -> service.enqueueReceipt("INV-404"));
        assertEquals("EMPTY", service.claimNextReceipt().get("status"),
                "whoever pressed print is told now, not after the queue went quiet");
    }

    @Test
    void aVoidedInvoiceIsRefusedBeforeItEverEntersTheQueue() {
        when(receiptService.getReceiptByInvoiceNumber("INV-9"))
                .thenThrow(new StateConflictException("Invoice was voided: INV-9"));

        assertThrows(StateConflictException.class, () -> service.enqueueReceipt("INV-9"));
        assertEquals("EMPTY", service.claimNextReceipt().get("status"));
    }

    @Test
    void aJobThatWasNeverQueuedHasNoStatusToGive() {
        assertThrows(ResourceNotFoundException.class, () -> service.jobStatus("never-enqueued"));
    }
}
