package com.bms.repository;

import com.bms.entity.Location;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LocationRepository extends JpaRepository<Location, Long> {
    Optional<Location> findByCodeIgnoreCase(String code);
    List<Location> findByTypeAndIsActiveTrue(Location.LocationType type);
    List<Location> findByIsActiveTrueOrderByNameAsc();
    List<Location> findAllByOrderByIdAsc();
}
