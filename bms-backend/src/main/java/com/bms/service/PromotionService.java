package com.bms.service;

import com.bms.dto.request.PromotionRequest;
import com.bms.entity.Customer;
import com.bms.entity.Product;
import com.bms.entity.Promotion;
import com.bms.entity.PromotionTier;
import com.bms.exception.BusinessException;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.CategoryRepository;
import com.bms.repository.ProductRepository;
import com.bms.repository.PromotionRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@Transactional
public class PromotionService {

    private final PromotionRepository promotionRepository;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final PriceListService priceListService;

    public PromotionService(PromotionRepository promotionRepository,
                            ProductRepository productRepository,
                            CategoryRepository categoryRepository,
                            PriceListService priceListService) {
        this.promotionRepository = promotionRepository;
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.priceListService = priceListService;
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "promotions", key = "'all'")
    public List<Promotion> getAll() {
        return promotionRepository.findAllByOrderByPriorityDesc();
    }

    @Transactional(readOnly = true)
    public Promotion getById(Long id) {
        return promotionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Promotion not found: " + id));
    }

    @CacheEvict(cacheNames = "promotions", allEntries = true)
    public Promotion create(PromotionRequest request) {
        Promotion promotion = new Promotion();
        apply(promotion, request);
        return promotionRepository.save(promotion);
    }

    @CacheEvict(cacheNames = "promotions", allEntries = true)
    public Promotion update(Long id, PromotionRequest request) {
        Promotion promotion = promotionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Promotion not found: " + id));
        apply(promotion, request);
        return promotionRepository.save(promotion);
    }

    @CacheEvict(cacheNames = "promotions", allEntries = true)
    public void delete(Long id) {
        Promotion promotion = promotionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Promotion not found: " + id));
        promotion.setIsActive(false);
        promotionRepository.save(promotion);
    }

