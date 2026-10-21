package com.dataforge.etl.pipeline.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

/**
 * 管道有向边:fromNodeKey → toNodeKey,表示数据/控制依赖方向。
 */
@Entity
@Table(name = "pipeline_edges")
public class PipelineEdge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属管道(不对外序列化,避免循环引用) */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pipeline_id")
    private Pipeline pipeline;

    @Column(name = "from_node_key", nullable = false)
    private String fromNodeKey;

    @Column(name = "to_node_key", nullable = false)
    private String toNodeKey;

    public PipelineEdge() {
    }

    public PipelineEdge(String fromNodeKey, String toNodeKey) {
        this.fromNodeKey = fromNodeKey;
        this.toNodeKey = toNodeKey;
    }

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

    public String getFromNodeKey() {
        return fromNodeKey;
    }

    public void setFromNodeKey(String fromNodeKey) {
        this.fromNodeKey = fromNodeKey;
    }

    public String getToNodeKey() {
        return toNodeKey;
    }

    public void setToNodeKey(String toNodeKey) {
        this.toNodeKey = toNodeKey;
    }
}
