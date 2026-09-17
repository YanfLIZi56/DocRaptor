package com.yanglizi.docraptor.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务线程池：Spring {@code @Async} + 有界队列，参数全部来自 {@code docraptor.async.*}。
 * 本项目不使用 Redis / MQ，任务进度落 {@code async_task} 表。
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig {

    public static final String EXECUTOR = "docRaptorTaskExecutor";

    @Bean(name = EXECUTOR)
    public TaskExecutor docRaptorTaskExecutor(DocRaptorProperties props) {
        int core = Math.max(1, props.getAsync().getCorePoolSize());
        int max = Math.max(core, props.getAsync().getMaxPoolSize());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(Math.max(1, props.getAsync().getQueueCapacity()));
        executor.setThreadNamePrefix("docraptor-async-");
        // 队列满时由调用线程执行，保证任务不静默丢弃
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        log.info("docRaptorTaskExecutor 初始化完成 core={} max={} queue={}", core, max,
                props.getAsync().getQueueCapacity());
        return executor;
    }
}
