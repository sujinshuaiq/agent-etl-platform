package com.dataforge.etl.run.entity;

import com.dataforge.etl.common.enums.LogLevel;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 运行日志:节点级(nodeRunId 非空)与运行级(nodeRunId 为空)日志。
 */
@Entity
@Table(name = "run_logs", indexes = {
        @Index(name = "idx_log_run", columnList = "runId"),
        @Index(name = "idx_log_node_run", columnList = "nodeRunId")
})
public class RunLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long runId;

    /** 所属节点运行;为空表示运行级日志 */
    private Long nodeRunId;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    @Enumerated(EnumType.STRING)
    @Column(name = "log_level", nullable = false)
    private LogLevel level = LogLevel.INFO;

    @Column(length = 2000)
    private String message;

    public RunLog() {
    }

    public RunLog(Long runId, Long nodeRunId, LogLevel level, String message) {
        this.runId = runId;
        this.nodeRunId = nodeRunId;
        this.timestamp = LocalDateTime.now();
        this.level = level;
        this.message = message;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRunId() {
        return runId;
    }

    public void setRunId(Long runId) {
        this.runId = runId;
    }

    public Long getNodeRunId() {
        return nodeRunId;
    }

    public void setNodeRunId(Long nodeRunId) {
        this.nodeRunId = nodeRunId;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public LogLevel getLevel() {
        return level;
    }

    public void setLevel(LogLevel level) {
        this.level = level;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
