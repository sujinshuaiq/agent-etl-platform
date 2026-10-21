package com.dataforge.etl.pipeline.entity;

import com.dataforge.etl.common.enums.AgentType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

/**
 * 管道节点:DAG 中的一个处理步骤,由指定类型的 Agent 执行。
 * config 为 JSON 字符串,结构按 agentType 约定(见 AGENT_SPEC §4.4)。
 */
@Entity
@Table(name = "pipeline_nodes")
public class PipelineNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属管道(不对外序列化,避免循环引用) */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pipeline_id")
    private Pipeline pipeline;

    /** 节点键:管道内唯一,边通过 nodeKey 引用节点 */
    @Column(name = "node_key", nullable = false)
    private String nodeKey;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AgentType agentType;

    /** 关联数据源(可为空) */
    private Long datasourceId;

    /** 节点配置(JSON 字符串) */
    @Lob
    @Column(name = "config")
    private String config;

    /** DAG 编辑器画布坐标 */
    private Integer positionX;

    private Integer positionY;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Pipeline getPipeline() {
        return pipeline;
    }

    public void setPipeline(Pipeline pipeline) {
        this.pipeline = pipeline;
    }

    public String getNodeKey() {
        return nodeKey;
    }

    public void setNodeKey(String nodeKey) {
        this.nodeKey = nodeKey;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public AgentType getAgentType() {
        return agentType;
    }

    public void setAgentType(AgentType agentType) {
        this.agentType = agentType;
    }

    public Long getDatasourceId() {
        return datasourceId;
    }

    public void setDatasourceId(Long datasourceId) {
        this.datasourceId = datasourceId;
    }

    public String getConfig() {
        return config;
    }

    public void setConfig(String config) {
        this.config = config;
    }

    public Integer getPositionX() {
        return positionX;
    }

    public void setPositionX(Integer positionX) {
        this.positionX = positionX;
    }

    public Integer getPositionY() {
        return positionY;
    }

    public void setPositionY(Integer positionY) {
        this.positionY = positionY;
    }
}
