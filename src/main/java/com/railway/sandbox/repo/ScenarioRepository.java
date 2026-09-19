package com.railway.sandbox.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScenarioRepository extends JpaRepository<ScenarioEntity, String> {
    List<ScenarioEntity> findByLineageOrderByVersionNoDesc(String lineage);
    List<ScenarioEntity> findAllByOrderByCreatedAtDesc();
    Optional<ScenarioEntity> findTopByOrderByCreatedAtDesc();
    Optional<ScenarioEntity> findTopByLineageOrderByVersionNoDesc(String lineage);
}