    private void apply(Promotion promotion, PromotionRequest request) {
        promotion.setName(request.getName().trim());
        promotion.setDescription(request.getDescription());
        promotion.setType(parseType(request.getType()));
        promotion.setScope(parseScope(request.getScope()));
        promotion.setProduct(null);
        promotion.setCategory(null);
        if (promotion.getScope() == Promotion.PromotionScope.PRODUCT) {
            if (request.getProductId() == null) {
                throw new BusinessException("Product promotions require a product");
            }
            promotion.setProduct(productRepository.findById(request.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Product not found: " + request.getProductId())));
        } else if (promotion.getScope() == Promotion.PromotionScope.CATEGORY) {
            if (request.getCategoryId() == null) {
                throw new BusinessException("Category promotions require a category");
            }
            promotion.setCategory(categoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Category not found: " + request.getCategoryId())));
        }

        promotion.setBuyQuantity(request.getBuyQuantity());
        promotion.setGetQuantity(request.getGetQuantity());
        promotion.setMinQuantity(request.getMinQuantity() != null ? request.getMinQuantity() : 1);
        promotion.setDiscountValue(request.getDiscountValue() != null
                ? request.getDiscountValue() : BigDecimal.ZERO);
        promotion.setStartDate(request.getStartDate());
        promotion.setEndDate(request.getEndDate());
        promotion.setPriority(request.getPriority() != null ? request.getPriority() : 0);
        promotion.setIsActive(request.getActive() == null || request.getActive());

        if (promotion.getStartDate() != null && promotion.getEndDate() != null
                && promotion.getEndDate().isBefore(promotion.getStartDate())) {
            throw new BusinessException("Promotion end date cannot be before its start date");
        }
        if (promotion.getType() == Promotion.PromotionType.BOGO
                && (promotion.getBuyQuantity() == null || promotion.getBuyQuantity() <= 0
                || promotion.getGetQuantity() == null || promotion.getGetQuantity() <= 0)) {
            throw new BusinessException("BOGO promotions require positive buy and get quantities");
        }

        promotion.getTiers().clear();
        if (request.getTiers() != null) {
            for (PromotionRequest.Tier tierRequest : request.getTiers()) {
                if (tierRequest.getMinQuantity() == null || tierRequest.getDiscountPercent() == null) {
                    throw new BusinessException("Every tier needs a minimum quantity and discount percent");
                }
                PromotionTier tier = new PromotionTier();
                tier.setPromotion(promotion);
                tier.setMinQuantity(tierRequest.getMinQuantity());
                tier.setDiscountPercent(tierRequest.getDiscountPercent());
                promotion.getTiers().add(tier);
            }
        }
        if (promotion.getType() == Promotion.PromotionType.TIERED && promotion.getTiers().isEmpty()) {
            throw new BusinessException("Tiered promotions require at least one tier");
        }
    }

    private Promotion.PromotionType parseType(String type) {
        try {
            return Promotion.PromotionType.valueOf(type.trim().toUpperCase());
        } catch (Exception ex) {
            throw new BusinessException("Invalid promotion type: " + type);
        }
    }

    private Promotion.PromotionScope parseScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return Promotion.PromotionScope.ALL;
        }
        try {
            return Promotion.PromotionScope.valueOf(scope.trim().toUpperCase());
        } catch (Exception ex) {
            throw new BusinessException("Invalid promotion scope: " + scope);
        }
    }

    /**
     * Returns the cheapest applicable price for one cart line. Promotions and
     * customer price lists both compete; the lowest unit price wins (ties go to
     * the contract price list, then to the higher-priority promotion).
     */
    @Transactional(readOnly = true)
    public PricingQuote calculateBestPrice(Product product, int quantity,
                                           Customer customer, LocalDateTime at) {
        BigDecimal original = product.getUnitPrice() != null
                ? product.getUnitPrice() : BigDecimal.ZERO;
        LocalDateTime moment = at != null ? at : LocalDateTime.now();

        BigDecimal priceListPrice = priceListService.resolveCustomerPrice(
                customer, product, LocalDate.now());
        Long priceListId = null;
        String priceListName = null;
        if (customer != null && customer.getPriceList() != null) {
            priceListId = customer.getPriceList().getId();
            priceListName = customer.getPriceList().getName();
        }

        PromotionCandidate best = null;
        for (Promotion promotion : promotionRepository.findActiveAt(moment)) {
            if (!appliesTo(promotion, product)) {
                continue;
            }
            if (quantity < promotion.getMinQuantity()) {
                continue;
            }
            BigDecimal candidatePrice = priceForPromotion(promotion, original, quantity);
            if (candidatePrice == null) {
                continue;
            }
            if (candidatePrice.compareTo(original) > 0) {
                candidatePrice = original;
            }
            if (candidatePrice.compareTo(BigDecimal.ZERO) < 0) {
                candidatePrice = BigDecimal.ZERO;
            }
            if (best == null
                    || candidatePrice.compareTo(best.price) < 0
                    || (candidatePrice.compareTo(best.price) == 0
                        && promotion.getPriority() > best.priority)) {
                best = new PromotionCandidate(promotion, candidatePrice);
            }
        }

        boolean usePriceList = priceListPrice != null
                && priceListPrice.compareTo(BigDecimal.ZERO) >= 0
                && (best == null || priceListPrice.compareTo(best.price) <= 0);
        if (usePriceList) {
            BigDecimal effective = priceListPrice.min(original);
            return quote(original, effective, quantity, null, null,
                    priceListId, priceListName, "PRICE_LIST");
        }
        if (best != null) {
            return quote(original, best.price, quantity, best.promotion.getId(),
                    best.promotion.getName(), null, null, "PROMOTION");
        }
        return quote(original, original, quantity, null, null, null, null, "REGULAR");
    }

    private PricingQuote quote(BigDecimal original, BigDecimal effective, int quantity,
                               Long promotionId, String promotionName,
                               Long priceListId, String priceListName, String source) {
        BigDecimal discount = original.subtract(effective)
                .multiply(BigDecimal.valueOf(quantity))
                .setScale(2, RoundingMode.HALF_UP);
        if (discount.compareTo(BigDecimal.ZERO) < 0) {
            discount = BigDecimal.ZERO;
        }
        return new PricingQuote(original, effective, discount, promotionId, promotionName,
                priceListId, priceListName, source);
    }

    private boolean appliesTo(Promotion promotion, Product product) {
        switch (promotion.getScope()) {
            case PRODUCT:
                return promotion.getProduct() != null
                        && promotion.getProduct().getId().equals(product.getId());
            case CATEGORY:
                return promotion.getCategory() != null
                        && product.getCategory() != null
                        && promotion.getCategory().getId().equals(product.getCategory().getId());
            case ALL:
            default:
                return true;
        }
    }

    private BigDecimal priceForPromotion(Promotion promotion, BigDecimal original, int quantity) {
        switch (promotion.getType()) {
            case PERCENT_DISCOUNT:
            case FLASH_SALE:
                return percentOff(original, promotion.getDiscountValue());
            case FIXED_DISCOUNT:
                return original.subtract(promotion.getDiscountValue());
            case BOGO:
                int buy = promotion.getBuyQuantity() != null ? promotion.getBuyQuantity() : 0;
                int get = promotion.getGetQuantity() != null ? promotion.getGetQuantity() : 0;
                if (buy <= 0 || get <= 0) {
                    return null;
                }
                int groupSize = buy + get;
                int groups = quantity / groupSize;
                if (groups <= 0) {
                    return null;
                }
                int freeUnits = groups * get;
                int chargedUnits = quantity - freeUnits;
                return original.multiply(BigDecimal.valueOf(chargedUnits))
                        .divide(BigDecimal.valueOf(quantity), 4, RoundingMode.HALF_UP);
            case TIERED:
                BigDecimal percent = null;
                for (PromotionTier tier : promotion.getTiers()) {
                    if (tier.getMinQuantity() != null && quantity >= tier.getMinQuantity()
                            && (percent == null || tier.getDiscountPercent().compareTo(percent) > 0)) {
                        percent = tier.getDiscountPercent();
                    }
                }
                return percent != null ? percentOff(original, percent) : null;
            default:
                return null;
        }
    }

    private BigDecimal percentOff(BigDecimal price, BigDecimal percent) {
        if (percent == null || percent.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        BigDecimal pct = percent.min(BigDecimal.valueOf(100));
        return price.multiply(BigDecimal.valueOf(100).subtract(pct))
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
    }

    private static class PromotionCandidate {
        private final Promotion promotion;
        private final BigDecimal price;
        private final int priority;

        private PromotionCandidate(Promotion promotion, BigDecimal price) {
            this.promotion = promotion;
            this.price = price;
            this.priority = promotion.getPriority();
        }
    }
}
