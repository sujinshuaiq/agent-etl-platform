package com.dataforge.etl.schedule.repository;

import com.dataforge.etl.schedule.entity.ScheduleJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 调度任务数据访问层 */
@Repository
public interface ScheduleJobRepository extends JpaRepository<ScheduleJob, Long> {

    List<ScheduleJob> findByEnabledTrue();
}
