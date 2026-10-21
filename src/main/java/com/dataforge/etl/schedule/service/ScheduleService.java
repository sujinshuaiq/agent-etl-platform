package com.dataforge.etl.schedule.service;

import com.dataforge.etl.common.exception.BizException;
import com.dataforge.etl.pipeline.entity.Pipeline;
import com.dataforge.etl.pipeline.repository.PipelineRepository;
import com.dataforge.etl.schedule.entity.ScheduleJob;
import com.dataforge.etl.schedule.repository.ScheduleJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 调度任务业务服务:CRUD 与启停;保存时校验 cron 合法性并预计算下次触发时间。
 */
@Service
public class ScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);

    private final ScheduleJobRepository scheduleRepository;
    private final PipelineRepository pipelineRepository;
    private final CronParser cronParser;

    public ScheduleService(ScheduleJobRepository scheduleRepository,
                           PipelineRepository pipelineRepository,
                           CronParser cronParser) {
        this.scheduleRepository = scheduleRepository;
        this.pipelineRepository = pipelineRepository;
        this.cronParser = cronParser;
    }

    public List<ScheduleJob> listAll() {
        return scheduleRepository.findAll();
    }

    public ScheduleJob getById(Long id) {
        return scheduleRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("调度任务不存在: id=" + id));
    }

    @Transactional
    public ScheduleJob create(ScheduleJob job) {
        job.setId(null);
        fillPipelineName(job);
        cronParser.validate(job.getCronExpression());
        job.setNextFireAt(cronParser.nextFireTime(job.getCronExpression(), LocalDateTime.now()));
        log.info("新增调度任务: 管道 {} cron [{}]", job.getPipelineName(), job.getCronExpression());
        return scheduleRepository.save(job);
    }

    @Transactional
    public ScheduleJob update(Long id, ScheduleJob input) {
        ScheduleJob job = getById(id);
        job.setPipelineId(input.getPipelineId());
        fillPipelineName(job);
        if (input.getCronExpression() != null && !input.getCronExpression().equals(job.getCronExpression())) {
            cronParser.validate(input.getCronExpression());
            job.setCronExpression(input.getCronExpression());
            // cron 变更后重新计算下次触发
            job.setNextFireAt(cronParser.nextFireTime(job.getCronExpression(), LocalDateTime.now()));
        }
        job.setDescription(input.getDescription());
        if (input.getEnabled() != null) {
            job.setEnabled(input.getEnabled());
        }
        return scheduleRepository.save(job);
    }

    @Transactional
    public void delete(Long id) {
        scheduleRepository.delete(getById(id));
    }

    @Transactional
    public ScheduleJob enable(Long id) {
        ScheduleJob job = getById(id);
        job.setEnabled(true);
        job.setNextFireAt(cronParser.nextFireTime(job.getCronExpression(), LocalDateTime.now()));
        return scheduleRepository.save(job);
    }

    @Transactional
    public ScheduleJob disable(Long id) {
        ScheduleJob job = getById(id);
        job.setEnabled(false);
        return scheduleRepository.save(job);
    }

    /** 冗余管道名(管道不存在时给出明确错误) */
    private void fillPipelineName(ScheduleJob job) {
        Pipeline pipeline = pipelineRepository.findById(job.getPipelineId())
                .orElseThrow(() -> BizException.badRequest("管道不存在: id=" + job.getPipelineId()));
        job.setPipelineName(pipeline.getName());
    }
}
