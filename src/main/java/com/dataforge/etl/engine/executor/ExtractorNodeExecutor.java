package com.dataforge.etl.engine.executor;

import com.dataforge.etl.common.enums.AgentType;
import com.dataforge.etl.common.enums.LogLevel;
import com.dataforge.etl.common.exception.BizException;
import com.dataforge.etl.engine.NodeExecutionContext;
import com.dataforge.etl.engine.NodeExecutor;
import com.dataforge.etl.engine.NodeOutput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 抽取节点执行器:真实统计 H2 源表行数(可带 whereClause 过滤),作为下游输入。
 * 配置:{"sourceTable":"ods_users","batchSize":1000,"whereClause":"..."}
 */
@Component
public class ExtractorNodeExecutor implements NodeExecutor {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z0-9_]+$");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ExtractorNodeExecutor(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentType supportType() {
        return AgentType.EXTRACTOR;
    }

    @Override
    public NodeOutput execute(NodeExecutionContext ctx) throws Exception {
        JsonNode config = readConfig(ctx);
        String table = config.path("sourceTable").asText("");
        if (table.isBlank() || !SAFE_IDENTIFIER.matcher(table).matches()) {
            throw BizException.badRequest("EXTRACTOR 节点缺少合法的 sourceTable 配置: " + table);
        }
        int batchSize = Math.max(1, config.path("batchSize").asInt(1000));
        String whereClause = config.path("whereClause").asText("").trim();

        ctx.log(LogLevel.INFO, "开始从源表 " + table + " 抽取数据,批量大小 " + batchSize);

        // 真实统计源表行数
        String sql = "SELECT COUNT(*) FROM " + table;
        if (!whereClause.isEmpty()) {
            // where 子句只允许简单条件字符,防注入
            if (!whereClause.matches("^[A-Za-z0-9_\\s=<>!'%.()\\-,/*+\"]+$")) {
                throw BizException.badRequest("whereClause 含非法字符");
            }
            sql += " WHERE " + whereClause;
        }
        Long total = jdbcTemplate.queryForObject(sql, Long.class);
        long rows = total == null ? 0 : total;
        ctx.log(LogLevel.INFO, "源表统计完成: " + table + " 共 " + rows + " 行");

        // 模拟分批抽取过程
        long batches = Math.max(1, (rows + batchSize - 1) / batchSize);
        ctx.simulateWork();
        ctx.log(LogLevel.INFO, "抽取完成: 共 " + batches + " 个批次," + rows + " 行");
        return NodeOutput.of(rows, "从 " + table + " 抽取 " + rows + " 行");
    }

    private JsonNode readConfig(NodeExecutionContext ctx) throws Exception {
        String json = ctx.getConfigJson();
        return (json == null || json.isBlank()) ? objectMapper.createObjectNode() : objectMapper.readTree(json);
    }
}
