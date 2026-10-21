package com.dataforge.etl.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 执行引擎线程池配置。
 *
 * <p>pipelineExecutor:每个线程负责一条管道运行的整体编排(逐层调度节点)。
 * nodeExecutor:节点级线程池,同一拓扑层内的节点在此池中并行执行。</p>
 */
@Configuration
public class EngineThreadPoolConfig {

    @Value("${dataforge.engine.core-pool-size:8}")
    private int corePoolSize;

    @Value("${dataforge.engine.max-pool-size:16}")
    private int maxPoolSize;

    @Value("${dataforge.engine.queue-capacity:200}")
    private int queueCapacity;

    /** 管道级线程池:并发运行的管道条数不宜过多 */
    @Bean(name = "pipelineExecutor")
    public ThreadPoolTaskExecutor pipelineExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("pipeline-run-");
        // 队列满时由调用线程兜底执行,避免任务被直接拒绝
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /** 节点级线程池:参数来自 application.yml 的 dataforge.engine 配置 */
    @Bean(name = "nodeExecutor")
    public ThreadPoolTaskExecutor nodeExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("etl-node-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
