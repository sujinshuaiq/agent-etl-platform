package com.dataforge.etl.engine;

/**
 * 重试策略:由管道配置(retryCount / retryIntervalSeconds)驱动。
 * 纯值对象,记录最大尝试次数与间隔,重试节奏由 PipelineEngine 控制。
 */
public class RetryPolicy {

    private final int retryCount;
    private final int retryIntervalSeconds;

    public RetryPolicy(int retryCount, int retryIntervalSeconds) {
        this.retryCount = Math.max(0, retryCount);
        this.retryIntervalSeconds = Math.max(0, retryIntervalSeconds);
    }

    /** 最大尝试次数 = 1(首次) + 重试次数 */
    public int maxAttempts() {
        return 1 + retryCount;
    }

    public int getRetryCount() {
        return retryCount;
    }

    /** 重试间隔(毫秒) */
    public long intervalMillis() {
        return retryIntervalSeconds * 1000L;
    }

    /** 第 attempt 次(从 1 计)失败后是否还允许重试 */
    public boolean canRetry(int attempt) {
        return attempt < maxAttempts();
    }
}
