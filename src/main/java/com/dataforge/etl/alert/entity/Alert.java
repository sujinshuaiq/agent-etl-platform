package com.dataforge.etl.alert.entity;

import com.dataforge.etl.common.enums.Severity;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 告警:管道失败、质量检查失败、Agent 离线等事件的告警记录。
 * read 字段映射到 read_flag 列,避免与数据库关键字冲突。
 */
@Entity
@Table(name = "alerts", indexes = {
        @Index(name = "idx_alert_read", columnList = "read_flag")
})
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 告警级别(与质量规则 severity 共用 Severity 枚举) */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity level = Severity.INFO;

    @Column(nullable = false)
    private String title;

    @Column(length = 2000)
    private String content;

    /** 告警来源,如 执行引擎 / 质量检查 / 调度器 / 心跳监控 */
    private String source;

    /** 关联的管道运行(可为空) */
    private Long runId;

    /** 是否已读(列名 read_flag) */
    @Column(name = "read_flag", nullable = false)
    private boolean read = false;

    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        // 种子数据可能显式指定历史时间,仅在未赋值时填充
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Severity getLevel() {
        return level;
    }

    public void setLevel(Severity level) {
        this.level = level;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Long getRunId() {
        return runId;
    }

    public void setRunId(Long runId) {
        this.runId = runId;
    }

    public boolean isRead() {
        return read;
    }

    public void setRead(boolean read) {
        this.read = read;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
