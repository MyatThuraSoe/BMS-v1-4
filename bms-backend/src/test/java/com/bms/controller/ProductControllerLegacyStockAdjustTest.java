package com.bms.controller;

import com.bms.service.ProductService;
import com.bms.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** S4: the legacy stock-adjust route must still work without a reason code. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductControllerLegacyStockAdjustTest {

    @Mock
    private ProductService productService;

    @Mock
    private UserService userService;

    @InjectMocks
    private ProductController productController;

    @Mock
    private Authentication authentication;

    @Mock
    private UserDetails userDetails;

    private void signedIn() {
        when(authentication.getPrincipal()).thenReturn(userDetails);
        when(userDetails.getUsername()).thenReturn("manager");
        com.bms.entity.User manager = new com.bms.entity.User();
        manager.setId(3L);
        when(userService.findByUsername("manager")).thenReturn(manager);
    }

    @Test
    void missingReasonCodeDefaultsToCountCorrection() {
        signedIn();

        productController.adjustStock(7L, -3, "Broken in store", false, null, null, authentication);

        verify(productService).adjustStock(3L, 7L, -3, "Broken in store",
                "COUNT_CORRECTION", false, null);
    }

    @Test
    void suppliedReasonCodeIsPassedThrough() {
        signedIn();

        productController.adjustStock(7L, 5, "Supplier sent extra", false, null,
                "SUPPLIER_ERROR", authentication);

        verify(productService).adjustStock(3L, 7L, 5, "Supplier sent extra",
                "SUPPLIER_ERROR", false, null);
    }
}
