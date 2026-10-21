package com.dataforge.etl.datasource.entity;

import com.dataforge.etl.common.enums.DataSourceStatus;
import com.dataforge.etl.common.enums.DataSourceType;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * 数据源实体:connectionConfig 为 JSON 字符串,结构随类型不同
 * (如 MYSQL/H2: {"jdbcUrl","username"};CSV: {"path"};REST_API: {"baseUrl"} 等)。
 */
@Entity
@Table(name = "data_sources")
public class DataSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "名称不能为空")
    @Column(nullable = false)
    private String name;

    @NotBlank(message = "编码不能为空")
    @Column(nullable = false, unique = true)
    private String code;

    @NotNull(message = "类型不能为空")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DataSourceType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DataSourceStatus status = DataSourceStatus.ACTIVE;

    /** 连接配置(JSON 字符串) */
    @Lob
    @Column(name = "connection_config")
    private String connectionConfig;

    @Column(length = 1000)
    private String description;

    /** 最近一次连接测试时间 */
    private LocalDateTime lastTestAt;

    /** 最近一次连接测试是否成功 */
    private Boolean lastTestSuccess;

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

    public DataSourceType getType() {
        return type;
    }

    public void setType(DataSourceType type) {
        this.type = type;
    }

    public DataSourceStatus getStatus() {
        return status;
    }

    public void setStatus(DataSourceStatus status) {
        this.status = status;
    }

    public String getConnectionConfig() {
        return connectionConfig;
    }

    public void setConnectionConfig(String connectionConfig) {
        this.connectionConfig = connectionConfig;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDateTime getLastTestAt() {
        return lastTestAt;
    }

    public void setLastTestAt(LocalDateTime lastTestAt) {
        this.lastTestAt = lastTestAt;
    }

    public Boolean getLastTestSuccess() {
        return lastTestSuccess;
    }

    public void setLastTestSuccess(Boolean lastTestSuccess) {
        this.lastTestSuccess = lastTestSuccess;
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
