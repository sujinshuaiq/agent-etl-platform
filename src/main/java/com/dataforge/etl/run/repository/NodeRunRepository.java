package com.dataforge.etl.run.repository;

import com.dataforge.etl.run.entity.NodeRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 节点运行数据访问层 */
@Repository
public interface NodeRunRepository extends JpaRepository<NodeRun, Long> {

    List<NodeRun> findByRunIdOrderByIdAsc(Long runId);
}
