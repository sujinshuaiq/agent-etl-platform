package com.dataforge.etl.dashboard;

import com.dataforge.etl.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 仪表盘接口:/api/dashboard
 */
@Tag(name = "仪表盘")
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @Operation(summary = "总览统计")
    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        return ApiResponse.ok(dashboardService.overview());
    }

    @Operation(summary = "最近 N 天运行趋势")
    @GetMapping("/run-trend")
    public ApiResponse<List<Map<String, Object>>> runTrend(@RequestParam(defaultValue = "14") int days) {
        return ApiResponse.ok(dashboardService.runTrend(days));
    }

    @Operation(summary = "运行状态分布")
    @GetMapping("/status-distribution")
    public ApiResponse<Map<String, Long>> statusDistribution() {
        return ApiResponse.ok(dashboardService.statusDistribution());
    }

    @Operation(summary = "Agent 负载")
    @GetMapping("/agent-load")
    public ApiResponse<List<Map<String, Object>>> agentLoad() {
        return ApiResponse.ok(dashboardService.agentLoad());
    }

    @Operation(summary = "管道运行排行")
    @GetMapping("/pipeline-rank")
    public ApiResponse<List<Map<String, Object>>> pipelineRank(@RequestParam(defaultValue = "5") int limit) {
        return ApiResponse.ok(dashboardService.pipelineRank(limit));
    }

    @Operation(summary = "最近运行")
    @GetMapping("/recent-runs")
    public ApiResponse<List<Map<String, Object>>> recentRuns(@RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(dashboardService.recentRuns(limit));
    }

    @Operation(summary = "质量检查通过率")
    @GetMapping("/quality-summary")
    public ApiResponse<Map<String, Object>> qualitySummary() {
        return ApiResponse.ok(dashboardService.qualitySummary());
    }
}
