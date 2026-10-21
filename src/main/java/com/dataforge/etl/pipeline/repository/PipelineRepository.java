package com.dataforge.etl.pipeline.repository;

import com.dataforge.etl.pipeline.entity.Pipeline;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** 管道数据访问层 */
@Repository
public interface PipelineRepository extends JpaRepository<Pipeline, Long> {

    boolean existsByCode(String code);
}
