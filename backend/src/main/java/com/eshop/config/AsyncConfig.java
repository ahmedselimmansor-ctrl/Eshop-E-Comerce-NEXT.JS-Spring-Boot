package com.eshop.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.slf4j.MDC;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Executors for {@code @Async} work (email, push, search indexing) and {@code @Scheduled} jobs.
 */
@Slf4j
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    /**
     * Copies the SLF4J MDC onto the worker thread.
     *
     * <p>Async tasks otherwise log with an empty {@code trace_id} and {@code requestId}, which
     * breaks the chain exactly where it matters most: the confirmation email that failed to send
     * for the order you are investigating.
     */
    static class MdcPropagatingDecorator implements TaskDecorator {
        @Override
        public Runnable decorate(Runnable runnable) {
            Map<String, String> callerContext = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                try {
                    if (callerContext != null) {
                        MDC.setContextMap(callerContext);
                    }
                    runnable.run();
                } finally {
                    if (previous != null) {
                        MDC.setContextMap(previous);
                    } else {
                        MDC.clear();
                    }
                }
            };
        }
    }

    @Override
    @Bean(name = "taskExecutor")
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("eshop-async-");
        executor.setTaskDecorator(new MdcPropagatingDecorator());
        // When the queue is full, run the task on the calling thread instead of dropping it. That
        // applies natural back-pressure: the caller slows down rather than silently losing an email.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(25);
        executor.initialize();
        return executor;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (Throwable ex, Method method, Object... params) -> {
            log.error("async_task_failed method={}.{}", method.getDeclaringClass().getSimpleName(),
                    method.getName(), ex);
            new SimpleAsyncUncaughtExceptionHandler().handleUncaughtException(ex, method, params);
        };
    }

    /**
     * Scheduler for the reservation sweeper, outbox relay, and nightly cleanup.
     *
     * <p>Sized above the number of registered jobs so a slow job cannot delay the others; every job
     * additionally takes a Redis lock so only one pod in the cluster runs it.
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(5);
        scheduler.setThreadNamePrefix("eshop-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(20);
        scheduler.setErrorHandler(throwable -> log.error("scheduled_task_failed", throwable));
        return scheduler;
    }
}
