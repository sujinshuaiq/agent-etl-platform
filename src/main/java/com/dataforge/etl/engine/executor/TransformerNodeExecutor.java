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
 * 转换节点执行器:按配置规则模拟清洗(trim/upper/mask/dedup 等),
 * 并按 filterRatio 过滤行数(rowsOut = rowsIn × (1 - filterRatio))。
 * 配置:{"rules":["trim","upper:email","mask:phone","dedup"],"filterRatio":0.05}
 */
@Component
public class TransformerNodeExecutor implements NodeExecutor {

    private final ObjectMapper objectMapper;

    public TransformerNodeExecutor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentType supportType() {
        return AgentType.TRANSFORMER;
    }

    @Override
    public NodeOutput execute(NodeExecutionContext ctx) throws Exception {
        String json = ctx.getConfigJson();
        JsonNode config = (json == null || json.isBlank()) ? objectMapper.createObjectNode() : objectMapper.readTree(json);

        long rowsIn = ctx.getRowsIn();
        double filterRatio = Math.min(0.9, Math.max(0, config.path("filterRatio").asDouble(0.05)));

        // 记录应用的转换规则
        if (config.path("rules").isArray()) {
            for (JsonNode rule : config.path("rules")) {
                ctx.checkCanceled();
                ctx.log(LogLevel.INFO, "应用转换规则: " + rule.asText());
            }
        }

        ctx.log(LogLevel.INFO, "开始转换,输入 " + rowsIn + " 行,过滤比例 " + (filterRatio * 100) + "%");
        ctx.simulateWork();

        long rowsOut = Math.round(rowsIn * (1 - filterRatio));
        long filtered = rowsIn - rowsOut;
        ctx.log(LogLevel.INFO, "转换完成: 输出 " + rowsOut + " 行,过滤 " + filtered + " 行");
        return new NodeOutput(rowsOut, filtered, "转换 " + rowsIn + " → " + rowsOut + " 行");
    }
}
