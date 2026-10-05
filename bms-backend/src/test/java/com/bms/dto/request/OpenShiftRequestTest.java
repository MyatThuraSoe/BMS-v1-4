package com.bms.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cashier must be able to open a shift with an empty drawer.
 *
 * <p>The opening amount is the cash counted at the start of the shift, and
 * starting the day with nothing in the till is an ordinary situation: the float
 * has not been collected yet, or the previous shift closed at zero. Rejecting
 * that forced the cashier to invent a number, which silently corrupted the
 * expected-drawer figure used to detect a shortage or overage at closing time.
 *
 * <p>The original rule was {@code @Positive}, which by definition excludes
 * zero. The sibling {@link CloseShiftRequest} already allowed zero, so opening
 * was the stricter of the two halves of the same feature.
 *
 * <p>Zero and negative are asserted separately: the useful rule is "zero or
 * more", so allowing zero must not have loosened the negative side.
 */
class OpenShiftRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        if (factory != null) {
            factory.close();
        }
    }

    private static OpenShiftRequest requestWith(String amount) {
        OpenShiftRequest request = new OpenShiftRequest();
        if (amount != null) {
            request.setOpeningAmount(new BigDecimal(amount));
        }
        return request;
    }

    private static Set<ConstraintViolation<OpenShiftRequest>> validate(String amount) {
        return validator.validate(requestWith(amount));
    }

    @Test
    void zeroOpeningAmountIsAllowed() {
        assertTrue(validate("0").isEmpty(),
                "an empty drawer must be a valid way to open a shift");
    }

    @Test
    void zeroWithCurrencyScaleIsAllowed() {
        assertTrue(validate("0.00").isEmpty(),
                "zero sent with currency scale must not be treated as different from zero");
    }

    @Test
    void positiveOpeningAmountIsAllowed() {
        assertTrue(validate("1500.50").isEmpty(),
                "a counted float must remain valid");
    }

    @Test
    void negativeOpeningAmountIsRejected() {
        Set<ConstraintViolation<OpenShiftRequest>> violations = validate("-0.01");

        assertEquals(1, violations.size(),
                "only the sign rule should fire, not some unrelated constraint");
        assertEquals("Opening amount must be zero or positive",
                violations.iterator().next().getMessage());
    }

    @Test
    void largeNegativeOpeningAmountIsRejected() {
        assertEquals(1, validate("-500").size(),
                "a large negative amount must still be rejected");
    }

    @Test
    void missingOpeningAmountIsRejected() {
        Set<ConstraintViolation<OpenShiftRequest>> violations = validate(null);

        assertEquals(1, violations.size());
        assertEquals("Opening amount is required",
                violations.iterator().next().getMessage());
    }
}