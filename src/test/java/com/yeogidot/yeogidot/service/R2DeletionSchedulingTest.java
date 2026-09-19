package com.yeogidot.yeogidot.service;

import com.yeogidot.yeogidot.config.R2DeletionConfiguration;
import com.yeogidot.yeogidot.config.R2DeletionSettings;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class R2DeletionSchedulingTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(R2DeletionConfiguration.class, R2DeletionScheduler.class)
            .withBean(R2DeletionWorker.class, () -> mock(R2DeletionWorker.class));

    @Test void 기본_설정에서_스케줄러가_작업자를_호출한다() {
        runner.withPropertyValues("r2.deletion.poll-interval-ms=10").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(R2DeletionSettings.class))
                    .isEqualTo(new R2DeletionSettings(5, 5, 300, 120, 20));
            verify(context.getBean(R2DeletionWorker.class), timeout(2000).atLeastOnce()).runOnce();
        });
    }

    @Test void 비활성화하면_작업은_자동으로_처리하지_않는다() {
        runner.withPropertyValues("r2.deletion.scheduling-enabled=false").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(R2DeletionScheduler.class);
            verifyNoInteractions(context.getBean(R2DeletionWorker.class));
        });
    }

    @Test void 잘못된_재시도_설정은_시작할_때_거절한다() {
        runner.withPropertyValues("r2.deletion.max-attempts=0").run(context ->
                assertThat(context).hasFailed());
    }

    @Test void 재시도_간격은_상한을_넘지_않는다() {
        var settings = new R2DeletionSettings(20, 5, 300, 120, 20);
        assertThat(settings.retryDelaySeconds(1)).isEqualTo(5);
        assertThat(settings.retryDelaySeconds(2)).isEqualTo(10);
        assertThat(settings.retryDelaySeconds(20)).isEqualTo(300);
    }
}
