package com.bms.service;

import com.bms.dto.request.LocationRequest;
import com.bms.dto.request.RescueStockRequest;
import com.bms.dto.request.StockTransferRequest;
import com.bms.entity.*;
import com.bms.exception.BusinessException;
import com.bms.exception.ResourceNotFoundException;
import com.bms.repository.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@Transactional
public class LocationService {

    private final LocationRepository locationRepository;
    private final LocationStockRepository locationStockRepository;
    private final StockTransferRepository stockTransferRepository;
    private final ProductRepository productRepository;
    private final StockMovementRepository stockMovementRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    public LocationService(LocationRepository locationRepository,
                           LocationStockRepository locationStockRepository,
                           StockTransferRepository stockTransferRepository,
                           ProductRepository productRepository,
                           StockMovementRepository stockMovementRepository,
                           UserRepository userRepository,
                           AuditLogService auditLogService) {
        this.locationRepository = locationRepository;
        this.locationStockRepository = locationStockRepository;
        this.stockTransferRepository = stockTransferRepository;
        this.productRepository = productRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
    }

    // ------------------------------------------------------------------
    // Locations
    // ------------------------------------------------------------------

    public List<Location> ensureDefaults() {
        ensure("SHOP", "Shop Floor", Location.LocationType.SHOP);
        ensure("BACKROOM", "Backroom", Location.LocationType.BACKROOM);
        ensure("QUARANTINE", "Quarantine", Location.LocationType.QUARANTINE);
        ensure("TRANSIT", "In Transit", Location.LocationType.TRANSIT);
        return locationRepository.findAllByOrderByIdAsc();
    }

    private Location ensure(String code, String name, Location.LocationType type) {
        return locationRepository.findByCodeIgnoreCase(code).orElseGet(() -> {
            Location location = new Location();
            location.setCode(code);
            location.setName(name);
            location.setType(type);
            return locationRepository.save(location);
        });
    }

    public Location getDefault(Location.LocationType type) {
        ensureDefaults();
        return locationRepository.findByTypeAndIsActiveTrue(type).stream().findFirst()
                .orElseThrow(() -> new BusinessException("No active " + type + " location configured"));
    }

    public Location getLocation(Long id) {
        return locationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Location not found: " + id));
    }

