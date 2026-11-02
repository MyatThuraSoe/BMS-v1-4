package com.bms.service;

import com.bms.entity.Customer;
import com.bms.entity.PriceList;
import com.bms.entity.Product;
import com.bms.entity.Promotion;
import com.bms.repository.CategoryRepository;
import com.bms.repository.ProductRepository;
import com.bms.repository.PromotionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * The pricing engine must always hand the POS the cheapest legal price.
 * Promotions compete with customer price lists; price-list wins ties because
 * it is a negotiated contract, and the higher-priority promotion wins a
 * promotion-only tie.
 */
@ExtendWith(MockitoExtension.class)
class PromotionServicePricingTest {

    @Mock
    private PromotionRepository promotionRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private PriceListService priceListService;

    private PromotionService promotionService;

    @BeforeEach
    void setUp() {
        promotionService = new PromotionService(promotionRepository, productRepository,
                categoryRepository, priceListService);
    }

    private Product product(String price) {
        Product product = new Product();
        product.setId(7L);
        product.setName("Milk 1L");
        product.setSku("MILK-1L");
        product.setUnitPrice(new BigDecimal(price));
        return product;
    }

    private Customer customerWithPriceList() {
        PriceList priceList = new PriceList();
        priceList.setId(3L);
        priceList.setName("Wholesale");
        Customer customer = new Customer();
        customer.setId(11L);
        customer.setPriceList(priceList);
        return customer;
    }

    private Promotion promotion(long id, String name, String percent, int priority) {
        Promotion promotion = new Promotion();
        promotion.setId(id);
        promotion.setName(name);
        promotion.setType(Promotion.PromotionType.PERCENT_DISCOUNT);
        promotion.setScope(Promotion.PromotionScope.ALL);
        promotion.setDiscountValue(new BigDecimal(percent));
        promotion.setMinQuantity(1);
        promotion.setPriority(priority);
        promotion.setIsActive(true);
        return promotion;
    }

    @Test
    void cheaperPromotionBeatsTheCustomerPriceList() {
        Product product = product("100.00");
        Customer customer = customerWithPriceList();
        when(priceListService.resolveCustomerPrice(eq(customer), eq(product), any(LocalDate.class)))
                .thenReturn(new BigDecimal("80.00"));
        when(promotionRepository.findActiveAt(any(LocalDateTime.class)))
                .thenReturn(List.of(promotion(1L, "25% off", "25", 0)));

        PricingQuote quote = promotionService.calculateBestPrice(product, 2, customer,
                LocalDateTime.of(2026, 1, 1, 12, 0));

        assertEquals("PROMOTION", quote.getSource());
        assertEquals(0, quote.getUnitPrice().compareTo(new BigDecimal("75.00")));
        // (100 - 75) * 2
        assertEquals(0, quote.getLineDiscount().compareTo(new BigDecimal("50.00")));
        assertEquals(1L, quote.getPromotionId());
        assertNull(quote.getPriceListId());
    }

    @Test
    void priceListWinsTiesAgainstAnEquallyCheapPromotion() {
        Product product = product("100.00");
        Customer customer = customerWithPriceList();
        when(priceListService.resolveCustomerPrice(eq(customer), eq(product), any(LocalDate.class)))
                .thenReturn(new BigDecimal("80.00"));
        when(promotionRepository.findActiveAt(any(LocalDateTime.class)))
                .thenReturn(List.of(promotion(1L, "20% off", "20", 0)));

        PricingQuote quote = promotionService.calculateBestPrice(product, 1, customer,
                LocalDateTime.of(2026, 1, 1, 12, 0));

        assertEquals("PRICE_LIST", quote.getSource());
        assertEquals(0, quote.getUnitPrice().compareTo(new BigDecimal("80.00")));
        assertEquals(3L, quote.getPriceListId());
        assertEquals("Wholesale", quote.getPriceListName());
        assertNull(quote.getPromotionId());
    }

    @Test
    void higherPriorityPromotionWinsAnAbsoluteTie() {
        Product product = product("100.00");
        when(priceListService.resolveCustomerPrice(any(), any(), any(LocalDate.class)))
                .thenReturn(null);
        // Repository returns them in priority order, but the tie-break must
        // still pick the higher priority when both give the same price.
        when(promotionRepository.findActiveAt(any(LocalDateTime.class)))
                .thenReturn(List.of(
                        promotion(1L, "Low priority", "10", 1),
                        promotion(2L, "High priority", "10", 5)));

        PricingQuote quote = promotionService.calculateBestPrice(product, 1, null,
                LocalDateTime.of(2026, 1, 1, 12, 0));

        assertEquals("PROMOTION", quote.getSource());
        assertEquals(2L, quote.getPromotionId());
        assertEquals(0, quote.getUnitPrice().compareTo(new BigDecimal("90.00")));
    }

    @Test
    void noDiscountsFallsBackToRegularPrice() {
        Product product = product("100.00");
        lenient().when(priceListService.resolveCustomerPrice(any(), any(), any(LocalDate.class)))
                .thenReturn(null);
        when(promotionRepository.findActiveAt(any(LocalDateTime.class))).thenReturn(List.of());

        PricingQuote quote = promotionService.calculateBestPrice(product, 1, null,
                LocalDateTime.of(2026, 1, 1, 12, 0));

        assertEquals("REGULAR", quote.getSource());
        assertEquals(0, quote.getUnitPrice().compareTo(new BigDecimal("100.00")));
        assertEquals(0, quote.getLineDiscount().compareTo(BigDecimal.ZERO));
    }
}
