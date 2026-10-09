package com.yeogidot.yeogidot.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class R2DeletionFailureMonitorTest {
    @Test void 실패_없음은_경고하지_않고_같은_실패는_반복_출력하지_않는다() {
        var operations = mock(R2DeletionOperationsService.class);
        var monitor = new R2DeletionFailureMonitor(operations);
        Logger logger = (Logger) LoggerFactory.getLogger(R2DeletionFailureMonitor.class);
        var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try {
            var zero = new R2DeletionOperationsService.Summary(0, 0, 0, 0, null);
            var marker = new R2DeletionOperationsService.FailureMarker(1, Instant.parse("2026-10-09T00:00:00Z"));
            var failed = new R2DeletionOperationsService.Summary(0, 0, 0, 1, marker);
            when(operations.summary()).thenReturn(zero, failed, failed,
                    new R2DeletionOperationsService.Summary(0, 0, 0, 1,
                            new R2DeletionOperationsService.FailureMarker(2, marker.updatedAt())), zero);
            monitor.poll(); assertThat(appender.list).isEmpty();
            monitor.poll(); monitor.poll();
            assertThat(appender.list.stream().filter(e -> e.getLevel() == Level.WARN)).hasSize(1);
            monitor.poll(); monitor.poll();
            assertThat(appender.list.stream().filter(e -> e.getLevel() == Level.WARN)).hasSize(2);
            assertThat(appender.list.stream().filter(e -> e.getLevel() == Level.INFO)).hasSize(1);
            assertThat(appender.list).allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain("http"));
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test void 조회_장애는_한번_기록하고_복구후_새_장애는_다시_알린다() {
        var operations = mock(R2DeletionOperationsService.class);
        var monitor = new R2DeletionFailureMonitor(operations);
        Logger logger = (Logger) LoggerFactory.getLogger(R2DeletionFailureMonitor.class);
        var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try {
            when(operations.summary()).thenThrow(new IllegalStateException("secret-password"))
                    .thenThrow(new IllegalStateException("secret-password"))
                    .thenReturn(new R2DeletionOperationsService.Summary(0, 0, 0, 0, null))
                    .thenThrow(new IllegalStateException("secret-password"));
            monitor.poll(); monitor.poll(); monitor.poll(); monitor.poll();
            assertThat(appender.list.stream().filter(e -> e.getLevel() == Level.ERROR)).hasSize(2);
            assertThat(appender.list).allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain("secret-password"));
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test void 스케줄링_연결과_모니터_비활성화를_확인한다() {
        var runner = new ApplicationContextRunner().withUserConfiguration(Scheduling.class, R2DeletionFailureMonitor.class)
                .withBean(R2DeletionOperationsService.class, () -> {
                    var operations = mock(R2DeletionOperationsService.class);
                    when(operations.summary()).thenReturn(new R2DeletionOperationsService.Summary(0, 0, 0, 0, null));
                    return operations;
                });
        runner.withPropertyValues("r2.deletion.monitor-interval-ms=10").run(context -> {
            assertThat(context).hasNotFailed();
            verify(context.getBean(R2DeletionOperationsService.class), timeout(2000).atLeastOnce()).summary();
        });
        runner.withPropertyValues("r2.deletion.monitoring-enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(R2DeletionFailureMonitor.class);
            verifyNoInteractions(context.getBean(R2DeletionOperationsService.class));
        });
        runner.withPropertyValues("r2.deletion.scheduling-enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(R2DeletionFailureMonitor.class);
            verifyNoInteractions(context.getBean(R2DeletionOperationsService.class));
        });
    }
    @Configuration(proxyBeanMethods = false) @EnableScheduling static class Scheduling { }
}
