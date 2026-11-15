package com.bms.service;

import com.bms.dto.request.PriceListRequest;
import com.bms.entity.Customer;
import com.bms.entity.PriceList;
import com.bms.entity.PriceListItem;
import com.bms.entity.Product;
import com.bms.exception.BusinessException;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.PriceListItemRepository;
import com.bms.repository.PriceListRepository;
import com.bms.repository.ProductRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@Transactional
public class PriceListService {

@Autowired
    private ShopTimeService shopTimeService = new ShopTimeService(null, "UTC");


    private final PriceListRepository priceListRepository;
    private final PriceListItemRepository priceListItemRepository;
    private final ProductRepository productRepository;

    public PriceListService(PriceListRepository priceListRepository,
                            PriceListItemRepository priceListItemRepository,
                            ProductRepository productRepository) {
        this.priceListRepository = priceListRepository;
        this.priceListItemRepository = priceListItemRepository;
        this.productRepository = productRepository;
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "priceLists", key = "'all'")
    public List<PriceList> getAll() {
        return priceListRepository.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "priceLists", key = "#id")
    public PriceList getById(Long id) {
        return priceListRepository.findWithItemsById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Price list not found: " + id));
    }

    @CacheEvict(cacheNames = "priceLists", allEntries = true)
    public PriceList create(PriceListRequest request) {
        if (priceListRepository.existsByNameIgnoreCase(request.getName().trim())) {
            throw new BusinessException("A price list with this name already exists");
        }
        PriceList priceList = new PriceList();
        apply(priceList, request);
        return priceListRepository.save(priceList);
    }

    @CacheEvict(cacheNames = "priceLists", allEntries = true)
    public PriceList update(Long id, PriceListRequest request) {
        PriceList priceList = priceListRepository.findWithItemsById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Price list not found: " + id));
        apply(priceList, request);
        return priceListRepository.save(priceList);
    }

    @CacheEvict(cacheNames = "priceLists", allEntries = true)
    public void delete(Long id) {
        PriceList priceList = priceListRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Price list not found: " + id));
        priceList.setIsActive(false);
        priceListRepository.save(priceList);
    }

    private void apply(PriceList priceList, PriceListRequest request) {
        priceList.setName(request.getName().trim());
        priceList.setDescription(request.getDescription());
        priceList.setValidFrom(request.getValidFrom());
        priceList.setValidTo(request.getValidTo());
        priceList.setIsActive(request.getActive() == null || request.getActive());

        if (request.getValidFrom() != null && request.getValidTo() != null
                && request.getValidTo().isBefore(request.getValidFrom())) {
            throw new BusinessException("Price list end date cannot be before its start date");
        }

        List<String> warnings = new ArrayList<>();
        priceList.getItems().clear();
        if (request.getItems() != null) {
            Set<Long> seen = new HashSet<>();
            for (PriceListRequest.Item itemRequest : request.getItems()) {
                if (!seen.add(itemRequest.getProductId())) {
                    throw new BusinessException("A product can only appear once in a price list");
                }
                Product product = productRepository.findById(itemRequest.getProductId())
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Product not found: " + itemRequest.getProductId()));
                if (itemRequest.getPrice() == null || itemRequest.getPrice().compareTo(BigDecimal.ZERO) < 0) {
                    throw new BusinessException("Price list prices cannot be negative");
                }
                // P10: warn (do not block) when a contract price falls below cost.
                if (product.getCostPrice() != null
                        && itemRequest.getPrice().compareTo(product.getCostPrice()) < 0) {
                    warnings.add(String.format("%s: price %s is below cost %s",
                            product.getName(),
                            itemRequest.getPrice().setScale(2, RoundingMode.HALF_UP),
                            product.getCostPrice().setScale(2, RoundingMode.HALF_UP)));
                }
                PriceListItem item = new PriceListItem();
                item.setPriceList(priceList);
                item.setProduct(product);
                item.setPrice(itemRequest.getPrice());
                priceList.getItems().add(item);
            }
        }
        priceList.setBelowCostWarnings(warnings);
    }

    /**
     * Contract price for a customer, or null when the customer has no active
     * list / the product is not on it / the list is outside its validity dates.
     */
    @Transactional(readOnly = true)
    public BigDecimal resolveCustomerPrice(Customer customer, Product product, LocalDate today) {
        if (customer == null || customer.getPriceList() == null || product == null) {
            return null;
        }
        PriceList list = customer.getPriceList();
        if (!Boolean.TRUE.equals(list.getIsActive())) {
            return null;
        }
        LocalDate date = today != null ? today : shopTimeService.today();
        if (list.getValidFrom() != null && date.isBefore(list.getValidFrom())) {
            return null;
        }
        if (list.getValidTo() != null && date.isAfter(list.getValidTo())) {
            return null;
        }
        return priceListItemRepository.findByPriceListIdAndProductId(list.getId(), product.getId())
                .map(PriceListItem::getPrice)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public PriceList resolveCustomerPriceList(Customer customer) {
        return customer != null ? customer.getPriceList() : null;
    }
}
