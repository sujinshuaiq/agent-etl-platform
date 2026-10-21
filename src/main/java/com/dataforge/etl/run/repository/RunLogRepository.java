package com.dataforge.etl.run.repository;

import com.dataforge.etl.run.entity.RunLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 运行日志数据访问层 */
@Repository
public interface RunLogRepository extends JpaRepository<RunLog, Long> {

    List<RunLog> findByRunIdOrderByTimestampAscIdAsc(Long runId);

    List<RunLog> findByRunIdAndNodeRunIdOrderByTimestampAscIdAsc(Long runId, Long nodeRunId);
}
