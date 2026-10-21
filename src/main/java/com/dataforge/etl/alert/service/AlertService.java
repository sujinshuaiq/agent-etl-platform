package com.dataforge.etl.alert.service;

import com.dataforge.etl.alert.entity.Alert;
import com.dataforge.etl.alert.repository.AlertRepository;
import com.dataforge.etl.common.enums.Severity;
import com.dataforge.etl.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 告警服务:告警的产生(供执行引擎/质量模块调用)、分页查询、已读管理。
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final AlertRepository alertRepository;

    public AlertService(AlertRepository alertRepository) {
        this.alertRepository = alertRepository;
    }

    /** 产生一条告警 */
    @Transactional
    public Alert raise(Severity level, String title, String content, String source, Long runId) {
        Alert alert = new Alert();
        alert.setLevel(level);
        alert.setTitle(title);
        alert.setContent(content);
        alert.setSource(source);
        alert.setRunId(runId);
        alert.setRead(false);
        log.info("产生告警 [{}] {} - {}", level, title, source);
        return alertRepository.save(alert);
    }

    /** 分页查询(read 为 null 时查全部) */
    public Page<Alert> page(Boolean read, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        if (read == null) {
            return alertRepository.findAll(pageable);
        }
        return alertRepository.findByReadOrderByIdDesc(read, pageable);
    }

    @Transactional
    public Alert markRead(Long id) {
        Alert alert = alertRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("告警不存在: id=" + id));
        alert.setRead(true);
        return alertRepository.save(alert);
    }

    @Transactional
    public int markAllRead() {
        return alertRepository.markAllRead();
    }

    @Transactional
    public void delete(Long id) {
        Alert alert = alertRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("告警不存在: id=" + id));
        alertRepository.delete(alert);
    }

    public long countUnread() {
        return alertRepository.countByReadFalse();
    }
}
