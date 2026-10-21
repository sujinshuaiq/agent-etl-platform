package com.dataforge.etl.engine.executor;

import com.dataforge.etl.common.enums.AgentType;
import com.dataforge.etl.common.enums.LogLevel;
import com.dataforge.etl.engine.NodeExecutionContext;
import com.dataforge.etl.engine.NodeExecutor;
import com.dataforge.etl.engine.NodeOutput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 编排节点执行器:纯协调角色(流程入口/汇合点),透传行数,不处理数据。
 * 配置:{"note":"..."}
 */
@Component
public class OrchestratorNodeExecutor implements NodeExecutor {

    private final ObjectMapper objectMapper;

    public OrchestratorNodeExecutor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentType supportType() {
        return AgentType.ORCHESTRATOR;
    }

    @Override
    public NodeOutput execute(NodeExecutionContext ctx) throws Exception {
        String json = ctx.getConfigJson();
        JsonNode config = (json == null || json.isBlank()) ? objectMapper.createObjectNode() : objectMapper.readTree(json);
        String note = config.path("note").asText("");
        ctx.log(LogLevel.INFO, "编排节点执行" + (note.isBlank() ? "" : ": " + note));
        // 编排节点只做短暂协调,耗时取区间下限附近
        ctx.sleepInterruptibly(Math.min(300, ctx.getMinDurationMs() + 100));
        return NodeOutput.of(ctx.getRowsIn(), "编排协调完成");
    }
}
