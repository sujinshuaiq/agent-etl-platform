package com.dataforge.etl.ai.guard;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 约束校验器:工具白名单、参数边界、危险操作确认。
 */
@Component
public class ConstraintValidator {

    /** 允许调用的平台工具白名单 */
    public static final Set<String> TOOL_WHITELIST = Set.of(
            "get_platform_overview", "list_failed_runs", "get_run_logs",
            "list_agents", "diagnose_run", "trigger_pipeline", "generate_pipeline");

    /** 危险操作(触发管道)必须出现的意图词 */
    private static final Pattern TRIGGER_INTENT = Pattern.compile("触发|运行|执行|跑一下|启动");

    /** 校验结果 */
    public record ValidationResult(boolean allowed, List<String> violations) {
        public static ValidationResult ok() {
            return new ValidationResult(true, List.of());
        }

        public static ValidationResult reject(String reason) {
            return new ValidationResult(false, List.of(reason));
        }
    }

    /**
     * 校验一次工具调用。
     *
     * @param tool      工具名
     * @param arguments 参数
     * @param message   用户原始消息(危险操作确认用)
     */
    public ValidationResult validateToolCall(String tool, Map<String, Object> arguments, String message) {
        List<String> violations = new ArrayList<>();
        // 白名单
        if (!TOOL_WHITELIST.contains(tool)) {
            violations.add("工具不在白名单: " + tool);
            return new ValidationResult(false, violations);
        }
        // 参数边界
        if ("list_failed_runs".equals(tool)) {
            Object hours = arguments.get("hours");
            if (hours != null) {
                long h = Long.parseLong(hours.toString());
                if (h < 1 || h > 720) {
                    violations.add("hours 参数越界,合法范围 [1, 720]: " + h);
                }
            }
        }
        if (("get_run_logs".equals(tool) || "diagnose_run".equals(tool))) {
            Object runId = arguments.get("runId");
            if (runId == null || Long.parseLong(runId.toString()) <= 0) {
                violations.add("runId 必须为正整数");
            }
        }
        // 危险操作:触发管道必须有明确触发意图词
        if ("trigger_pipeline".equals(tool)) {
            Object pipelineId = arguments.get("pipelineId");
            if (pipelineId == null || Long.parseLong(pipelineId.toString()) <= 0) {
                violations.add("pipelineId 必须为正整数");
            }
            if (message == null || !TRIGGER_INTENT.matcher(message).find()) {
                violations.add("触发管道属于危险操作,消息中必须明确包含触发/运行意图");
            }
        }
        return new ValidationResult(violations.isEmpty(), violations);
    }
}
