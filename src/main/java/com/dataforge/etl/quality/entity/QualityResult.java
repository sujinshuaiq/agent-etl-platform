package com.dataforge.etl.quality.entity;

import com.dataforge.etl.common.enums.CheckType;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 质量检查结果:一次规则执行的快照(期望值/实际值/是否通过)。
 */
@Entity
@Table(name = "quality_results", indexes = {
        @Index(name = "idx_qr_rule", columnList = "ruleId"),
        @Index(name = "idx_qr_checked_at", columnList = "checkedAt")
})
public class QualityResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long ruleId;

    private String ruleName;

    /** 关联的管道运行(手工触发的检查可为空) */
    private Long runId;

    /** 执行检查的节点名称 */
    private String nodeName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CheckType checkType;

    /** 期望值描述,如 "NULL 数 = 0"、"行数 >= 1000" */
    private String expected;

    /** 实际值描述 */
    private String actual;

    @Column(nullable = false)
    private Boolean passed;

    private LocalDateTime checkedAt;

    @Column(length = 1000)
    private String detail;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRuleId() {
        return ruleId;
    }

    public void setRuleId(Long ruleId) {
        this.ruleId = ruleId;
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public Long getRunId() {
        return runId;
    }

    public void setRunId(Long runId) {
        this.runId = runId;
    }

    public String getNodeName() {
        return nodeName;
    }

    public void setNodeName(String nodeName) {
        this.nodeName = nodeName;
    }

    public CheckType getCheckType() {
        return checkType;
    }

    public void setCheckType(CheckType checkType) {
        this.checkType = checkType;
    }

    public String getExpected() {
        return expected;
    }

    public void setExpected(String expected) {
        this.expected = expected;
    }

    public String getActual() {
        return actual;
    }

    public void setActual(String actual) {
        this.actual = actual;
    }

    public Boolean getPassed() {
        return passed;
    }

    public void setPassed(Boolean passed) {
        this.passed = passed;
    }

    public LocalDateTime getCheckedAt() {
        return checkedAt;
    }

    public void setCheckedAt(LocalDateTime checkedAt) {
        this.checkedAt = checkedAt;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }
}
