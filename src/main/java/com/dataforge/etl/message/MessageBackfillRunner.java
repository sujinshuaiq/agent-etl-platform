package com.dataforge.etl.message;

import com.dataforge.etl.pipeline.entity.PipelineEdge;
import com.dataforge.etl.pipeline.repository.PipelineEdgeRepository;
import com.dataforge.etl.run.entity.NodeRun;
import com.dataforge.etl.run.entity.PipelineRun;
import com.dataforge.etl.run.repository.NodeRunRepository;
import com.dataforge.etl.run.repository.PipelineRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 历史消息回填:启动时若消息表为空且已有历史运行,
 * 从 NodeRun + 管道边关系重建最近 150 次运行的 Agent 通信消息,
 * 让「Agent 通信」视图对存量数据也有内容可看。
 */
@Component
@Order(10) // 在 DataInitializer 之后执行
public class MessageBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MessageBackfillRunner.class);
    private static final int BACKFILL_RUNS = 150;
    private static final int BATCH = 500;

    private final AgentMessageRepository messageRepository;
    private final PipelineRunRepository runRepository;
    private final NodeRunRepository nodeRunRepository;
    private final PipelineEdgeRepository edgeRepository;

    public MessageBackfillRunner(AgentMessageRepository messageRepository,
                                 PipelineRunRepository runRepository,
                                 NodeRunRepository nodeRunRepository,
                                 PipelineEdgeRepository edgeRepository) {
        this.messageRepository = messageRepository;
        this.runRepository = runRepository;
        this.nodeRunRepository = nodeRunRepository;
        this.edgeRepository = edgeRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (messageRepository.count() > 0) {
            return;
        }
        List<PipelineRun> runs = runRepository.findAll(
                PageRequest.of(0, BACKFILL_RUNS, Sort.by("id").descending())).getContent();
        if (runs.isEmpty()) {
            return;
        }
        long t0 = System.currentTimeMillis();
        Map<Long, List<PipelineEdge>> edgesCache = new HashMap<>();
        List<AgentMessage> buffer = new ArrayList<>();
        int total = 0;
        for (PipelineRun run : runs) {
            List<NodeRun> nodeRuns = nodeRunRepository.findByRunIdOrderByIdAsc(run.getId());
            Map<String, NodeRun> byKey = new HashMap<>();
            for (NodeRun nr : nodeRuns) {
                byKey.put(nr.getNodeKey(), nr);
                // DATA_READY:成功节点产出就绪广播
                if (nr.getAgentName() != null && nr.getEndTime() != null
                        && "SUCCESS".equals(String.valueOf(nr.getStatus()))) {
                    long rows = nr.getRowsOut() == null ? 0 : nr.getRowsOut();
                    AgentMessage m = new AgentMessage(run.getId(), run.getPipelineName(),
                            nr.getAgentName(), null, MessageType.DATA_READY, rows,
                            "产出就绪: 「" + nr.getAgentName() + "」完成处理,输出 " + rows + " 行(广播)");
                    m.setCreatedAt(nr.getEndTime());
                    buffer.add(m);
                }
            }
            // DATA_HANDOFF:按管道边重建交接消息
            List<PipelineEdge> edges = edgesCache.computeIfAbsent(run.getPipelineId(),
                    edgeRepository::findByPipelineIdOrderByIdAsc);
            for (PipelineEdge edge : edges) {
                NodeRun from = byKey.get(edge.getFromNodeKey());
                NodeRun to = byKey.get(edge.getToNodeKey());
                if (from == null || to == null || from.getAgentName() == null || to.getAgentName() == null) {
                    continue;
                }
                long rows = from.getRowsOut() == null ? 0 : from.getRowsOut();
                AgentMessage m = new AgentMessage(run.getId(), run.getPipelineName(),
                        from.getAgentName(), to.getAgentName(), MessageType.DATA_HANDOFF, rows,
                        "数据交接: " + rows + " 行,「" + from.getAgentName() + "」→「" + to.getAgentName() + "」");
                m.setCreatedAt(to.getStartTime() == null ? from.getEndTime() : to.getStartTime());
                buffer.add(m);
            }
            if (buffer.size() >= BATCH) {
                messageRepository.saveAll(buffer);
                total += buffer.size();
                buffer.clear();
            }
        }
        if (!buffer.isEmpty()) {
            messageRepository.saveAll(buffer);
            total += buffer.size();
        }
        log.info("历史消息回填完成: {} 次运行, {} 条消息, 耗时 {}ms",
                runs.size(), total, System.currentTimeMillis() - t0);
    }
}
