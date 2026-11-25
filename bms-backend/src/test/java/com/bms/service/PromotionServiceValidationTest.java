package com.bms.service;

import com.bms.dto.request.PromotionRequest;
import com.bms.entity.Promotion;
import com.bms.entity.Product;
import com.bms.exception.BusinessException;
import com.bms.repository.CategoryRepository;
import com.bms.repository.PromotionRepository;
import com.bms.repository.ProductRepository;
import com.bms.service.PriceListService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * Defect 6: a promotion must not carry a negative discount value, a percent
 * above 100, or a tier percent above 100 (which would price below zero / mint
 * money).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PromotionServiceValidationTest {

    @Mock private PromotionRepository promotionRepository;
    @Mock private ProductRepository productRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private PriceListService priceListService;

    private PromotionService promotionService;

    @BeforeEach
    void setUp() {
        promotionService = new PromotionService(promotionRepository, productRepository,
                categoryRepository, priceListService);
        lenient().when(promotionRepository.save(any(Promotion.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(productRepository.findByIsActiveTrueAndDeletedAtIsNullOrderByNameAsc())
                .thenReturn(List.of());
    }

    @Test
    void createRejectsNegativeDiscountValue() {
        PromotionServiceValidationTest.PromotionRequestBuilder b = request();
        b.setDiscountValue(new BigDecimal("-5.00"));

        assertThrows(BusinessException.class, () -> promotionService.create(b.build()));
    }

    @Test
    void createRejectsPercentAbove100() {
        PromotionServiceValidationTest.PromotionRequestBuilder b = request();
        b.setType("PERCENT_DISCOUNT");
        b.setDiscountValue(new BigDecimal("150.00"));

        assertThrows(BusinessException.class, () -> promotionService.create(b.build()));
    }

    @Test
    void createRejectsFlashSalePercentAbove100() {
        PromotionServiceValidationTest.PromotionRequestBuilder b = request();
        b.setType("FLASH_SALE");
        b.setDiscountValue(new BigDecimal("120.00"));

        assertThrows(BusinessException.class, () -> promotionService.create(b.build()));
    }

    @Test
    void createRejectsTierPercentAbove100() {
        PromotionServiceValidationTest.PromotionRequestBuilder b = request();
        b.setType("TIERED");
        PromotionRequest.Tier tier = new PromotionRequest.Tier();
        tier.setMinQuantity(2);
        tier.setDiscountPercent(new BigDecimal("150.00"));
        b.setTiers(List.of(tier));

        assertThrows(BusinessException.class, () -> promotionService.create(b.build()));
    }

    @Test
    void createAcceptsValidPercentPromotion() {
        PromotionServiceValidationTest.PromotionRequestBuilder b = request();
        b.setType("PERCENT_DISCOUNT");
        b.setDiscountValue(new BigDecimal("50.00"));

        promotionService.create(b.build()); // must not throw
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private PromotionRequestBuilder request() {
        return new PromotionRequestBuilder();
    }

    private class PromotionRequestBuilder {
        private final PromotionRequest r = new PromotionRequest();

        PromotionRequestBuilder() {
            r.setName("Test promo");
            r.setDescription("d");
            r.setType("FIXED_DISCOUNT");
            r.setScope("ALL");
            r.setDiscountValue(BigDecimal.ZERO);
            r.setStartDate(LocalDateTime.now());
            r.setEndDate(LocalDateTime.now().plusDays(30));
            r.setPriority(0);
            r.setActive(true);
            r.setBuyQuantity(null);
            r.setGetQuantity(null);
            r.setMinQuantity(1);
            r.setTiers(null);
        }

        PromotionRequestBuilder setDiscountValue(BigDecimal v) {
            r.setDiscountValue(v);
            return this;
        }

        PromotionRequestBuilder setType(String t) {
            r.setType(t);
            return this;
        }

        PromotionRequestBuilder setTiers(List<PromotionRequest.Tier> tiers) {
            r.setTiers(tiers);
            return this;
        }

        PromotionRequest build() {
            return r;
        }
    }
}
