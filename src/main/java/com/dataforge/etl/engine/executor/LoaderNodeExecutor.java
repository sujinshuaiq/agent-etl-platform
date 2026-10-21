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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 加载节点执行器:把上游行数真实写入 dwd_* 目标表。
 * 目标表不存在时自动创建通用结构(run_id / row_seq / loaded_at);
 * 为保护演示环境,单次实际落库行数封顶 20000(逻辑行数不受影响)。
 * 配置:{"targetTable":"dwd_users","writeMode":"INSERT"}
 */
@Component
public class LoaderNodeExecutor implements NodeExecutor {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z0-9_]+$");
    /** 单次实际落库上限 */
    private static final int MAX_PHYSICAL_ROWS = 20000;
    private static final int BATCH_SIZE = 1000;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public LoaderNodeExecutor(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentType supportType() {
        return AgentType.LOADER;
    }

    @Override
    public NodeOutput execute(NodeExecutionContext ctx) throws Exception {
        String json = ctx.getConfigJson();
        JsonNode config = (json == null || json.isBlank()) ? objectMapper.createObjectNode() : objectMapper.readTree(json);
        String targetTable = config.path("targetTable").asText("");
        if (targetTable.isBlank() || !SAFE_IDENTIFIER.matcher(targetTable).matches()) {
            throw BizException.badRequest("LOADER 节点缺少合法的 targetTable 配置: " + targetTable);
        }
        String writeMode = config.path("writeMode").asText("INSERT");

        long rowsIn = ctx.getRowsIn();
        ctx.log(LogLevel.INFO, "开始加载 " + rowsIn + " 行到目标表 " + targetTable + " (模式 " + writeMode + ")");

        // 目标表不存在则创建(通用宽表结构,演示用途)
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + targetTable + " ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "run_id BIGINT, "
                + "row_seq INT, "
                + "loaded_at TIMESTAMP)");

        // OVERWRITE 模式先清空目标表
        if ("OVERWRITE".equalsIgnoreCase(writeMode)) {
            jdbcTemplate.update("DELETE FROM " + targetTable);
            ctx.log(LogLevel.WARN, "OVERWRITE 模式:已清空目标表 " + targetTable);
        }

        // 真实批量写入(行数封顶,保护磁盘)
        long physical = Math.min(rowsIn, MAX_PHYSICAL_ROWS);
        long written = 0;
        while (written < physical) {
            ctx.checkCanceled();
            int batch = (int) Math.min(BATCH_SIZE, physical - written);
            List<Object[]> args = new ArrayList<>(batch);
            for (int i = 0; i < batch; i++) {
                args.add(new Object[]{ctx.getRunId(), (int) (written + i + 1), java.time.LocalDateTime.now()});
            }
            jdbcTemplate.batchUpdate("INSERT INTO " + targetTable + " (run_id, row_seq, loaded_at) VALUES (?, ?, ?)", args);
            written += batch;
        }

        ctx.simulateWork();
        String suffix = rowsIn > MAX_PHYSICAL_ROWS ? "(逻辑行数 " + rowsIn + ",实际落库封顶 " + MAX_PHYSICAL_ROWS + ")" : "";
        ctx.log(LogLevel.INFO, "加载完成: 写入 " + targetTable + " 共 " + rowsIn + " 行" + suffix);
        return NodeOutput.of(rowsIn, "写入 " + targetTable + " " + rowsIn + " 行");
    }
}
