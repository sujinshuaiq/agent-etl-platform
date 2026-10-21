package com.dataforge.etl.run.entity;

import com.dataforge.etl.common.enums.AgentType;
import com.dataforge.etl.common.enums.NodeRunStatus;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 节点运行记录:一次管道运行中单个节点的执行情况。
 * 通过 runId 关联管道运行(不用 JPA 关联,方便异步线程独立写入)。
 */
@Entity
@Table(name = "node_runs", indexes = {
        @Index(name = "idx_node_run_run", columnList = "runId")
})
public class NodeRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long runId;

    @Column(nullable = false)
    private String nodeKey;

    private String nodeName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AgentType agentType;

    /** 实际执行该节点的 Agent 名称 */
    private String agentName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NodeRunStatus status = NodeRunStatus.PENDING;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Long durationMs = 0L;

    /** 输入行数(上游节点 rowsOut 之和) */
    private Long rowsIn = 0L;

    /** 输出行数 */
    private Long rowsOut = 0L;

    /** 已重试次数 */
    private Integer retryAttempt = 0;

    @Column(length = 2000)
    private String errorMessage;

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

    public String getNodeKey() {
        return nodeKey;
    }

    public void setNodeKey(String nodeKey) {
        this.nodeKey = nodeKey;
    }

    public String getNodeName() {
        return nodeName;
    }

    public void setNodeName(String nodeName) {
        this.nodeName = nodeName;
    }

    public AgentType getAgentType() {
        return agentType;
    }

    public void setAgentType(AgentType agentType) {
        this.agentType = agentType;
    }

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public NodeRunStatus getStatus() {
        return status;
    }

    public void setStatus(NodeRunStatus status) {
        this.status = status;
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

    public Long getRowsIn() {
        return rowsIn;
    }

    public void setRowsIn(Long rowsIn) {
        this.rowsIn = rowsIn;
    }

    public Long getRowsOut() {
        return rowsOut;
    }

    public void setRowsOut(Long rowsOut) {
        this.rowsOut = rowsOut;
    }

    public Integer getRetryAttempt() {
        return retryAttempt;
    }

    public void setRetryAttempt(Integer retryAttempt) {
        this.retryAttempt = retryAttempt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
