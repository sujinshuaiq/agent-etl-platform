package com.dataforge.etl.pipeline.repository;

import com.dataforge.etl.pipeline.entity.PipelineNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 管道节点数据访问层 */
@Repository
public interface PipelineNodeRepository extends JpaRepository<PipelineNode, Long> {

    List<PipelineNode> findByPipelineIdOrderByIdAsc(Long pipelineId);
}
