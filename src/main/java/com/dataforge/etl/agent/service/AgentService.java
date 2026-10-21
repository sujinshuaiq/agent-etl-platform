package com.dataforge.etl.agent.service;

import com.dataforge.etl.agent.entity.Agent;
import com.dataforge.etl.agent.repository.AgentRepository;
import com.dataforge.etl.common.enums.AgentStatus;
import com.dataforge.etl.common.enums.AgentType;
import com.dataforge.etl.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * Agent 业务服务:CRUD、启停状态流转、运行指标维护、执行引擎的 Agent 选择。
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final AgentRepository agentRepository;

    public AgentService(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    /** 查询全部 Agent(含运行指标) */
    public List<Agent> listAll() {
        return agentRepository.findAll();
    }

    public Agent getById(Long id) {
        return agentRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("Agent 不存在: id=" + id));
    }

    /** 新增 Agent */
    @Transactional
    public Agent create(Agent agent) {
        if (agent.getCode() == null || agent.getCode().isBlank()) {
            throw BizException.badRequest("Agent 编码不能为空");
        }
        if (agentRepository.existsByCode(agent.getCode())) {
            throw BizException.badRequest("Agent 编码已存在: " + agent.getCode());
        }
        agent.setId(null);
        if (agent.getStatus() == null) {
            agent.setStatus(AgentStatus.OFFLINE);
        }
        if (agent.getCurrentTasks() == null) {
            agent.setCurrentTasks(0);
        }
        log.info("新增 Agent: {} ({})", agent.getName(), agent.getCode());
        return agentRepository.save(agent);
    }

    /** 更新 Agent 基础信息(不允许通过本接口改指标) */
    @Transactional
    public Agent update(Long id, Agent input) {
        Agent agent = getById(id);
        // 编码唯一性校验(排除自身)
        if (input.getCode() != null && !input.getCode().equals(agent.getCode())
                && agentRepository.existsByCode(input.getCode())) {
            throw BizException.badRequest("Agent 编码已存在: " + input.getCode());
        }
        agent.setName(input.getName());
        agent.setCode(input.getCode());
        agent.setType(input.getType());
        agent.setHost(input.getHost());
        agent.setPort(input.getPort());
        agent.setVersion(input.getVersion());
        agent.setDescription(input.getDescription());
        agent.setCapabilitiesCsv(input.getCapabilitiesCsv());
        if (input.getMaxConcurrency() != null) {
            agent.setMaxConcurrency(input.getMaxConcurrency());
        }
        if (input.getStatus() != null) {
            agent.setStatus(input.getStatus());
        }
        log.info("更新 Agent: {} ({})", agent.getName(), agent.getCode());
        return agentRepository.save(agent);
    }

    @Transactional
    public void delete(Long id) {
        Agent agent = getById(id);
        if (agent.getCurrentTasks() != null && agent.getCurrentTasks() > 0) {
            throw BizException.badRequest("Agent 正在执行任务,无法删除");
        }
        agentRepository.delete(agent);
        log.info("删除 Agent: {} ({})", agent.getName(), agent.getCode());
    }

    /** 启动:OFFLINE/MAINTENANCE → ONLINE */
    @Transactional
    public Agent start(Long id) {
        Agent agent = getById(id);
        agent.setStatus(AgentStatus.ONLINE);
        agent.setLastHeartbeatAt(LocalDateTime.now());
        log.info("Agent 启动: {}", agent.getName());
        return agentRepository.save(agent);
    }

    /** 停止:→ OFFLINE */
    @Transactional
    public Agent stop(Long id) {
        Agent agent = getById(id);
        agent.setStatus(AgentStatus.OFFLINE);
        log.info("Agent 停止: {}", agent.getName());
        return agentRepository.save(agent);
    }

    /** 重启:短暂置为维护中后立即恢复在线(模拟重启过程) */
    @Transactional
    public Agent restart(Long id) {
        Agent agent = getById(id);
        agent.setStatus(AgentStatus.ONLINE);
        agent.setCurrentTasks(0);
        agent.setLastHeartbeatAt(LocalDateTime.now());
        log.info("Agent 重启: {}", agent.getName());
        return agentRepository.save(agent);
    }

    /**
     * 为节点任务挑选一个 Agent:优先 ONLINE 且负载最低者;
     * 无在线 Agent 时返回 null,由调用方决定失败或等待。
     */
    public Agent pickAgent(AgentType type) {
        List<Agent> online = agentRepository.findByTypeAndStatus(type, AgentStatus.ONLINE);
        if (online.isEmpty()) {
            return null;
        }
        return online.stream()
                .min(Comparator.comparingInt(a -> a.getCurrentTasks() == null ? 0 : a.getCurrentTasks()))
                .orElse(null);
    }

    /** 任务开始:占用一个并发槽位 */
    @Transactional
    public void taskStarted(Long agentId) {
        agentRepository.findById(agentId).ifPresent(agent -> {
            int cur = agent.getCurrentTasks() == null ? 0 : agent.getCurrentTasks();
            agent.setCurrentTasks(cur + 1);
            agentRepository.save(agent);
        });
    }

    /** 任务结束:释放槽位并累计指标(平均耗时按滑动平均更新) */
    @Transactional
    public void taskFinished(Long agentId, boolean success, long durationMs) {
        agentRepository.findById(agentId).ifPresent(agent -> {
            int cur = agent.getCurrentTasks() == null ? 0 : agent.getCurrentTasks();
            agent.setCurrentTasks(Math.max(0, cur - 1));
            long total = agent.getTotalExecuted() == null ? 0 : agent.getTotalExecuted();
            long succ = agent.getSuccessCount() == null ? 0 : agent.getSuccessCount();
            long fail = agent.getFailCount() == null ? 0 : agent.getFailCount();
            long avg = agent.getAvgDurationMs() == null ? 0 : agent.getAvgDurationMs();
            agent.setTotalExecuted(total + 1);
            if (success) {
                agent.setSuccessCount(succ + 1);
            } else {
                agent.setFailCount(fail + 1);
            }
            // 增量式平均:avg_new = (avg_old * n + d) / (n + 1)
            agent.setAvgDurationMs((avg * total + durationMs) / (total + 1));
            agentRepository.save(agent);
        });
    }

    /** 心跳刷新(由 HeartbeatService 定时调用) */
    @Transactional
    public void refreshHeartbeat() {
        List<Agent> online = agentRepository.findByStatus(AgentStatus.ONLINE);
        LocalDateTime now = LocalDateTime.now();
        for (Agent agent : online) {
            agent.setLastHeartbeatAt(now);
        }
        agentRepository.saveAll(online);
    }

    /** 扫描心跳超时:ONLINE 但长时间未上报心跳的 Agent 标记为 OFFLINE(MAINTENANCE 除外) */
    @Transactional
    public int markStaleAgentsOffline(int offlineThresholdSeconds) {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(offlineThresholdSeconds);
        int marked = 0;
        for (Agent agent : agentRepository.findByStatus(AgentStatus.ONLINE)) {
            if (agent.getLastHeartbeatAt() != null && agent.getLastHeartbeatAt().isBefore(threshold)) {
                agent.setStatus(AgentStatus.OFFLINE);
                agentRepository.save(agent);
                marked++;
                log.warn("Agent 心跳超时,已标记离线: {} ({})", agent.getName(), agent.getCode());
            }
        }
        return marked;
    }
}
