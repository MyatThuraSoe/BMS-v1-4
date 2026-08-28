package com.bms.service;

import com.bms.dto.draft.DraftCreateRequest;
import com.bms.dto.draft.DraftResponse;
import com.bms.entity.Draft;
import com.bms.entity.DraftItem;
import com.bms.entity.Product;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.DraftRepository;
import com.bms.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DraftService {
    private final DraftRepository draftRepository;
    private final ProductRepository productRepository;

    @Transactional
    public DraftResponse create(DraftCreateRequest request, Long cashierId) {
        Draft draft = new Draft();
        draft.setCashierId(cashierId);
        draft.setCustomerId(request.getCustomerId());
        draft.setNotes(request.getNotes());
        for (DraftCreateRequest.Item requested : request.getItems()) {
            Product product = productRepository.findById(requested.getProductId())
                    .filter(p -> Boolean.TRUE.equals(p.getIsActive()) && p.getDeletedAt() == null)
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + requested.getProductId()));
            DraftItem item = new DraftItem();
            item.setDraft(draft);
            item.setProduct(product);
            item.setQuantity(requested.getQuantity());
            draft.getItems().add(item);
        }
        return toResponse(draftRepository.save(draft));
    }

    @Transactional(readOnly = true)
    public List<DraftResponse> getAll(Long cashierId) {
        return draftRepository.findByCashierIdAndIsActiveTrueOrderByCreatedAtDesc(cashierId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional
    public DraftResponse update(Long id, DraftCreateRequest request, Long cashierId) {
        Draft draft = draftRepository.findByIdAndCashierIdAndIsActiveTrue(id, cashierId)
                .orElseThrow(() -> new ResourceNotFoundException("Draft not found: " + id));
        draft.setCustomerId(request.getCustomerId());
        draft.setNotes(request.getNotes());
        draft.getItems().clear();
        for (DraftCreateRequest.Item requested : request.getItems()) {
            Product product = productRepository.findById(requested.getProductId())
                    .filter(p -> Boolean.TRUE.equals(p.getIsActive()) && p.getDeletedAt() == null)
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + requested.getProductId()));
            DraftItem item = new DraftItem();
            item.setDraft(draft);
            item.setProduct(product);
            item.setQuantity(requested.getQuantity());
            draft.getItems().add(item);
        }
        return toResponse(draftRepository.save(draft));
    }

    @Transactional(readOnly = true)
    public DraftResponse getById(Long id, Long cashierId) {
        return draftRepository.findByIdAndCashierIdAndIsActiveTrue(id, cashierId)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Draft not found: " + id));
    }

    @Transactional
    public void delete(Long id, Long cashierId) {
        Draft draft = draftRepository.findByIdAndCashierIdAndIsActiveTrue(id, cashierId)
                .orElseThrow(() -> new ResourceNotFoundException("Draft not found: " + id));
        draft.setIsActive(false);
        draftRepository.save(draft);
    }

    private DraftResponse toResponse(Draft draft) {
        DraftResponse response = new DraftResponse();
        response.setId(draft.getId());
        response.setCreatedAt(draft.getCreatedAt());
        response.setCustomerId(draft.getCustomerId());
        response.setNotes(draft.getNotes());
        response.setItems(draft.getItems().stream().map(item -> {
            Product product = item.getProduct();
            DraftResponse.Item result = new DraftResponse.Item();
            result.setProductId(product.getId());
            result.setProductName(product.getName());
            result.setSku(product.getSku());
            result.setUnitPrice(product.getUnitPrice());
            result.setQuantity(item.getQuantity());
            result.setAvailableStock(product.getAvailableQuantity());
            return result;
        }).toList());
        return response;
    }
}