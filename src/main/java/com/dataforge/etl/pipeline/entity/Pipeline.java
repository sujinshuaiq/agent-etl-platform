package com.dataforge.etl.pipeline.entity;

import com.dataforge.etl.common.enums.FailureStrategy;
import com.dataforge.etl.common.enums.PipelineStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 管道实体:一棵完整的 DAG,由 nodes(节点)与 edges(有向边)组成。
 * 保存/更新时节点与边整体替换(cascade ALL + orphanRemoval)。
 */
@Entity
@Table(name = "pipelines")
public class Pipeline {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "管道名称不能为空")
    @Column(nullable = false)
    private String name;

    @NotBlank(message = "管道编码不能为空")
    @Column(nullable = false, unique = true)
    private String code;

    @Column(length = 1000)
    private String description;

    /** 业务分类,如 用户域 / 订单域 / 日志域 */
    @Column(length = 100)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PipelineStatus status = PipelineStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FailureStrategy failureStrategy = FailureStrategy.FAIL_FAST;

    /** 节点失败重试次数 */
    private Integer retryCount = 1;

    /** 重试间隔(秒) */
    private Integer retryIntervalSeconds = 5;

    /** 整体超时时间(分钟) */
    private Integer timeoutMinutes = 30;

    @OneToMany(mappedBy = "pipeline", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id ASC")
    private List<PipelineNode> nodes = new ArrayList<>();

    @OneToMany(mappedBy = "pipeline", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id ASC")
    private List<PipelineEdge> edges = new ArrayList<>();

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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public PipelineStatus getStatus() {
        return status;
    }

    public void setStatus(PipelineStatus status) {
        this.status = status;
    }

    public FailureStrategy getFailureStrategy() {
        return failureStrategy;
    }

    public void setFailureStrategy(FailureStrategy failureStrategy) {
        this.failureStrategy = failureStrategy;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public Integer getRetryIntervalSeconds() {
        return retryIntervalSeconds;
    }

    public void setRetryIntervalSeconds(Integer retryIntervalSeconds) {
        this.retryIntervalSeconds = retryIntervalSeconds;
    }

    public Integer getTimeoutMinutes() {
        return timeoutMinutes;
    }

    public void setTimeoutMinutes(Integer timeoutMinutes) {
        this.timeoutMinutes = timeoutMinutes;
    }

    public List<PipelineNode> getNodes() {
        return nodes;
    }

    public void setNodes(List<PipelineNode> nodes) {
        this.nodes = nodes;
    }

    public List<PipelineEdge> getEdges() {
        return edges;
    }

    public void setEdges(List<PipelineEdge> edges) {
        this.edges = edges;
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
