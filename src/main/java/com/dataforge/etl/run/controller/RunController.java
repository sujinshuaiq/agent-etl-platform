package com.dataforge.etl.run.controller;

import com.dataforge.etl.common.api.ApiResponse;
import com.dataforge.etl.common.api.PageResult;
import com.dataforge.etl.common.enums.RunStatus;
import com.dataforge.etl.common.enums.TriggerType;
import com.dataforge.etl.common.exception.BizException;
import com.dataforge.etl.engine.PipelineEngine;
import com.dataforge.etl.engine.RunEventPublisher;
import com.dataforge.etl.run.entity.PipelineRun;
import com.dataforge.etl.run.entity.RunLog;
import com.dataforge.etl.run.service.RunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行监控接口:/api/runs
 */
@Tag(name = "运行监控")
@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final RunService runService;
    private final PipelineEngine pipelineEngine;
    private final RunEventPublisher eventPublisher;

    public RunController(RunService runService, PipelineEngine pipelineEngine, RunEventPublisher eventPublisher) {
        this.runService = runService;
        this.pipelineEngine = pipelineEngine;
        this.eventPublisher = eventPublisher;
    }

    @Operation(summary = "运行记录分页(状态/管道筛选)")
    @GetMapping
    public ApiResponse<PageResult<Map<String, Object>>> list(@RequestParam(required = false) String status,
                                                             @RequestParam(required = false) Long pipelineId,
                                                             @RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(defaultValue = "20") int size) {
        RunStatus runStatus = parseStatus(status);
        Page<PipelineRun> result = runService.page(runStatus, pipelineId, page, size);
        return ApiResponse.ok(PageResult.from(result, runService::toListItem));
    }

    @Operation(summary = "运行详情(含 nodeRuns)")
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(runService.detail(id));
    }

    @Operation(summary = "运行日志(按时间升序)")
    @GetMapping("/{id}/logs")
    public ApiResponse<List<RunLog>> logs(@PathVariable Long id,
                                          @RequestParam(required = false) Long nodeRunId) {
        return ApiResponse.ok(runService.logs(id, nodeRunId));
    }

    @Operation(summary = "取消运行")
    @PostMapping("/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable Long id) {
        pipelineEngine.requestCancel(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", id);
        return ApiResponse.ok(result);
    }

    @Operation(summary = "重跑(以 RETRY 触发方式新建一次运行)")
    @PostMapping("/{id}/rerun")
    public ApiResponse<Map<String, Object>> rerun(@PathVariable Long id) {
        PipelineRun run = runService.getById(id);
        Long newRunId = pipelineEngine.trigger(run.getPipelineId(), TriggerType.RETRY);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", newRunId);
        return ApiResponse.ok(result);
    }

    @Operation(summary = "运行事件流(SSE):RUN_UPDATE / NODE_UPDATE / LOG")
    @GetMapping("/stream")
    public SseEmitter stream() {
        return eventPublisher.subscribe();
    }

    /** 解析状态过滤参数,非法值抛出业务异常 */
    private RunStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return RunStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest("非法的运行状态: " + status);
        }
    }
}
