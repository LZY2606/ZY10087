package com.railway.sandbox.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RuleSetRepository extends JpaRepository<RuleSetEntity, String> {
    List<RuleSetEntity> findAllByOrderByUpdatedAtDesc();
}
