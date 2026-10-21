package com.dataforge.etl.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 心跳服务:按 dataforge.heartbeat.interval-seconds 周期执行——
 * 1) 刷新所有 ONLINE Agent 的 lastHeartbeatAt(模拟 Agent 主动上报心跳);
 * 2) 扫描心跳超过 offline-threshold-seconds 的 ONLINE Agent,标记为 OFFLINE。
 * MAINTENANCE 状态的 Agent 不参与心跳,也不被误判离线。
 */
@Component
public class HeartbeatService {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatService.class);

    private final AgentService agentService;

    @Value("${dataforge.heartbeat.offline-threshold-seconds:45}")
    private int offlineThresholdSeconds;

    public HeartbeatService(AgentService agentService) {
        this.agentService = agentService;
    }

    /** 定时心跳:fixedDelayString 支持 SpEL,把秒换算成毫秒 */
    @Scheduled(fixedDelayString = "#{1000 * ${dataforge.heartbeat.interval-seconds:10}}",
            initialDelayString = "#{1000 * ${dataforge.heartbeat.interval-seconds:10}}")
    public void heartbeat() {
        try {
            agentService.refreshHeartbeat();
            int offline = agentService.markStaleAgentsOffline(offlineThresholdSeconds);
            if (offline > 0) {
                log.warn("本轮心跳扫描: {} 个 Agent 因心跳超时被标记离线", offline);
            }
        } catch (Exception e) {
            // 心跳任务不允许把异常抛给调度器
            log.error("心跳任务执行异常: {}", e.getMessage());
        }
    }
}
