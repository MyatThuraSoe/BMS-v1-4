package com.bms.repository;

import com.bms.entity.Location;
import com.bms.entity.LocationStock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LocationStockRepository extends JpaRepository<LocationStock, Long> {

    Optional<LocationStock> findByLocationIdAndProductId(Long locationId, Long productId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ls FROM LocationStock ls WHERE ls.location.id = :locationId AND ls.product.id = :productId")
    Optional<LocationStock> findForUpdate(@Param("locationId") Long locationId,
                                          @Param("productId") Long productId);

    @Query("""
        SELECT ls FROM LocationStock ls
        JOIN FETCH ls.product p
        JOIN FETCH ls.location l
        WHERE l.id = :locationId AND ls.quantity > 0
        ORDER BY p.name
        """)
    List<LocationStock> findPositiveByLocation(@Param("locationId") Long locationId);

    @Query("""
        SELECT ls FROM LocationStock ls
        JOIN FETCH ls.product p
        JOIN FETCH ls.location l
        WHERE l.type = :type AND ls.quantity > 0
        ORDER BY p.name
        """)
    List<LocationStock> findPositiveByLocationType(@Param("type") Location.LocationType type);

    @Query("""
        SELECT ls FROM LocationStock ls
        JOIN FETCH ls.location l
        WHERE ls.product.id = :productId AND ls.quantity > 0
        ORDER BY l.name
        """)
    List<LocationStock> findPositiveByProduct(@Param("productId") Long productId);

    @Query("SELECT COALESCE(SUM(ls.quantity), 0) FROM LocationStock ls WHERE ls.product.id = :productId")
    long sumQuantityByProduct(@Param("productId") Long productId);

    /**
     * Units in every non-SHOP bucket. The SHOP bucket mirrors
     * Product.stockQuantity, so it is excluded here to avoid double counting.
     */
    @Query("SELECT COALESCE(SUM(ls.quantity), 0) FROM LocationStock ls "
            + "WHERE ls.product.id = :productId "
            + "AND ls.location.type <> com.bms.entity.Location.LocationType.SHOP")
    long sumNonShopQuantityByProduct(@Param("productId") Long productId);
}
