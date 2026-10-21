package com.dataforge.etl.quality.entity;

import com.dataforge.etl.common.enums.CheckType;
import com.dataforge.etl.common.enums.Severity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * 数据质量规则:针对某张业务表的某个字段定义检查逻辑。
 * expression 的含义随 checkType 不同:
 *   RANGE_CHECK → "min,max";REGEX_CHECK → 正则;ENUM_CHECK → "A,B,C";
 *   ROW_COUNT → ">=1000" 之类的比较表达式;NULL_CHECK / UNIQUE_CHECK 可为空。
 */
@Entity
@Table(name = "quality_rules")
public class QualityRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "规则名称不能为空")
    @Column(nullable = false)
    private String name;

    /** 目标表名 */
    @NotBlank(message = "目标表不能为空")
    @Column(nullable = false)
    private String targetTable;

    @NotNull(message = "检查类型不能为空")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CheckType checkType;

    /** 目标字段(ROW_COUNT 可为空) */
    @Column(name = "field_name")
    private String field;

    /** 检查表达式 */
    @Column(length = 500)
    private String expression;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity = Severity.WARN;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(length = 1000)
    private String description;

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTargetTable() {
        return targetTable;
    }

    public void setTargetTable(String targetTable) {
        this.targetTable = targetTable;
    }

    public CheckType getCheckType() {
        return checkType;
    }

    public void setCheckType(CheckType checkType) {
        this.checkType = checkType;
    }

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public String getExpression() {
        return expression;
    }

    public void setExpression(String expression) {
        this.expression = expression;
    }

    public Severity getSeverity() {
        return severity;
    }

    public void setSeverity(Severity severity) {
        this.severity = severity;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
