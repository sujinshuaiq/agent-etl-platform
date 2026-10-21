package com.dataforge.etl.ai.repository;

import com.dataforge.etl.ai.entity.AiChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** AI 聊天消息数据访问层 */
@Repository
public interface AiChatMessageRepository extends JpaRepository<AiChatMessage, Long> {

    List<AiChatMessage> findBySessionIdOrderByIdAsc(String sessionId);
}
