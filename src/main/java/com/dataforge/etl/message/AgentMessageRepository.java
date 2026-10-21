package com.dataforge.etl.message;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Agent 消息数据访问层 */
@Repository
public interface AgentMessageRepository extends JpaRepository<AgentMessage, Long> {

    /** 按运行查询(分页,按 id 升序 = 时间序) */
    Page<AgentMessage> findByRunIdOrderByIdAsc(Long runId, Pageable pageable);

    /** 全量分页(按 id 倒序,最新在前) */
    Page<AgentMessage> findAllByOrderByIdDesc(Pageable pageable);

    long countByType(MessageType type);
}
