package com.railway.sandbox.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TopologyRepository extends JpaRepository<TopologyEntity, String> {
    List<TopologyEntity> findAllByOrderByRevisionDesc();
    Optional<TopologyEntity> findTopByOrderByRevisionDesc();
}
