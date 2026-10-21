package com.dataforge.etl.dashboard;

import com.dataforge.etl.agent.entity.Agent;
import com.dataforge.etl.agent.repository.AgentRepository;
import com.dataforge.etl.alert.repository.AlertRepository;
import com.dataforge.etl.common.enums.AgentStatus;
import com.dataforge.etl.common.enums.PipelineStatus;
import com.dataforge.etl.common.enums.RunStatus;
import com.dataforge.etl.datasource.repository.DataSourceRepository;
import com.dataforge.etl.pipeline.entity.Pipeline;
import com.dataforge.etl.pipeline.repository.PipelineRepository;
import com.dataforge.etl.quality.repository.QualityResultRepository;
import com.dataforge.etl.run.entity.PipelineRun;
import com.dataforge.etl.run.repository.PipelineRunRepository;
import com.dataforge.etl.run.service.RunService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 仪表盘聚合服务:在内存中对运行记录做聚合统计
 * (演示数据量级下远小于 SQL 分组成本,且逻辑更直观)。
 */
@Service
public class DashboardService {

    private final AgentRepository agentRepository;
    private final DataSourceRepository dataSourceRepository;
    private final PipelineRepository pipelineRepository;
    private final PipelineRunRepository runRepository;
    private final AlertRepository alertRepository;
    private final QualityResultRepository qualityResultRepository;
    private final RunService runService;

    public DashboardService(AgentRepository agentRepository,
                            DataSourceRepository dataSourceRepository,
                            PipelineRepository pipelineRepository,
                            PipelineRunRepository runRepository,
                            AlertRepository alertRepository,
                            QualityResultRepository qualityResultRepository,
                            RunService runService) {
        this.agentRepository = agentRepository;
        this.dataSourceRepository = dataSourceRepository;
        this.pipelineRepository = pipelineRepository;
        this.runRepository = runRepository;
        this.alertRepository = alertRepository;
        this.qualityResultRepository = qualityResultRepository;
        this.runService = runService;
    }

    /** 总览统计卡片 */
    public Map<String, Object> overview() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(7);
        List<PipelineRun> recent = runRepository.findByStartTimeAfter(sevenDaysAgo);

