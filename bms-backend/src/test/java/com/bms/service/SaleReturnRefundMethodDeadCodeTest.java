package com.bms.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit T6-15: once an explicit method has been returned, a blank method can
 * only reach the "choose a refund method" throw. Keep the fallback branch out
 * of the source so the real behaviour is unambiguous.
 */
class SaleReturnRefundMethodDeadCodeTest {

    @Test
    void mixedPaymentFallbackAfterTheChooseMethodThrowIsRemoved() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/bms/service/SaleReturnService.java"));

        int throwAt = source.indexOf("throw new BusinessException(\"Choose a refund method\")");
        int fallbackAt = source.indexOf("return methods.contains(PaymentMethod.CASH)");
        assertTrue(throwAt > 0, "the mixed-payment sale must still demand an explicit method");
        assertFalse(fallbackAt > throwAt,
                "the fallback after the unconditional throw is unreachable and must be deleted");
    }
}
