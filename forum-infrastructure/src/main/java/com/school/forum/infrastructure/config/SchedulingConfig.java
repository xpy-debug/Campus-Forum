package com.school.forum.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 定时任务与异步执行的统一配置。
 *
 * <p><b>为什么要显式配置线程池，而不是用 {@code @EnableScheduling} 的默认值：</b>
 * 默认调度器只有<b>一个</b>线程。本项目的定时任务不少
 * （本地消息表投递、消费埋点落库、计数刷盘、秒杀活动预热与结算），
 * 只要其中任何一个执行时间超过调度间隔，其余任务就全部被推迟——
 * 表现为「有些任务莫名其妙不按时跑」，而且不会有任何报错，极难排查。
 */
@Configuration
@EnableScheduling
@EnableAsync
public class SchedulingConfig {

    /**
     * 定时任务调度线程池。
     * <p>大小取 4：足够让几个互不相干的定时任务并行，
     * 又不至于让它们无序地争抢数据库连接。
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("forum-sched-");
        // 关闭时等待正在执行的任务结束，避免定时任务做到一半进程就退了，
        // 留下半截状态（例如埋点只写了一半、库存只扣了一半）
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        // 有任务抛异常时不要让整个调度器停摆
        scheduler.setErrorHandler(t -> org.slf4j.LoggerFactory
                .getLogger(SchedulingConfig.class).error("定时任务执行异常", t));
        return scheduler;
    }

    /**
     * 异步任务线程池。用于点赞通知、计数落库这类「不需要用户等待」的操作。
     *
     * <p><b>拒绝策略用 CallerRunsPolicy 而非 AbortPolicy：</b>
     * 队列满时把任务交回调用线程同步执行，形成天然的背压——
     * 上游会因为变慢而降低提交速度。若用 AbortPolicy 直接抛异常，
     * 在流量高峰时表现为大面积业务报错，而实际上系统只是「忙」而已。
     */
    @Bean("forumAsyncExecutor")
    public Executor forumAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(1000);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("forum-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