        // 今日成功/失败
        long successToday = recent.stream()
                .filter(r -> !r.getStartTime().isBefore(todayStart) && r.getStatus() == RunStatus.SUCCESS)
                .count();
        long failedToday = recent.stream()
                .filter(r -> !r.getStartTime().isBefore(todayStart) && r.getStatus() == RunStatus.FAILED)
                .count();
        // 今日处理行数
        long rowsToday = recent.stream()
                .filter(r -> !r.getStartTime().isBefore(todayStart))
                .mapToLong(r -> r.getRowsWritten() == null ? 0 : r.getRowsWritten())
                .sum();
        // 近 7 天成功率(已结束的运行)
        List<PipelineRun> finished7d = recent.stream()
                .filter(r -> r.getStatus() == RunStatus.SUCCESS || r.getStatus() == RunStatus.FAILED
                        || r.getStatus() == RunStatus.CANCELED || r.getStatus() == RunStatus.PARTIAL_SUCCESS)
                .toList();
        long success7d = finished7d.stream().filter(r -> r.getStatus() == RunStatus.SUCCESS).count();
        double successRate7d = finished7d.isEmpty() ? 0.0 : round1(success7d * 100.0 / finished7d.size());
        // 近 7 天平均耗时
        double avgDuration7d = finished7d.stream()
                .mapToLong(r -> r.getDurationMs() == null ? 0 : r.getDurationMs())
                .average().orElse(0);

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("agentTotal", agentRepository.count());
        overview.put("agentOnline", agentRepository.countByStatus(AgentStatus.ONLINE));
        overview.put("datasourceTotal", dataSourceRepository.count());
        overview.put("pipelineTotal", pipelineRepository.count());
        overview.put("pipelineEnabled", pipelineRepository.findAll().stream()
                .filter(p -> p.getStatus() == PipelineStatus.ENABLED).count());
        overview.put("runTotal", runRepository.count());
        overview.put("runRunning", runRepository.countByStatus(RunStatus.RUNNING));
        overview.put("runSuccessToday", successToday);
        overview.put("runFailedToday", failedToday);
        overview.put("successRate7d", successRate7d);
        overview.put("avgDurationMs7d", Math.round(avgDuration7d));
        overview.put("rowsProcessedToday", rowsToday);
        overview.put("alertUnread", alertRepository.countByReadFalse());
        return overview;
    }

    /** 最近 N 天运行趋势:每天 成功/失败/总数/处理行数 */
    public List<Map<String, Object>> runTrend(int days) {
        LocalDate startDate = LocalDate.now().minusDays(days - 1L);
        List<PipelineRun> runs = runRepository.findByStartTimeAfter(startDate.atStartOfDay());
        Map<LocalDate, List<PipelineRun>> byDate = runs.stream()
                .filter(r -> r.getStartTime() != null)
                .collect(Collectors.groupingBy(r -> r.getStartTime().toLocalDate()));

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate date = startDate.plusDays(i);
            List<PipelineRun> dayRuns = byDate.getOrDefault(date, List.of());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", date.format(fmt));
            item.put("success", dayRuns.stream().filter(r -> r.getStatus() == RunStatus.SUCCESS).count());
            item.put("failed", dayRuns.stream()
                    .filter(r -> r.getStatus() == RunStatus.FAILED || r.getStatus() == RunStatus.PARTIAL_SUCCESS).count());
            item.put("total", dayRuns.size());
            item.put("rowsProcessed", dayRuns.stream()
                    .mapToLong(r -> r.getRowsWritten() == null ? 0 : r.getRowsWritten()).sum());
            trend.add(item);
        }
        return trend;
    }

    /** 运行状态分布:{SUCCESS:n, FAILED:n, ...} */
    public Map<String, Long> statusDistribution() {
        Map<String, Long> distribution = new LinkedHashMap<>();
        for (RunStatus status : RunStatus.values()) {
            distribution.put(status.name(), 0L);
        }
        for (PipelineRun run : runRepository.findAll()) {
            distribution.merge(run.getStatus().name(), 1L, Long::sum);
        }
        return distribution;
    }

    /** Agent 负载:并发槽位、累计执行、成功率、平均耗时 */
    public List<Map<String, Object>> agentLoad() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Agent agent : agentRepository.findAll()) {
            long success = agent.getSuccessCount() == null ? 0 : agent.getSuccessCount();
            long fail = agent.getFailCount() == null ? 0 : agent.getFailCount();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("agentId", agent.getId());
            item.put("agentName", agent.getName());
            item.put("type", agent.getType());
            item.put("runningTasks", agent.getCurrentTasks() == null ? 0 : agent.getCurrentTasks());
            item.put("totalExecuted", agent.getTotalExecuted() == null ? 0 : agent.getTotalExecuted());
            item.put("successRate", (success + fail) == 0 ? 0.0 : round1(success * 100.0 / (success + fail)));
            item.put("avgDurationMs", agent.getAvgDurationMs() == null ? 0 : agent.getAvgDurationMs());
            result.add(item);
        }
        return result;
    }

    /** 管道运行排行:按运行次数倒序 */
    public List<Map<String, Object>> pipelineRank(int limit) {
        List<PipelineRun> all = runRepository.findAll();
        Map<Long, List<PipelineRun>> byPipeline = all.stream()
                .collect(Collectors.groupingBy(PipelineRun::getPipelineId));
        Map<Long, String> pipelineNames = new HashMap<>();
        for (Pipeline p : pipelineRepository.findAll()) {
            pipelineNames.put(p.getId(), p.getName());
        }
        return byPipeline.entrySet().stream()
                .map(entry -> {
                    List<PipelineRun> runs = entry.getValue();
                    long success = runs.stream().filter(r -> r.getStatus() == RunStatus.SUCCESS).count();
                    double avgRows = runs.stream()
                            .mapToLong(r -> r.getRowsWritten() == null ? 0 : r.getRowsWritten()).average().orElse(0);
                    Map<String, Object> item = new LinkedHashMap<String, Object>();
                    item.put("pipelineId", entry.getKey());
                    item.put("pipelineName", pipelineNames.getOrDefault(entry.getKey(),
                            runs.isEmpty() ? "未知管道" : runs.get(0).getPipelineName()));
                    item.put("runCount", (long) runs.size());
                    item.put("successRate", round1(success * 100.0 / runs.size()));
                    item.put("avgRows", Math.round(avgRows));
                    return item;
                })
                .sorted((a, b) -> Long.compare((long) b.get("runCount"), (long) a.get("runCount")))
                .limit(Math.max(1, limit))
                .toList();
    }

    /** 最近运行摘要 */
    public List<Map<String, Object>> recentRuns(int limit) {
        return runRepository.findTop10ByOrderByIdDesc().stream()
                .limit(Math.max(1, limit))
                .map(runService::toListItem)
                .toList();
    }

    /** 质量检查通过率汇总 */
    public Map<String, Object> qualitySummary() {
        long total = qualityResultRepository.count();
        long passed = qualityResultRepository.countByPassed(true);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalChecks", total);
        summary.put("passed", passed);
        summary.put("failed", total - passed);
        summary.put("passRate", total == 0 ? 0.0 : round1(passed * 100.0 / total));
        return summary;
    }

    /** 保留一位小数 */
    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
