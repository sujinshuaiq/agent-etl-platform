package com.dataforge.etl.message;

import com.dataforge.etl.engine.RunEventPublisher;
import com.dataforge.etl.run.entity.PipelineRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 消息总线:Agent 间通信的中枢。
 *
 * <p>职责:
 * 1. 接收执行引擎在节点边界产生的交接/广播消息;
 * 2. 持久化到 agent_message 表(可审计、可回溯,构成数据血缘的通信视图);
 * 3. 通过 SSE 实时广播给前端(事件类型 MESSAGE)。</p>
 *
 * <p>设计要点:Agent 之间不直接持有对方引用、不点对点调用——
 * 消息全部经过总线中介,这样通信链路天然可观测、可重放、可治理,
 * 也是后续演进到跨进程消息中间件(Kafka/RocketMQ)时的替换点。</p>
 */
@Component
public class MessageBus {

    private static final Logger log = LoggerFactory.getLogger(MessageBus.class);

    private final AgentMessageRepository messageRepository;
    private final RunEventPublisher eventPublisher;

    public MessageBus(AgentMessageRepository messageRepository, RunEventPublisher eventPublisher) {
        this.messageRepository = messageRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 数据交接消息:上游 Agent → 下游 Agent(对应 DAG 上的一条边)。
     */
    public AgentMessage dataHandoff(PipelineRun run, String fromAgent, String toAgent, long rows) {
        return publish(new AgentMessage(run.getId(), run.getPipelineName(), fromAgent, toAgent,
                MessageType.DATA_HANDOFF, rows,
                "数据交接: " + rows + " 行,「" + fromAgent + "」→「" + toAgent + "」"));
    }

    /**
     * 完成广播:Agent 节点执行成功,向全体 Agent 广播产出就绪。
     */
    public AgentMessage dataReady(PipelineRun run, String fromAgent, long rows) {
        return publish(new AgentMessage(run.getId(), run.getPipelineName(), fromAgent, null,
                MessageType.DATA_READY, rows,
                "产出就绪: 「" + fromAgent + "」完成处理,输出 " + rows + " 行(广播)"));
    }

    /** 发布消息:落库 + SSE 广播;任何异常都不影响主流程 */
    private AgentMessage publish(AgentMessage message) {
        try {
            AgentMessage saved = messageRepository.save(message);
            eventPublisher.publish("MESSAGE", saved);
            return saved;
        } catch (Exception e) {
            log.warn("Agent 消息发布失败: type={}, error={}", message.getType(), e.getMessage());
            return message;
        }
    }

    /** 分页查询:runId 为空时查全部(最新在前) */
    public Page<AgentMessage> page(Long runId, int page, int size) {
        int p = Math.max(0, page);
        int s = Math.min(200, Math.max(1, size));
        if (runId != null) {
            return messageRepository.findByRunIdOrderByIdAsc(runId, PageRequest.of(p, s));
        }
        return messageRepository.findAllByOrderByIdDesc(PageRequest.of(p, s));
    }

    /** 通信统计 */
    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", messageRepository.count());
        result.put("handoff", messageRepository.countByType(MessageType.DATA_HANDOFF));
        result.put("ready", messageRepository.countByType(MessageType.DATA_READY));
        return result;
    }
}
