package com.dataforge.etl.engine.executor;

import com.dataforge.etl.common.enums.AgentType;
import com.dataforge.etl.common.enums.LogLevel;
import com.dataforge.etl.common.enums.Severity;
import com.dataforge.etl.engine.NodeExecutionContext;
import com.dataforge.etl.engine.NodeExecutor;
import com.dataforge.etl.engine.NodeOutput;
import com.dataforge.etl.quality.entity.QualityResult;
import com.dataforge.etl.quality.entity.QualityRule;
import com.dataforge.etl.quality.repository.QualityRuleRepository;
import com.dataforge.etl.quality.service.QualityService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 质量检查节点执行器:按配置的 ruleIds 逐条对 H2 业务表执行真实检查,
 * 结果落库;存在 ERROR/CRITICAL 级别未通过项时节点失败。
 * 配置:{"ruleIds":[1,2]}
 */
@Component
public class QualityCheckerNodeExecutor implements NodeExecutor {

    private final QualityRuleRepository ruleRepository;
    private final QualityService qualityService;
    private final ObjectMapper objectMapper;

    public QualityCheckerNodeExecutor(QualityRuleRepository ruleRepository,
                                      QualityService qualityService,
                                      ObjectMapper objectMapper) {
        this.ruleRepository = ruleRepository;
        this.qualityService = qualityService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentType supportType() {
        return AgentType.QUALITY_CHECKER;
    }

    @Override
    public NodeOutput execute(NodeExecutionContext ctx) throws Exception {
        String json = ctx.getConfigJson();
        JsonNode config = (json == null || json.isBlank()) ? objectMapper.createObjectNode() : objectMapper.readTree(json);

        List<Long> ruleIds = new ArrayList<>();
        if (config.path("ruleIds").isArray()) {
            for (JsonNode idNode : config.path("ruleIds")) {
                ruleIds.add(idNode.asLong());
            }
        }
        if (ruleIds.isEmpty()) {
            ctx.log(LogLevel.WARN, "未配置 ruleIds,跳过质量检查");
            return NodeOutput.of(ctx.getRowsIn(), "无质量规则,直接通过");
        }

        int passed = 0;
        int failed = 0;
        List<String> criticalFailures = new ArrayList<>();
        for (Long ruleId : ruleIds) {
            ctx.checkCanceled();
            QualityRule rule = ruleRepository.findById(ruleId).orElse(null);
            if (rule == null) {
                ctx.log(LogLevel.WARN, "质量规则不存在,跳过: id=" + ruleId);
                continue;
            }
            if (Boolean.FALSE.equals(rule.getEnabled())) {
                ctx.log(LogLevel.INFO, "规则已停用,跳过: " + rule.getName());
                continue;
            }
            ctx.log(LogLevel.INFO, "执行质量检查: " + rule.getName());
            QualityResult result = qualityService.executeCheck(rule, ctx.getRunId(), ctx.getNodeName());
            if (Boolean.TRUE.equals(result.getPassed())) {
                passed++;
                ctx.log(LogLevel.INFO, "检查通过: " + rule.getName() + " (" + result.getActual() + ")");
            } else {
                failed++;
                ctx.log(LogLevel.ERROR, "检查未通过: " + rule.getName()
                        + " (期望 " + result.getExpected() + ",实际 " + result.getActual() + ")");
                if (rule.getSeverity() == Severity.ERROR || rule.getSeverity() == Severity.CRITICAL) {
                    criticalFailures.add(rule.getName());
                }
            }
        }
        ctx.simulateWork();
        ctx.log(LogLevel.INFO, "质量检查完成: 通过 " + passed + " 项,未通过 " + failed + " 项");
        if (!criticalFailures.isEmpty()) {
            throw new IllegalStateException("存在严重级别质量检查未通过: " + String.join(", ", criticalFailures));
        }
        return NodeOutput.of(ctx.getRowsIn(), "质量检查 通过 " + passed + " / 未通过 " + failed);
    }
}
