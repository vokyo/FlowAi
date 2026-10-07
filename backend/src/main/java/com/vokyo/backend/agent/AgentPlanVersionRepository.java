package com.vokyo.backend.agent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentPlanVersionRepository extends JpaRepository<AgentPlanVersion, UUID> {

    Optional<AgentPlanVersion> findByRun_IdAndVersion(UUID runId, int version);

    List<AgentPlanVersion> findByRun_IdOrderByVersion(UUID runId);
}
