package com.bms.service;

import com.bms.entity.Location;
import com.bms.entity.Product;
import com.bms.repository.LocationRepository;
import com.bms.repository.LocationStockRepository;
import com.bms.repository.ProductRepository;
import com.bms.repository.StockMovementRepository;
import com.bms.repository.StockTransferRepository;
import com.bms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocationServiceTotalOwnedUnitsTest {

    @Mock
    private LocationRepository locationRepository;
    @Mock
    private LocationStockRepository locationStockRepository;
    @Mock
    private StockTransferRepository stockTransferRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private StockMovementRepository stockMovementRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AuditLogService auditLogService;

    private LocationService locationService;

    @BeforeEach
    void setUp() {
        locationService = new LocationService(locationRepository, locationStockRepository,
                stockTransferRepository, productRepository, stockMovementRepository,
                userRepository, auditLogService);
    }

    @Test
    void totalOwnedUnitsSumsShopAndEveryNonShopBucket() {
        Product product = new Product();
        product.setId(7L);
        product.setStockQuantity(5);

        // Warehouse (backroom) 10 + quarantine 4 live in location buckets.
        when(locationStockRepository.sumNonShopQuantityByProduct(7L)).thenReturn(14L);

        assertEquals(19, locationService.totalOwnedUnits(product));
    }

    @Test
    void totalOwnedUnitsHandlesNullShopStock() {
        Product product = new Product();
        product.setId(7L);
        product.setStockQuantity(null);
        lenient().when(locationStockRepository.sumNonShopQuantityByProduct(7L)).thenReturn(0L);

        assertEquals(0, locationService.totalOwnedUnits(product));
    }
}