    public Optional<Location> findByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return locationRepository.findByCodeIgnoreCase(code.trim());
    }

    /** Units of one product in one bucket; SHOP mirrors Product.stockQuantity. */
    public int quantityIn(Location location, Product product) {
        if (location.getType() == Location.LocationType.SHOP) {
            return product.getStockQuantity() != null ? product.getStockQuantity() : 0;
        }
        return locationStockRepository.findByLocationIdAndProductId(location.getId(), product.getId())
                .map(LocationStock::getQuantity).orElse(0);
    }

    /**
     * Removes sellable shop stock, keeping the mirrored SHOP bucket in sync
     * when one exists (legacy products may never have had one).
     */
    public void removeShopStock(Product product, int quantity) {
        if (quantity <= 0) {
            return;
        }
        int current = product.getStockQuantity() != null ? product.getStockQuantity() : 0;
        if (current < quantity) {
            throw new BusinessException("Only " + current + " sellable unit(s) of "
                    + product.getName() + " are available");
        }
        product.setStockQuantity(current - quantity);
        Location shop = getDefault(Location.LocationType.SHOP);
        locationStockRepository.findForUpdate(shop.getId(), product.getId())
                .ifPresent(stock -> {
                    stock.setQuantity(Math.max(0, stock.getQuantity() - quantity));
                    locationStockRepository.save(stock);
                });
    }

    public List<Location> list() {
        ensureDefaults();
        return locationRepository.findByIsActiveTrueOrderByNameAsc();
    }

    public Location create(LocationRequest request) {
        ensureDefaults();
        if (locationRepository.findByCodeIgnoreCase(request.getCode().trim()).isPresent()) {
            throw new BusinessException("A location with this code already exists");
        }
        Location location = new Location();
        apply(location, request);
        return locationRepository.save(location);
    }

    public Location update(Long id, LocationRequest request) {
        Location location = getLocation(id);
        apply(location, request);
        return locationRepository.save(location);
    }

    private void apply(Location location, LocationRequest request) {
        location.setCode(request.getCode().trim().toUpperCase());
        location.setName(request.getName().trim());
        try {
            location.setType(Location.LocationType.valueOf(request.getType().trim().toUpperCase()));
        } catch (Exception ex) {
            throw new BusinessException("Invalid location type: " + request.getType());
        }
        location.setIsActive(request.getActive() == null || request.getActive());
    }

    // ------------------------------------------------------------------
    // Location stock
    // ------------------------------------------------------------------

    public List<Map<String, Object>> stockByLocation(Long locationId) {
        Location location = getLocation(locationId);
        if (location.getType() == Location.LocationType.SHOP) {
            // Legacy products only track stock on the product row; merge those
            // quantities with the explicit SHOP bucket so nothing disappears.
            Map<Long, Integer> buckets = new HashMap<>();
            for (LocationStock stock : locationStockRepository.findPositiveByLocation(locationId)) {
                buckets.put(stock.getProduct().getId(), stock.getQuantity());
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Product product : productRepository.findByIsActiveTrueAndDeletedAtIsNullOrderByNameAsc()) {
                int quantity = product.getStockQuantity() != null ? product.getStockQuantity() : 0;
                if (quantity <= 0) {
                    continue;
                }
                rows.add(stockRow(product, quantity, location));
            }
            return rows;
        }
        return locationStockRepository.findPositiveByLocation(locationId).stream()
                .map(stock -> stockRow(stock.getProduct(), stock.getQuantity(), location))
                .toList();
    }

    private Map<String, Object> stockRow(Product product, int quantity, Location location) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("productId", product.getId());
        row.put("sku", product.getSku());
        row.put("name", product.getName());
        row.put("binLocation", product.getBinLocation());
        row.put("quantity", quantity);
        row.put("locationId", location.getId());
        row.put("locationCode", location.getCode());
        row.put("locationType", location.getType().name());
        return row;
    }

    /**
     * Total units the business owns: sellable shop stock plus every non-shop
     * bucket (backroom/warehouse, quarantine, transit). Product.stockQuantity
     * mirrors only the SHOP bucket. Must be called while holding the product
     * row lock, inside the same transaction as the cost calculation.
     */
    public int totalOwnedUnits(Product product) {
        int shop = product.getStockQuantity() != null ? product.getStockQuantity() : 0;
        return shop + (int) locationStockRepository.sumNonShopQuantityByProduct(product.getId());
    }

    /** Adds units to a location bucket without touching sellable product stock. */
    public void addStock(Product product, Location location, int quantity) {
        if (quantity <= 0) {
            return;
        }
        LocationStock stock = locationStockRepository.findForUpdate(location.getId(), product.getId())
                .orElseGet(() -> {
                    LocationStock fresh = new LocationStock();
                    fresh.setLocation(location);
                    fresh.setProduct(product);
                    fresh.setQuantity(0);
                    return fresh;
                });
        stock.setQuantity(stock.getQuantity() + quantity);
        locationStockRepository.save(stock);
    }

    /** Removes units from a location bucket, refusing to go below zero. */
    public void removeStock(Product product, Location location, int quantity) {
        if (quantity <= 0) {
            return;
        }
        LocationStock stock = locationStockRepository.findForUpdate(location.getId(), product.getId())
                .orElseThrow(() -> new BusinessException(
                        "No " + location.getCode() + " stock found for " + product.getName()));
        if (stock.getQuantity() < quantity) {
            throw new BusinessException("Only " + stock.getQuantity() + " unit(s) of "
                    + product.getName() + " are in " + location.getCode());
        }
        stock.setQuantity(stock.getQuantity() - quantity);
        locationStockRepository.save(stock);
    }

    public List<Map<String, Object>> putAwayList() {
        Location backroom = getDefault(Location.LocationType.BACKROOM);
        return locationStockRepository.findPositiveByLocation(backroom.getId()).stream()
                .map(stock -> {
                    Map<String, Object> row = stockRow(stock.getProduct(), stock.getQuantity(), backroom);
                    row.put("suggestedBin", stock.getProduct().getBinLocation());
                    return row;
                })
                .toList();
    }

    public List<Map<String, Object>> binLabels(boolean includeUnassigned) {
        return productRepository.findByIsActiveTrueAndDeletedAtIsNullOrderByNameAsc().stream()
                .filter(p -> includeUnassigned || (p.getBinLocation() != null && !p.getBinLocation().isBlank()))
                .map(product -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("productId", product.getId());
                    row.put("name", product.getName());
                    row.put("sku", product.getSku());
                    row.put("barcode", product.getBarcode());
                    row.put("binLocation", product.getBinLocation());
                    row.put("unitPrice", product.getUnitPrice());
                    row.put("abcClass", product.getAbcClass());
                    return row;
                })
                .toList();
    }

    public Map<String, Object> scan(String code) {
        if (code == null || code.isBlank()) {
            throw new BusinessException("Scan code is required");
        }
        String trimmed = code.trim();
        Product product = productRepository.findByBarcodeIgnoreCase(trimmed)
                .or(() -> productRepository.findBySkuIgnoreCase(trimmed))
                .or(() -> productRepository.findFirstByBinLocationIgnoreCase(trimmed))
                .orElseThrow(() -> new ResourceNotFoundException("No product found for code: " + code));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("productId", product.getId());
        result.put("name", product.getName());
        result.put("sku", product.getSku());
        result.put("barcode", product.getBarcode());
        result.put("expectedBin", product.getBinLocation());
        result.put("abcClass", product.getAbcClass());
        result.put("shopQuantity", product.getStockQuantity());

        List<Map<String, Object>> locations = new ArrayList<>();
        locations.add(stockRow(product, product.getStockQuantity() != null ? product.getStockQuantity() : 0,
                getDefault(Location.LocationType.SHOP)));
        for (LocationStock stock : locationStockRepository.findPositiveByProduct(product.getId())) {
            if (stock.getLocation().getType() != Location.LocationType.SHOP) {
                locations.add(stockRow(stock.getProduct(), stock.getQuantity(), stock.getLocation()));
            }
        }
        result.put("locations", locations);
        result.put("misplaced", product.getBinLocation() != null
                && !product.getBinLocation().isBlank()
                && !product.getBinLocation().equalsIgnoreCase(trimmed)
                && !product.getSku().equalsIgnoreCase(trimmed)
                && !(product.getBarcode() != null && product.getBarcode().equalsIgnoreCase(trimmed)));
        return result;
    }

    public Map<String, Object> rescue(RescueStockRequest request, Long userId) {
        Product product = productRepository.findByIdForUpdate(request.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + request.getProductId()));
        Location from = getLocation(request.getFromLocationId());
        Location to = getLocation(request.getToLocationId());
        if (from.getId().equals(to.getId())) {
            throw new BusinessException("Source and destination locations must differ");
        }
        moveStock(product, from, to, request.getQuantity(), "RESCUE", userId,
                request.getReason() != null ? request.getReason() : "Misplaced item rescue");
        auditLogService.logAction(userId, "STOCK_RESCUE",
                "Rescued " + request.getQuantity() + " x " + product.getName() + " from "
                        + from.getCode() + " to " + to.getCode(),
                "Product", product.getId(), null, null);
        return scan(product.getBarcode() != null ? product.getBarcode() : product.getSku());
    }

    // ------------------------------------------------------------------
    // Transfers
    // ------------------------------------------------------------------

    public Page<StockTransfer> listTransfers(Pageable pageable) {
        return stockTransferRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    public StockTransfer getTransfer(Long id) {
        return stockTransferRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Stock transfer not found: " + id));
    }

    public StockTransfer createTransfer(StockTransferRequest request, Long userId) {
        Location from = getLocation(request.getFromLocationId());
        Location to = getLocation(request.getToLocationId());
        if (from.getId().equals(to.getId())) {
            throw new BusinessException("Source and destination locations must differ");
        }
        StockTransfer transfer = new StockTransfer();
        transfer.setTransferNumber(nextTransferNumber());
        transfer.setFromLocation(from);
        transfer.setToLocation(to);
        transfer.setNotes(request.getNotes());
        transfer.setCreatedBy(userId);
        Set<Long> seen = new HashSet<>();
        for (StockTransferRequest.Item itemRequest : request.getItems()) {
            if (!seen.add(itemRequest.getProductId())) {
                throw new BusinessException("A product can only appear once per transfer");
            }
            Product product = productRepository.findById(itemRequest.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Product not found: " + itemRequest.getProductId()));
            StockTransferItem item = new StockTransferItem();
            item.setTransfer(transfer);
            item.setProduct(product);
            item.setQuantity(itemRequest.getQuantity());
            transfer.getItems().add(item);
        }
        return stockTransferRepository.save(transfer);
    }

    public StockTransfer completeTransfer(Long id, Long userId) {
        StockTransfer transfer = getTransfer(id);
        if (transfer.getStatus() != StockTransfer.TransferStatus.DRAFT) {
            throw new BusinessException("Only a draft transfer can be completed");
        }
        for (StockTransferItem item : transfer.getItems()) {
            Product product = productRepository.findByIdForUpdate(item.getProduct().getId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Product not found: " + item.getProduct().getId()));
            moveStock(product, transfer.getFromLocation(), transfer.getToLocation(),
                    item.getQuantity(), "TRANSFER", userId,
                    "Transfer " + transfer.getTransferNumber());
        }
        transfer.setStatus(StockTransfer.TransferStatus.COMPLETED);
        transfer.setCompletedAt(LocalDateTime.now());
        transfer.setCompletedBy(userId);
        stockTransferRepository.save(transfer);
        auditLogService.logAction(userId, "STOCK_TRANSFER_COMPLETE",
                "Stock transfer completed: " + transfer.getTransferNumber(),
                "StockTransfer", transfer.getId(), null, null);
        return transfer;
    }

    /**
     * DRAFT -> IN_TRANSIT: stock leaves the source and waits in the virtual
     * TRANSIT location. It is not sellable while in transit.
     */
    public StockTransfer startTransfer(Long id, Long userId) {
        StockTransfer transfer = getTransfer(id);
        if (transfer.getStatus() != StockTransfer.TransferStatus.DRAFT) {
            throw new BusinessException("Only a draft transfer can be dispatched");
        }
        Location transit = getDefault(Location.LocationType.TRANSIT);
        for (StockTransferItem item : transfer.getItems()) {
            Product product = productRepository.findByIdForUpdate(item.getProduct().getId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Product not found: " + item.getProduct().getId()));
            moveStock(product, transfer.getFromLocation(), transit, item.getQuantity(),
                    "TRANSFER", userId, "Transfer " + transfer.getTransferNumber() + " dispatched");
        }
        transfer.setStatus(StockTransfer.TransferStatus.IN_TRANSIT);
        stockTransferRepository.save(transfer);
        auditLogService.logAction(userId, "STOCK_TRANSFER_DISPATCH",
                "Stock transfer dispatched: " + transfer.getTransferNumber(),
                "StockTransfer", transfer.getId(), null, null);
        return transfer;
    }

    /** IN_TRANSIT -> RECEIVED: transit stock lands at the destination. */
    public StockTransfer receiveTransfer(Long id, Long userId) {
        StockTransfer transfer = getTransfer(id);
        if (transfer.getStatus() != StockTransfer.TransferStatus.IN_TRANSIT) {
            throw new BusinessException("Only an in-transit transfer can be received");
        }
        Location transit = getDefault(Location.LocationType.TRANSIT);
        for (StockTransferItem item : transfer.getItems()) {
            Product product = productRepository.findByIdForUpdate(item.getProduct().getId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Product not found: " + item.getProduct().getId()));
            moveStock(product, transit, transfer.getToLocation(), item.getQuantity(),
                    "TRANSFER", userId, "Transfer " + transfer.getTransferNumber() + " received");
        }
        transfer.setStatus(StockTransfer.TransferStatus.RECEIVED);
        transfer.setCompletedAt(LocalDateTime.now());
        transfer.setCompletedBy(userId);
        stockTransferRepository.save(transfer);
        auditLogService.logAction(userId, "STOCK_TRANSFER_RECEIVE",
                "Stock transfer received: " + transfer.getTransferNumber(),
                "StockTransfer", transfer.getId(), null, null);
        return transfer;
    }

    public StockTransfer cancelTransfer(Long id, Long userId) {
        StockTransfer transfer = getTransfer(id);
        if (transfer.getStatus() != StockTransfer.TransferStatus.DRAFT) {
            throw new BusinessException("Only a draft transfer can be cancelled");
        }
        transfer.setStatus(StockTransfer.TransferStatus.CANCELLED);
        stockTransferRepository.save(transfer);
        auditLogService.logAction(userId, "STOCK_TRANSFER_CANCEL",
                "Stock transfer cancelled: " + transfer.getTransferNumber(),
                "StockTransfer", transfer.getId(), null, null);
        return transfer;
    }

    /**
     * Moves stock between locations. SHOP is special: its quantity mirrors the
     * sellable {@code Product.stockQuantity}, so the product row is updated
     * together with the explicit location bucket.
     */
    private void moveStock(Product product, Location from, Location to, int quantity,
                           String reasonCode, Long userId, String description) {
        if (quantity <= 0) {
            throw new BusinessException("Quantity must be positive");
        }
        int productStock = product.getStockQuantity() != null ? product.getStockQuantity() : 0;
        boolean fromShop = from.getType() == Location.LocationType.SHOP;
        boolean toShop = to.getType() == Location.LocationType.SHOP;

        if (fromShop) {
            if (productStock < quantity) {
                throw new BusinessException("Only " + productStock + " unit(s) of "
                        + product.getName() + " are on the shop floor");
            }
            product.setStockQuantity(productStock - quantity);
            // Keep the SHOP bucket in sync only when it already exists; legacy
            // products may never have had one.
            locationStockRepository.findForUpdate(from.getId(), product.getId())
                    .ifPresent(stock -> {
                        stock.setQuantity(Math.max(0, stock.getQuantity() - quantity));
                        locationStockRepository.save(stock);
                    });
        } else {
            removeStock(product, from, quantity);
        }

        if (toShop) {
            product.setStockQuantity((product.getStockQuantity() != null ? product.getStockQuantity() : 0)
                    + quantity);
            addStock(product, to, quantity);
        } else {
            addStock(product, to, quantity);
        }
        productRepository.save(product);

        User user = userRepository.findById(userId).orElse(null);
        java.math.BigDecimal unitCost = product.getCostPrice() != null
                ? product.getCostPrice() : java.math.BigDecimal.ZERO;
        java.math.BigDecimal totalValuation = unitCost.multiply(java.math.BigDecimal.valueOf(quantity))
                .setScale(CostCalculationUtils.COST_SCALE, java.math.RoundingMode.HALF_UP);
        String movementDescription = description + " (" + from.getCode() + " -> " + to.getCode() + ")";

        if (from.getType() == Location.LocationType.QUARANTINE) {
            recordQuarantineTransferOut(product, from, quantity, reasonCode, movementDescription, user, unitCost);
        } else {
            stockMovementRepository.save(transferMovement(product, StockMovement.MovementType.TRANSFER_OUT,
                    quantity, from.getCode(), reasonCode, movementDescription, user, totalValuation, null));
        }
        stockMovementRepository.save(transferMovement(product, StockMovement.MovementType.TRANSFER_IN,
                quantity, to.getCode(), reasonCode, movementDescription, user, totalValuation, null));
    }

    /**
     * Attributes quarantine releases FIFO to the purchase receipts that put
     * the goods there, so return-to-vendor scoping stays correct after a
     * release. Legacy stock without a receipt trail stays unattributed.
     */
    private void recordQuarantineTransferOut(Product product, Location from, int quantity,
                                             String reasonCode, String description, User user,
                                             java.math.BigDecimal unitCost) {
        int unattributed = quantity;
        List<StockMovement> receipts = stockMovementRepository.findByProductAndReferenceTypeAndLocation(
                product.getId(), StockMovement.ReferenceType.RECEIVING, from.getCode());
        for (StockMovement receipt : receipts) {
            if (unattributed <= 0) {
                break;
            }
            if (receipt.getMovementType() != StockMovement.MovementType.IN
                    || receipt.getPurchaseItemId() == null) {
                continue;
            }
            int consumed = 0;
            for (StockMovement movement : stockMovementRepository.findByPurchaseItemId(receipt.getPurchaseItemId())) {
                if (from.getCode().equalsIgnoreCase(movement.getLocation())
                        && (movement.getMovementType() == StockMovement.MovementType.OUT
                            || movement.getMovementType() == StockMovement.MovementType.TRANSFER_OUT)) {
                    consumed += movement.getQuantity();
                }
            }
            int remaining = receipt.getQuantity() - consumed;
            if (remaining <= 0) {
                continue;
            }
            int take = Math.min(unattributed, remaining);
            stockMovementRepository.save(transferMovement(product, StockMovement.MovementType.TRANSFER_OUT,
                    take, from.getCode(), reasonCode, description, user,
                    unitCost.multiply(java.math.BigDecimal.valueOf(take))
                            .setScale(CostCalculationUtils.COST_SCALE, java.math.RoundingMode.HALF_UP),
                    receipt.getPurchaseItemId()));
            unattributed -= take;
        }
        if (unattributed > 0) {
            stockMovementRepository.save(transferMovement(product, StockMovement.MovementType.TRANSFER_OUT,
                    unattributed, from.getCode(), reasonCode, description, user,
                    unitCost.multiply(java.math.BigDecimal.valueOf(unattributed))
                            .setScale(CostCalculationUtils.COST_SCALE, java.math.RoundingMode.HALF_UP),
                    null));
        }
    }

    private StockMovement transferMovement(Product product, StockMovement.MovementType type, int quantity,
                                           String locationCode, String reasonCode, String description,
                                           User user, java.math.BigDecimal valuation, Long purchaseItemId) {
        StockMovement movement = new StockMovement();
        movement.setProduct(product);
        movement.setMovementType(type);
        movement.setQuantity(quantity);
        movement.setReferenceType(StockMovement.ReferenceType.TRANSFER);
        movement.setReferenceId(0L);
        movement.setPurchaseItemId(purchaseItemId);
        movement.setReasonCode(reasonCode);
        movement.setLocation(locationCode);
        movement.setDescription(description);
        movement.setValuationAmount(valuation);
        movement.setCreatedBy(user);
        movement.setMovementDate(LocalDateTime.now());
        return movement;
    }

    private String nextTransferNumber() {
        LocalDate today = LocalDate.now();
        String prefix = "TR-" + today.format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";
        long next = stockTransferRepository.countByTransferNumberStartingWith(prefix) + 1;
        return prefix + String.format("%04d", next);
    }
}
