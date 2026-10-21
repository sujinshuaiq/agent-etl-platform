package com.dataforge.etl.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 运行事件 SSE 发布器:维护所有订阅 /api/runs/stream 的客户端连接,
 * 执行引擎产生的 RUN_UPDATE / NODE_UPDATE / LOG 事件实时广播给全部订阅端。
 */
@Component
public class RunEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RunEventPublisher.class);

    /** SSE 连接超时:30 分钟 */
    private static final long EMITTER_TIMEOUT = 30 * 60 * 1000L;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ObjectMapper objectMapper;

    public RunEventPublisher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 新客户端订阅,立即发送一条连接确认事件 */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        emitters.add(emitter);
        log.debug("SSE 客户端接入,当前连接数: {}", emitters.size());
        sendTo(emitter, "CONNECTED", Map.of("message", "事件流已连接"));
        return emitter;
    }

    /** 广播事件:{type, payload} */
    public void publish(String type, Object payload) {
        if (emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            sendTo(emitter, type, payload);
        }
    }

    /** 心跳:每 15 秒发送一次 PING,防止代理/浏览器断开空闲连接 */
    @Scheduled(fixedRate = 15000)
    public void heartbeat() {
        if (!emitters.isEmpty()) {
            publish("PING", Map.of("ts", System.currentTimeMillis()));
        }
    }

    private void sendTo(SseEmitter emitter, String type, Object payload) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", type);
            event.put("payload", payload);
            emitter.send(SseEmitter.event()
                    .name("etl-event")
                    .data(objectMapper.writeValueAsString(event), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // 客户端断开:移除连接
            emitters.remove(emitter);
        }
    }
}
