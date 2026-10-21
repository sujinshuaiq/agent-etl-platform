package com.dataforge.etl.schedule.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * 调度任务:把管道绑定到一个六位 cron 表达式(秒 分 时 日 月 周)。
 */
@Entity
@Table(name = "schedule_jobs")
public class ScheduleJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull(message = "管道不能为空")
    @Column(nullable = false)
    private Long pipelineId;

    /** 冗余管道名,列表展示免关联 */
    private String pipelineName;

    /** 六位 cron:秒 分 时 日 月 周,支持 * ? * / n 数字 逗号 */
    @NotBlank(message = "cron 表达式不能为空")
    @Column(nullable = false)
    private String cronExpression;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(length = 500)
    private String description;

    private LocalDateTime lastFireAt;

    private LocalDateTime nextFireAt;

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

    public String getCronExpression() {
        return cronExpression;
    }

    public void setCronExpression(String cronExpression) {
        this.cronExpression = cronExpression;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDateTime getLastFireAt() {
        return lastFireAt;
    }

    public void setLastFireAt(LocalDateTime lastFireAt) {
        this.lastFireAt = lastFireAt;
    }

    public LocalDateTime getNextFireAt() {
        return nextFireAt;
    }

    public void setNextFireAt(LocalDateTime nextFireAt) {
        this.nextFireAt = nextFireAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
