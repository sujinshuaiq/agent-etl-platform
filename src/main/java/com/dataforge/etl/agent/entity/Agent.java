package com.dataforge.etl.agent.entity;

import com.dataforge.etl.common.enums.AgentStatus;
import com.dataforge.etl.common.enums.AgentType;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Agent 实体:平台中的执行单元,按类型承担 ETL 各环节职责。
 * capabilities 在库中以逗号分隔字符串存储,对外 JSON 暴露为字符串数组。
 */
@Entity
@Table(name = "agents")
public class Agent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Agent 显示名称 */
    @NotBlank(message = "名称不能为空")
    @Column(nullable = false)
    private String name;

    /** Agent 编码,全局唯一 */
    @NotBlank(message = "编码不能为空")
    @Column(nullable = false, unique = true)
    private String code;

    @NotNull(message = "类型不能为空")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AgentType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AgentStatus status = AgentStatus.OFFLINE;

    private String host;

    private Integer port;

    private String version;

    @Column(length = 1000)
    private String description;

    /** 能力标签,逗号分隔存储(如 "MySQL,H2,CSV") */
    @Column(name = "capabilities", length = 500)
    private String capabilitiesCsv;

    /** 最大并发任务数 */
    private Integer maxConcurrency = 4;

    /** 当前正在执行的任务数(由执行引擎动态维护) */
    private Integer currentTasks = 0;

    /** 最近一次心跳时间 */
    private LocalDateTime lastHeartbeatAt;

    /** 累计执行任务数 */
    private Long totalExecuted = 0L;

    private Long successCount = 0L;

    private Long failCount = 0L;

    /** 平均任务耗时(毫秒) */
    private Long avgDurationMs = 0L;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // ---------- capabilities 数组化读写(仅 JSON 层) ----------

    @Transient
    @JsonProperty("capabilities")
    public List<String> getCapabilities() {
        if (capabilitiesCsv == null || capabilitiesCsv.isBlank()) {
            return new ArrayList<>();
        }
        return Arrays.stream(capabilitiesCsv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @Transient
    @JsonProperty("capabilities")
    public void setCapabilities(List<String> capabilities) {
        this.capabilitiesCsv = capabilities == null ? null : String.join(",", capabilities);
    }

    // ---------- getter / setter ----------

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public AgentType getType() {
        return type;
    }

    public void setType(AgentType type) {
        this.type = type;
    }

    public AgentStatus getStatus() {
        return status;
    }

    public void setStatus(AgentStatus status) {
        this.status = status;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    /** 原始 CSV 存储字段的访问器不参与 JSON 序列化 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getCapabilitiesCsv() {
        return capabilitiesCsv;
    }

    public void setCapabilitiesCsv(String capabilitiesCsv) {
        this.capabilitiesCsv = capabilitiesCsv;
    }

    public Integer getMaxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(Integer maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

    public Integer getCurrentTasks() {
        return currentTasks;
    }

    public void setCurrentTasks(Integer currentTasks) {
        this.currentTasks = currentTasks;
    }

    public LocalDateTime getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public void setLastHeartbeatAt(LocalDateTime lastHeartbeatAt) {
        this.lastHeartbeatAt = lastHeartbeatAt;
    }

    public Long getTotalExecuted() {
        return totalExecuted;
    }

    public void setTotalExecuted(Long totalExecuted) {
        this.totalExecuted = totalExecuted;
    }

    public Long getSuccessCount() {
        return successCount;
    }

    public void setSuccessCount(Long successCount) {
        this.successCount = successCount;
    }

    public Long getFailCount() {
        return failCount;
    }

    public void setFailCount(Long failCount) {
        this.failCount = failCount;
    }

    public Long getAvgDurationMs() {
        return avgDurationMs;
    }

    public void setAvgDurationMs(Long avgDurationMs) {
        this.avgDurationMs = avgDurationMs;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
