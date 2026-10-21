package com.dataforge.etl.run.repository;

import com.dataforge.etl.common.enums.RunStatus;
import com.dataforge.etl.run.entity.PipelineRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/** 管道运行数据访问层(继承 JpaSpecificationExecutor 支持动态筛选) */
@Repository
public interface PipelineRunRepository extends JpaRepository<PipelineRun, Long>, JpaSpecificationExecutor<PipelineRun> {

    PipelineRun findTop1ByPipelineIdOrderByIdDesc(Long pipelineId);

    List<PipelineRun> findTop10ByOrderByIdDesc();

    List<PipelineRun> findByStartTimeAfter(LocalDateTime after);

    long countByStatus(RunStatus status);
}
