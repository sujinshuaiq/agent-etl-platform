package com.dataforge.etl.meta;

import com.dataforge.etl.common.api.ApiResponse;
import com.dataforge.etl.common.enums.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 元数据接口:向前端提供全部枚举的可选值(大写字符串)。
 */
@Tag(name = "元数据")
@RestController
@RequestMapping("/api/meta")
public class MetaController {

    @Operation(summary = "全部枚举值")
    @GetMapping("/enums")
    public ApiResponse<Map<String, List<String>>> enums() {
        Map<String, List<String>> enums = new LinkedHashMap<>();
        enums.put("agentTypes", names(AgentType.values()));
        enums.put("agentStatuses", names(AgentStatus.values()));
        enums.put("datasourceTypes", names(DataSourceType.values()));
        enums.put("pipelineStatuses", names(PipelineStatus.values()));
        enums.put("runStatuses", names(RunStatus.values()));
        enums.put("checkTypes", names(CheckType.values()));
        enums.put("severities", names(Severity.values()));
        enums.put("failureStrategies", names(FailureStrategy.values()));
        enums.put("triggerTypes", names(TriggerType.values()));
        return ApiResponse.ok(enums);
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }
}
