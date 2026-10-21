package com.dataforge.etl.schedule.controller;

import com.dataforge.etl.common.api.ApiResponse;
import com.dataforge.etl.schedule.entity.ScheduleJob;
import com.dataforge.etl.schedule.service.ScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 调度任务接口:/api/schedules
 */
@Tag(name = "调度中心")
@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {

    private final ScheduleService scheduleService;

    public ScheduleController(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @Operation(summary = "调度任务列表")
    @GetMapping
    public ApiResponse<List<ScheduleJob>> list() {
        return ApiResponse.ok(scheduleService.listAll());
    }

    @Operation(summary = "调度任务详情")
    @GetMapping("/{id}")
    public ApiResponse<ScheduleJob> detail(@PathVariable Long id) {
        return ApiResponse.ok(scheduleService.getById(id));
    }

    @Operation(summary = "新增调度任务")
    @PostMapping
    public ApiResponse<ScheduleJob> create(@Valid @RequestBody ScheduleJob job) {
        return ApiResponse.ok(scheduleService.create(job));
    }

    @Operation(summary = "更新调度任务")
    @PutMapping("/{id}")
    public ApiResponse<ScheduleJob> update(@PathVariable Long id, @Valid @RequestBody ScheduleJob job) {
        return ApiResponse.ok(scheduleService.update(id, job));
    }

    @Operation(summary = "删除调度任务")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        scheduleService.delete(id);
        return ApiResponse.ok();
    }

    @Operation(summary = "启用调度")
    @PostMapping("/{id}/enable")
    public ApiResponse<ScheduleJob> enable(@PathVariable Long id) {
        return ApiResponse.ok(scheduleService.enable(id));
    }

    @Operation(summary = "停用调度")
    @PostMapping("/{id}/disable")
    public ApiResponse<ScheduleJob> disable(@PathVariable Long id) {
        return ApiResponse.ok(scheduleService.disable(id));
    }
}
