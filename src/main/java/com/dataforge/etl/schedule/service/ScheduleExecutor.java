package com.dataforge.etl.schedule.service;

import com.dataforge.etl.common.enums.TriggerType;
import com.dataforge.etl.engine.PipelineEngine;
import com.dataforge.etl.schedule.entity.ScheduleJob;
import com.dataforge.etl.schedule.repository.ScheduleJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 调度执行器:每秒扫描一次启用中的调度任务,
 * 到达 nextFireAt 即以 SCHEDULED 方式触发管道,并滚动更新 lastFireAt / nextFireAt。
 */
@Component
public class ScheduleExecutor {

    private static final Logger log = LoggerFactory.getLogger(ScheduleExecutor.class);

    private final ScheduleJobRepository scheduleRepository;
    private final CronParser cronParser;
    private final PipelineEngine pipelineEngine;

    public ScheduleExecutor(ScheduleJobRepository scheduleRepository,
                            CronParser cronParser,
                            PipelineEngine pipelineEngine) {
        this.scheduleRepository = scheduleRepository;
        this.cronParser = cronParser;
        this.pipelineEngine = pipelineEngine;
    }

    /** 每秒扫描(启动 20 秒后开始,避开种子初始化窗口) */
    @Scheduled(fixedDelay = 1000, initialDelay = 20000)
    @Transactional
    public void scan() {
        List<ScheduleJob> jobs;
        try {
            jobs = scheduleRepository.findByEnabledTrue();
        } catch (Exception e) {
            log.error("调度扫描读取任务失败: {}", e.getMessage());
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (ScheduleJob job : jobs) {
            try {
                // 兜底:nextFireAt 为空时先补算
                if (job.getNextFireAt() == null) {
                    job.setNextFireAt(cronParser.nextFireTime(job.getCronExpression(), now));
                    scheduleRepository.save(job);
                    continue;
                }
                if (!now.isBefore(job.getNextFireAt())) {
                    log.info("调度触发: 任务 #{} 管道 {} cron [{}]", job.getId(), job.getPipelineName(), job.getCronExpression());
                    pipelineEngine.trigger(job.getPipelineId(), TriggerType.SCHEDULED);
                    job.setLastFireAt(now);
                    job.setNextFireAt(cronParser.nextFireTime(job.getCronExpression(), now));
                    scheduleRepository.save(job);
                }
            } catch (Exception e) {
                // 单个任务失败不影响其他任务
                log.error("调度任务 #{} 触发失败: {}", job.getId(), e.getMessage());
                try {
                    job.setNextFireAt(cronParser.nextFireTime(job.getCronExpression(), now));
                    scheduleRepository.save(job);
                } catch (Exception ignore) {
                    // cron 本身非法时停用,避免每秒钟刷错误日志
                    job.setEnabled(false);
                    scheduleRepository.save(job);
                }
            }
        }
    }
}
