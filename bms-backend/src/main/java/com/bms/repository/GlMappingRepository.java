package com.bms.repository;

import com.bms.entity.GlMapping;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GlMappingRepository extends JpaRepository<GlMapping, Long> {

    @EntityGraph(attributePaths = {"account"})
    List<GlMapping> findAllByOrderByEventTypeAsc();

    @EntityGraph(attributePaths = {"account"})
    Optional<GlMapping> findByEventType(GlMapping.EventType eventType);
}
