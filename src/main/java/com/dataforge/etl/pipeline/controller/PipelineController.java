package com.dataforge.etl.pipeline.controller;

import com.dataforge.etl.common.api.ApiResponse;
import com.dataforge.etl.common.enums.TriggerType;
import com.dataforge.etl.engine.PipelineEngine;
import com.dataforge.etl.pipeline.entity.Pipeline;
import com.dataforge.etl.pipeline.service.PipelineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管道管理接口:/api/pipelines
 */
@Tag(name = "管道编排")
@RestController
@RequestMapping("/api/pipelines")
public class PipelineController {

    private final PipelineService pipelineService;
    private final PipelineEngine pipelineEngine;

    public PipelineController(PipelineService pipelineService, PipelineEngine pipelineEngine) {
        this.pipelineService = pipelineService;
        this.pipelineEngine = pipelineEngine;
    }

    @Operation(summary = "管道列表(摘要)")
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(pipelineService.listSummaries());
    }

    @Operation(summary = "管道详情(含 nodes/edges)")
    @GetMapping("/{id}")
    public ApiResponse<Pipeline> detail(@PathVariable Long id) {
        return ApiResponse.ok(pipelineService.getDetail(id));
    }

    @Operation(summary = "新增管道(整棵 DAG 提交)")
    @PostMapping
    public ApiResponse<Pipeline> create(@Valid @RequestBody Pipeline pipeline) {
        Pipeline saved = pipelineService.create(pipeline);
        return ApiResponse.ok(pipelineService.getDetail(saved.getId()));
    }

    @Operation(summary = "更新管道(整棵 DAG 替换)")
    @PutMapping("/{id}")
    public ApiResponse<Pipeline> update(@PathVariable Long id, @Valid @RequestBody Pipeline pipeline) {
        Pipeline saved = pipelineService.update(id, pipeline);
        return ApiResponse.ok(pipelineService.getDetail(saved.getId()));
    }

    @Operation(summary = "删除管道")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        pipelineService.delete(id);
        return ApiResponse.ok();
    }

    @Operation(summary = "启用管道")
    @PostMapping("/{id}/enable")
    public ApiResponse<Pipeline> enable(@PathVariable Long id) {
        return ApiResponse.ok(pipelineService.enable(id));
    }

    @Operation(summary = "停用管道")
    @PostMapping("/{id}/disable")
    public ApiResponse<Pipeline> disable(@PathVariable Long id) {
        return ApiResponse.ok(pipelineService.disable(id));
    }

    @Operation(summary = "拓扑校验(环检测)")
    @PostMapping("/{id}/validate")
    public ApiResponse<Map<String, Object>> validate(@PathVariable Long id) {
        return ApiResponse.ok(pipelineService.validate(id));
    }

    @Operation(summary = "手动触发运行(异步)")
    @PostMapping("/{id}/trigger")
    public ApiResponse<Map<String, Object>> trigger(@PathVariable Long id) {
        Long runId = pipelineEngine.trigger(id, TriggerType.MANUAL);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", runId);
        return ApiResponse.ok(result);
    }
}
