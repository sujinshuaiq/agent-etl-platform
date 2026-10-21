package com.dataforge.etl.run.entity;

import com.dataforge.etl.common.enums.RunStatus;
import com.dataforge.etl.common.enums.TriggerType;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 管道运行记录:一次管道执行的整体状态与行数统计。
 * nodeRuns 通过 runId 反查,不建立 JPA 关联(执行引擎在异步线程中写入,避免懒加载问题)。
 */
@Entity
@Table(name = "pipeline_runs", indexes = {
        @Index(name = "idx_run_pipeline", columnList = "pipelineId"),
        @Index(name = "idx_run_status", columnList = "status"),
        @Index(name = "idx_run_start_time", columnList = "startTime")
})
public class PipelineRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long pipelineId;

    @Column(nullable = false)
    private String pipelineName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status = RunStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TriggerType triggerType = TriggerType.MANUAL;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    /** 总耗时(毫秒) */
    private Long durationMs = 0L;

    /** 读取总行数(抽取节点 rowsOut 之和) */
    private Long rowsRead = 0L;

    /** 写入总行数(加载节点 rowsOut 之和) */
    private Long rowsWritten = 0L;

    /** 被过滤的总行数(转换节点 rowsIn-rowsOut 之和) */
    private Long rowsFiltered = 0L;

    /** 进度 0~100(按完成节点数计算) */
    private Integer progress = 0;

    @Column(length = 2000)
    private String errorMessage;

    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPipelineId() {
        return pipelineId;
    }

    public void setPipelineId(Long pipelineId) {
        this.pipelineId = pipelineId;
    }

    public String getPipelineName() {
        return pipelineName;
    }

    public void setPipelineName(String pipelineName) {
        this.pipelineName = pipelineName;
    }

    public RunStatus getStatus() {
        return status;
    }

    public void setStatus(RunStatus status) {
        this.status = status;
    }

    public TriggerType getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(TriggerType triggerType) {
        this.triggerType = triggerType;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public Long getRowsRead() {
        return rowsRead;
    }

    public void setRowsRead(Long rowsRead) {
        this.rowsRead = rowsRead;
    }

    public Long getRowsWritten() {
        return rowsWritten;
    }

    public void setRowsWritten(Long rowsWritten) {
        this.rowsWritten = rowsWritten;
    }

    public Long getRowsFiltered() {
        return rowsFiltered;
    }

    public void setRowsFiltered(Long rowsFiltered) {
        this.rowsFiltered = rowsFiltered;
    }

    public Integer getProgress() {
        return progress;
    }

    public void setProgress(Integer progress) {
        this.progress = progress;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
