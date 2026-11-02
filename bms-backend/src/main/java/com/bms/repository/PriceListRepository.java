package com.bms.repository;

import com.bms.entity.PriceList;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PriceListRepository extends JpaRepository<PriceList, Long> {

    @EntityGraph(attributePaths = {"items", "items.product"})
    List<PriceList> findAllByOrderByNameAsc();

    @EntityGraph(attributePaths = {"items", "items.product"})
    @org.springframework.data.jpa.repository.Query("SELECT p FROM PriceList p WHERE p.id = :id")
    Optional<PriceList> findWithItemsById(@org.springframework.data.repository.query.Param("id") Long id);

    boolean existsByNameIgnoreCase(String name);
}
