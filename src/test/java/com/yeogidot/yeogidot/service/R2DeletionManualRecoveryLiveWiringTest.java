package com.yeogidot.yeogidot.service;

import com.yeogidot.yeogidot.entity.R2DeletionTask;
import com.yeogidot.yeogidot.repository.R2DeletionTaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 새 실연동 설정의 감사 테이블/실패 주입/기존 작업자 연결을 H2와 Mock S3로 확인한다. */
@SpringJUnitConfig(R2DeletionManualRecoveryLiveWiringTest.Config.class)
@TestPropertySource(properties = {"r2.bucket=yeogidot-r2-delete-test", "r2.public-url=https://r2-test.invalid"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class R2DeletionManualRecoveryLiveWiringTest {
    @Autowired R2DeletionOperationsService operations;
    @Autowired R2DeletionTaskService tasks;
    @Autowired R2DeletionTaskRepository repository;
    @Autowired R2DeletionWorker worker;
    @Autowired R2DeletionLiveTestConfiguration.FaultInjectingGcsService gcs;
    @Autowired R2LiveTestStorage storage;
    @Autowired R2DeletionTestConfiguration.MutableClock clock;
    @Autowired PlatformTransactionManager transactions;

    @Test void 새_설정의_한도초과와_재처리이력을_외부접속없이_확인한다() {
        String url = R2LiveTestStorage.URL_BASE + "/fixture.txt";
        Long id = new TransactionTemplate(transactions).execute(status -> tasks.enqueue(url));
        for (int attempt = 1; attempt <= 3; attempt++) {
            gcs.failNext.set(true); worker.runOnce();
            if (attempt < 3) clock.advance(attempt);
        }
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(R2DeletionTask.Status.FAILED);
        verifyNoInteractions(storage.client());
        operations.retryFailed(id, 0, UUID.randomUUID().toString(), "test operator", "Injected failure removed");
        assertThat(operations.history(id)).hasSize(1);
        worker.runOnce();
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(R2DeletionTask.Status.COMPLETED);
        verify(storage.client(), times(1)).deleteObject(argThat((DeleteObjectRequest request) ->
                request.bucket().equals(R2LiveTestStorage.BUCKET) && request.key().equals("fixture.txt")));
    }

    @Configuration
    static class Config extends R2DeletionManualRecoveryLiveTestConfiguration {
        @Override @Bean(destroyMethod = "shutdown") DataSource dataSource(Environment environment) {
            var source = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
            new ResourceDatabasePopulator(new ClassPathResource("db/manual/20261009_create_r2_deletion_retry_audit.sql"))
                    .execute(source);
            return source;
        }
        @Override @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            return new R2DeletionTestConfiguration().entityManagerFactory(source);
        }
        @Override @Bean R2LiveTestStorage liveStorage() {
            var storage = mock(R2LiveTestStorage.class);
            when(storage.client()).thenReturn(mock(S3Client.class));
            return storage;
        }
    }
}
