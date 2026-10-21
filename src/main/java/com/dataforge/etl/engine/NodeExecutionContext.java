package com.dataforge.etl.engine;

import com.dataforge.etl.common.enums.LogLevel;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 节点执行上下文:执行引擎传给节点执行器的全部运行时信息。
 */
public class NodeExecutionContext {

    private final Long runId;
    private final Long nodeRunId;
    private final String nodeKey;
    private final String nodeName;
    private final String configJson;
    private final long rowsIn;
    /** 取消标志:为 true 时节点应尽快停止 */
    private final AtomicBoolean cancelFlag;
    /** 模拟耗时区间(毫秒) */
    private final long minDurationMs;
    private final long maxDurationMs;
    /** 日志回调:落库 + SSE 广播 */
    private final LogSink logSink;

    /** 日志输出口 */
    public interface LogSink {
        void log(LogLevel level, String message);
    }

    public NodeExecutionContext(Long runId, Long nodeRunId, String nodeKey, String nodeName,
                                String configJson, long rowsIn, AtomicBoolean cancelFlag,
                                long minDurationMs, long maxDurationMs, LogSink logSink) {
        this.runId = runId;
        this.nodeRunId = nodeRunId;
        this.nodeKey = nodeKey;
        this.nodeName = nodeName;
        this.configJson = configJson;
        this.rowsIn = rowsIn;
        this.cancelFlag = cancelFlag;
        this.minDurationMs = minDurationMs;
        this.maxDurationMs = maxDurationMs;
        this.logSink = logSink;
    }

    public Long getRunId() {
        return runId;
    }

    public Long getNodeRunId() {
        return nodeRunId;
    }

    public String getNodeKey() {
        return nodeKey;
    }

    public String getNodeName() {
        return nodeName;
    }

    public String getConfigJson() {
        return configJson;
    }

    public long getRowsIn() {
        return rowsIn;
    }

    public long getMinDurationMs() {
        return minDurationMs;
    }

    public long getMaxDurationMs() {
        return maxDurationMs;
    }

    /** 写一条节点级日志 */
    public void log(LogLevel level, String message) {
        logSink.log(level, message);
    }

    public boolean isCanceled() {
        return cancelFlag.get();
    }

    /** 检查取消标志,已取消则抛出 CancelledException 中断节点执行 */
    public void checkCanceled() {
        if (cancelFlag.get()) {
            throw new CancelledException("运行已被取消");
        }
    }

    /**
     * 可中断睡眠:每 100ms 检查一次取消标志。
     */
    public void sleepInterruptibly(long millis) {
        long remaining = millis;
        while (remaining > 0) {
            checkCanceled();
            long slice = Math.min(100, remaining);
            try {
                Thread.sleep(slice);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancelledException("节点执行线程被中断");
            }
            remaining -= slice;
        }
    }

    /** 模拟节点处理耗时(在配置区间内随机) */
    public void simulateWork() {
        long range = Math.max(0, maxDurationMs - minDurationMs);
        long duration = minDurationMs + (range == 0 ? 0 : (long) (Math.random() * range));
        sleepInterruptibly(duration);
    }

    /** 节点被取消时抛出的异常(不触发重试) */
    public static class CancelledException extends RuntimeException {
        public CancelledException(String message) {
            super(message);
        }
    }
}
