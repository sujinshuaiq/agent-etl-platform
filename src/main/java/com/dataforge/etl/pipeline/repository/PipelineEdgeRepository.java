package com.dataforge.etl.pipeline.repository;

import com.dataforge.etl.pipeline.entity.PipelineEdge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 管道边数据访问层 */
@Repository
public interface PipelineEdgeRepository extends JpaRepository<PipelineEdge, Long> {

    List<PipelineEdge> findByPipelineIdOrderByIdAsc(Long pipelineId);
}
