package com.dataforge.etl.message;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Agent 间通信消息(消息总线持久化记录)。
 *
 * <p>平台采用「编排者中介 + 消息总线」的通信模型:Agent 之间不直接互调,
 * 而是把交接/广播消息发布到总线(本表),由总线负责持久化、检索与 SSE 推送。
 * 每条消息可回溯到一次管道运行(runId),构成可审计的通信链路。</p>
 */
@Entity
@Table(name = "agent_message", indexes = {
        @Index(name = "idx_msg_run", columnList = "runId"),
        @Index(name = "idx_msg_created", columnList = "createdAt")
})
public class AgentMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属管道运行 */
    @Column(nullable = false)
    private Long runId;

    /** 管道名称(冗余,便于展示) */
    @Column(length = 128)
    private String pipelineName;

    /** 发送方 Agent 名称 */
    @Column(nullable = false, length = 64)
    private String fromAgent;

    /** 接收方 Agent 名称;null 表示广播 */
    @Column(length = 64)
    private String toAgent;

    /** 消息类型 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MessageType type;

    /** 交接行数(DATA_HANDOFF/DATA_READY 携带) */
    private Long rows;

    /** 消息内容描述 */
    @Column(length = 512)
    private String payload;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Column(nullable = false)
    private LocalDateTime createdAt;

    public AgentMessage() {
    }

    public AgentMessage(Long runId, String pipelineName, String fromAgent, String toAgent,
                        MessageType type, Long rows, String payload) {
        this.runId = runId;
        this.pipelineName = pipelineName;
        this.fromAgent = fromAgent;
        this.toAgent = toAgent;
        this.type = type;
        this.rows = rows;
        this.payload = payload;
        this.createdAt = LocalDateTime.now();
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

    public String getPipelineName() {
        return pipelineName;
    }

    public void setPipelineName(String pipelineName) {
        this.pipelineName = pipelineName;
    }

    public String getFromAgent() {
        return fromAgent;
    }

    public void setFromAgent(String fromAgent) {
        this.fromAgent = fromAgent;
    }

    public String getToAgent() {
        return toAgent;
    }

    public void setToAgent(String toAgent) {
        this.toAgent = toAgent;
    }

    public MessageType getType() {
        return type;
    }

    public void setType(MessageType type) {
        this.type = type;
    }

    public Long getRows() {
        return rows;
    }

    public void setRows(Long rows) {
        this.rows = rows;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
