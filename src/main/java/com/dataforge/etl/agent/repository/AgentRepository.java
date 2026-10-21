package com.dataforge.etl.agent.repository;

import com.dataforge.etl.agent.entity.Agent;
import com.dataforge.etl.common.enums.AgentStatus;
import com.dataforge.etl.common.enums.AgentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Agent 数据访问层 */
@Repository
public interface AgentRepository extends JpaRepository<Agent, Long> {

    boolean existsByCode(String code);

    List<Agent> findByStatus(AgentStatus status);

    List<Agent> findByTypeAndStatus(AgentType type, AgentStatus status);

    List<Agent> findByType(AgentType type);

    long countByStatus(AgentStatus status);
}
