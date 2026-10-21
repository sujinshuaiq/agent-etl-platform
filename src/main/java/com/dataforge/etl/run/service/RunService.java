package com.dataforge.etl.run.service;

import com.dataforge.etl.common.enums.RunStatus;
import com.dataforge.etl.common.exception.BizException;
import com.dataforge.etl.run.entity.NodeRun;
import com.dataforge.etl.run.entity.PipelineRun;
import com.dataforge.etl.run.entity.RunLog;
import com.dataforge.etl.run.repository.NodeRunRepository;
import com.dataforge.etl.run.repository.PipelineRunRepository;
import com.dataforge.etl.run.repository.RunLogRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行记录查询服务:列表分页/筛选、详情、日志,以及对外统一的运行摘要 DTO 整形。
 */
@Service
public class RunService {

    private final PipelineRunRepository runRepository;
    private final NodeRunRepository nodeRunRepository;
    private final RunLogRepository runLogRepository;

    public RunService(PipelineRunRepository runRepository,
                      NodeRunRepository nodeRunRepository,
                      RunLogRepository runLogRepository) {
        this.runRepository = runRepository;
        this.nodeRunRepository = nodeRunRepository;
        this.runLogRepository = runLogRepository;
    }

    public PipelineRun getById(Long id) {
        return runRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("运行记录不存在: id=" + id));
    }

    /** 分页查询:支持按状态与管道过滤,按开始时间倒序 */
    public Page<PipelineRun> page(RunStatus status, Long pipelineId, int page, int size) {
        Specification<PipelineRun> spec = (root, q, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (pipelineId != null) {
                predicates.add(cb.equal(root.get("pipelineId"), pipelineId));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return runRepository.findAll(spec,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
    }

    /** 运行详情:运行实体字段 + nodeRuns 列表 */
    public Map<String, Object> detail(Long id) {
        PipelineRun run = getById(id);
        Map<String, Object> detail = toListItem(run);
        detail.put("nodeRuns", nodeRunRepository.findByRunIdOrderByIdAsc(id));
        return detail;
    }

    /** 运行日志(按时间升序),可按节点过滤 */
    public List<RunLog> logs(Long runId, Long nodeRunId) {
        getById(runId); // 校验存在
        if (nodeRunId != null) {
            return runLogRepository.findByRunIdAndNodeRunIdOrderByTimestampAscIdAsc(runId, nodeRunId);
        }
        return runLogRepository.findByRunIdOrderByTimestampAscIdAsc(runId);
    }

    /** 运行摘要(契约 4.4 列表项形状),REST 与 SSE 共用 */
    public Map<String, Object> toListItem(PipelineRun run) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", run.getId());
        item.put("pipelineId", run.getPipelineId());
        item.put("pipelineName", run.getPipelineName());
        item.put("status", run.getStatus());
        item.put("triggerType", run.getTriggerType());
        item.put("startTime", run.getStartTime());
        item.put("endTime", run.getEndTime());
        item.put("durationMs", run.getDurationMs());
        item.put("rowsRead", run.getRowsRead());
        item.put("rowsWritten", run.getRowsWritten());
        item.put("rowsFiltered", run.getRowsFiltered());
        item.put("progress", run.getProgress());
        item.put("errorMessage", run.getErrorMessage());
        return item;
    }
}
