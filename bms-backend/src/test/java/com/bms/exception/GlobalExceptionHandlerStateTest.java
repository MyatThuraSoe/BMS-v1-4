package com.bms.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q3: only a refusal the shop can act on is allowed to explain itself.
 *
 * <p>A handler for {@code IllegalStateException} looked like a good idea until Spring,
 * Hibernate and the JDK turned out to throw it for their own faults as well - a closed
 * EntityManager, an already-registered singleton, a session that went away. Those words
 * describe this process, not the shop's data, and they were being sent to the browser as
 * a 409 that told the person at the counter the sale conflicted with itself.
 *
 * <p>So the refusals that were meant for the person reading the screen now say so in
 * their type, and everything else falls to the generic 500 with the generic message.
 */
class GlobalExceptionHandlerStateTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @BeforeEach
    void wireMessages() {
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasenames("i18n/messages");
        messages.setUseCodeAsDefaultMessage(true);
        ReflectionTestUtils.setField(handler, "messageSource", messages);
    }

    @Test
    void aRefusalMeantForTheShopKeepsItsMessageAndItsConflict() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                handler.handleStateConflict(new StateConflictException("Invoice was voided: INV-0001"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertTrue(response.getBody().getMessage().contains("INV-0001"),
                "the person at the counter has to be told which invoice: " + response.getBody().getMessage());
    }

    @Test
    void aServersOwnFaultIsNotExplainedToTheShop() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleGenericException(
                new IllegalStateException("secret detail: the EntityManager for shop 4 is closed"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertFalse(response.getBody().getMessage().contains("secret detail"),
                "internal text must not reach the browser: " + response.getBody().getMessage());
    }

    @Test
    void theRefusalTypeIsMappedAndIllegalStateIsNot() {
        List<Class<? extends Throwable>> mappedTo = handlerTypesMappedTo();

        assertTrue(mappedTo.contains(StateConflictException.class),
                "a refusal the shop acts on needs its own answer: " + mappedTo);
        assertFalse(mappedTo.contains(IllegalStateException.class),
                "the generic 500 must be the only answer left for it: " + mappedTo);
    }

    private List<Class<? extends Throwable>> handlerTypesMappedTo() {
        return Arrays.stream(GlobalExceptionHandler.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(ExceptionHandler.class))
                .flatMap(method -> Arrays.stream(method.getAnnotation(ExceptionHandler.class).value()))
                .toList();
    }
}
