package com.railway.sandbox.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReportRepository extends JpaRepository<ReportEntity, String> {
    List<ReportEntity> findAllByOrderByCreatedAtDesc();
    List<ReportEntity> findByScenarioIdOrderByCreatedAtDesc(String scenarioId);
}
